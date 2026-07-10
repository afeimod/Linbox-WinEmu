package org.github.ewt45.winemulator.prootdistro

import org.github.ewt45.winemulator.ui.components.TaskReporter
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * 参考 `proot_distro/helpers/tar_extract.py`——
 * 把一个 (gzipped) tar 流或 tar 文件解开到 rootfs 目录。
 *
 * 关键不变量 (跟 Python 版一致):
 *   - 跳过 block / char / FIFO / socket entries
 *   - 拒绝含 ".." 或空路径段的 entry
 *   - 解析路径时穿过任意 symlink,每一步都限制在 rootfs 内,
 *     防止 `evil -> /` 或 `evil -> ../../etc` 类型的目录穿越
 *   - hardlink 推迟到所有 regular file 写完后再拷贝,源和目标都
 *     重新 safe-resolve 一遍,防止后来插入的 symlink 改写目标
 *   - OCI whiteout:
 *       .wh.<name>           删除同名兄弟
 *       .wh..wh..opq         清空父目录
 *   - 目录的 mtime 最后再 stamp (写文件会 bump mtime)
 *   - 目录至少 S_IRWXU,否则后续写入可能失败
 */
object ProotDistroExtract {

    /**
     * 解压 tarball 到 [rootfsDir]。
     *
     * @param archive tar 文件 (支持 raw tar / .tar.gz / .tar / Docker layer gzip tar)
     * @param rootfsDir 目标目录 (必须存在)
     * @param handleWhiteouts 是否处理 OCI whiteout entry
     * @param reporter 进度报告器
     */
    fun extractTarToRootfs(
        archive: File,
        rootfsDir: File,
        handleWhiteouts: Boolean = false,
        reporter: TaskReporter? = null,
    ) {
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        val totalSize = archive.length()
        val deferredLinks = ArrayList<Pair<List<String>, List<String>>>()
        val deferredDirs = ArrayList<Pair<File, Long>>()
        var entriesProcessed = 0
        var entriesSkipped = 0
        var entriesWhiteout = 0
        var entriesSymlink = 0
        var entriesFile = 0
        var entriesDir = 0
        var entriesHardlink = 0

        val rawFh = BufferedInputStream(FileInputStream(archive))
        try {
            val counter = CountingInputStream(rawFh)
            val (compression, streamForTar) = detectCompression(counter)
            val toRead = when (compression) {
                Compression.GZIP -> GZIPInputStream(streamForTar)
                Compression.XZ -> org.tukaani.xz.XZInputStream(streamForTar)
                Compression.NONE -> streamForTar
            }
            val tis = TarArchiveInputStream(toRead, 1024 * 1024)
            try {
                var entry: TarArchiveEntry? = tis.nextTarEntry
                while (entry != null) {
                    entriesProcessed++
                    val before = entriesSkipped
                    processEntry(
                        entry, tis, rootfsDir,
                        handleWhiteouts = handleWhiteouts,
                        deferredLinks = deferredLinks,
                        deferredDirs = deferredDirs,
                        statCallback = { kind ->
                            when (kind) {
                                StatKind.WHITEOUT -> entriesWhiteout++
                                StatKind.SYMLINK -> entriesSymlink++
                                StatKind.FILE -> entriesFile++
                                StatKind.DIR -> entriesDir++
                                StatKind.HARDLINK -> entriesHardlink++
                                StatKind.SKIPPED -> entriesSkipped++
                            }
                        },
                    )
                    if (totalSize > 0 && reporter != null) {
                        val percent = (counter.count * 100 / totalSize).toInt().coerceIn(0, 100)
                        reporter.progress(percent / 100f)
                    }
                    entry = tis.nextTarEntry
                }
            } finally {
                tis.close()
            }
        } finally {
            rawFh.close()
        }
        reporter?.msg("    解压 entry 统计: 共 $entriesProcessed 个, " +
                "文件=$entriesFile, 目录=$entriesDir, symlink=$entriesSymlink, " +
                "hardlink=$entriesHardlink, whiteout=$entriesWhiteout, 跳过=$entriesSkipped")

        // 第一阶段:所有 regular file 写完,现在处理 deferred hardlinks
        for ((destParts, srcParts) in deferredLinks) {
            val parent = safeResolve(rootfsDir, destParts.dropLast(1)) ?: continue
            val dest = File(parent, destParts.last())
            val src = safeResolve(rootfsDir, srcParts) ?: continue
            if (dest.exists() || Files.isSymbolicLink(dest.toPath())) {
                try { Files.delete(dest.toPath()) } catch (_: IOException) {}
            }
            if (src.isFile) {
                try {
                    src.copyTo(dest, overwrite = true)
                    preserveModeAndMtime(src, dest, src.lastModified())
                } catch (_: IOException) {}
            }
        }

        // 第二阶段:stamp 目录 mtime (reverse 顺序,因为后写的子目录 mtime 应该被父目录覆盖)
        for ((dir, mtime) in deferredDirs.reversed()) {
            try {
                dir.setLastModified(mtime)
            } catch (_: Throwable) {}
        }
    }

    /** 统计信息 category for processEntry */
    private enum class StatKind { FILE, DIR, SYMLINK, HARDLINK, WHITEOUT, SKIPPED }

    private fun processEntry(
        member: TarArchiveEntry,
        tis: TarArchiveInputStream,
        rootfsDir: File,
        handleWhiteouts: Boolean,
        deferredLinks: MutableList<Pair<List<String>, List<String>>>,
        deferredDirs: MutableList<Pair<File, Long>>,
        statCallback: (StatKind) -> Unit = {},
    ) {
        // 跳过 block/char/FIFO
        if (member.isBlockDevice || member.isCharacterDevice || member.isFIFO) {
            statCallback(StatKind.SKIPPED); return
        }
        // 跳过 socket (commons-compress 没 isSocket,用 mode 位检测)
        // S_IFSOCK = 0o140000 = 61440
        if ((member.mode.toInt() and 61440) == 61440) {
            statCallback(StatKind.SKIPPED); return
        }

        val rawName = member.name.trim('/')
        val parts = rawName.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) { statCallback(StatKind.SKIPPED); return }
        if (parts.any { it == ".." }) {
            statCallback(StatKind.SKIPPED)
            android.util.Log.w("ProotDistroExtract", "skip path with .. : $rawName")
            return
        }

        // parent 走 safe-resolve,但最后一个 component 不跟 (我们要操作它自己)
        val parent = safeResolve(rootfsDir, parts.dropLast(1))
        if (parent == null) { statCallback(StatKind.SKIPPED); return }
        val dest = File(parent, parts.last())

        if (handleWhiteouts && applyWhiteout(parts, parent)) {
            statCallback(StatKind.WHITEOUT); return
        }

        if (!parent.exists()) parent.mkdirs()

        when {
            member.isDirectory -> {
                // 现有同名 symlink 替换为目录 (overlay semantics)
                if (Files.isSymbolicLink(dest.toPath())) {
                    deleteRecursively(dest)
                }
                if (!dest.exists()) dest.mkdirs()
                try {
                    // 0o7777 = 4095
                    val mode = member.mode.toInt() and 4095
                    dest.setWritable(true, true)
                    dest.setReadable(true, true)
                    dest.setExecutable(true, true)
                    if (mode != 0) {
                        // 用 Runtime.exec("chmod") 太重,这里通过 Files.setPosixFilePermissions 设置
                        runCatching {
                            // 0o700 = 448
                            val perms = PosixMode.toPosixFilePermissions(mode or 448)
                            Files.setPosixFilePermissions(dest.toPath(), perms)
                        }
                    }
                } catch (_: Throwable) {}
                deferredDirs.add(dest to (member.lastModifiedDate?.time ?: 0L))
                statCallback(StatKind.DIR)
            }

            member.isSymbolicLink -> {
                if (dest.exists() || Files.isSymbolicLink(dest.toPath())) {
                    try { Files.delete(dest.toPath()) } catch (_: IOException) {}
                }
                try {
                    val linkName = member.linkName ?: ""
                    Files.createSymbolicLink(dest.toPath(), java.nio.file.Paths.get(linkName))
                    runCatching {
                        Files.setLastModifiedTime(
                            dest.toPath(),
                            java.nio.file.attribute.FileTime.fromMillis(member.lastModifiedDate?.time ?: 0L)
                        )
                    }
                } catch (_: Throwable) {}
                statCallback(StatKind.SYMLINK)
            }

            member.isLink -> {
                // hardlink: 解析 linkname 的相对组件
                val linkName = (member.linkName ?: "").trim('/')
                val linkParts = linkName.split('/').filter { it.isNotEmpty() }
                if (linkParts.any { it == ".." }) { statCallback(StatKind.SKIPPED); return }
                deferredLinks.add(parts to linkParts)
                statCallback(StatKind.HARDLINK)
            }

            member.isFile -> {
                if (dest.exists() || Files.isSymbolicLink(dest.toPath())) {
                    try { Files.delete(dest.toPath()) } catch (_: IOException) {}
                }
                try {
                    dest.outputStream().use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = tis.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                        }
                    }
                    try {
                        // 0o7777 = 4095
                        val mode = member.mode.toInt() and 4095
                        if (mode != 0) {
                            val perms = PosixMode.toPosixFilePermissions(mode)
                            Files.setPosixFilePermissions(dest.toPath(), perms)
                        }
                    } catch (_: Throwable) {}
                    try {
                        dest.setLastModified(member.lastModifiedDate?.time ?: 0L)
                    } catch (_: Throwable) {}
                    statCallback(StatKind.FILE)
                } catch (e: Throwable) {
                    statCallback(StatKind.SKIPPED)
                    android.util.Log.w("ProotDistroExtract",
                        "解压文件失败: $rawName (${e::class.simpleName}: ${e.message})")
                }
            }
        }
    }

    /** 处理 OCI whiteout,返回 true 表示已处理 */
    private fun applyWhiteout(parts: List<String>, parent: File): Boolean {
        val basename = parts.last()
        if (basename == ".wh..wh..opq") {
            // opaque whiteout: 清空 parent 内所有内容
            val children = parent.listFiles() ?: return true
            for (c in children) deleteRecursively(c)
            return true
        }
        if (basename.startsWith(".wh.")) {
            val target = File(parent, basename.removePrefix(".wh."))
            if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                deleteRecursively(target)
            }
            return true
        }
        return false
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
            f.listFiles()?.forEach { deleteRecursively(it) }
        }
        try { Files.delete(f.toPath()) } catch (_: IOException) {}
    }

    private fun preserveModeAndMtime(src: File, dest: File, mtime: Long) {
        try {
            val view = Files.getFileAttributeView(src.toPath(), PosixFileAttributeView::class.java)
            val perms = view.readAttributes().permissions()
            Files.setPosixFilePermissions(dest.toPath(), perms)
        } catch (_: Throwable) {}
        try { dest.setLastModified(mtime) } catch (_: Throwable) {}
    }

    /**
     * 解析 [parts] 组件到 rootfs 之内。每一步穿过一个 symlink,但
     * 永远不许跨出 rootfs。如果陷入 symlink loop (link_budget 用尽),
     * 返回 null 表示放弃此 entry。
     */
    private fun safeResolve(root: File, parts: List<String>): File? {
        val resolved = ArrayDeque<String>()
        val pending = ArrayDeque<String>().apply { addAll(parts) }
        var linkBudget = 40
        while (pending.isNotEmpty()) {
            val comp = pending.removeFirst()
            when {
                comp.isEmpty() || comp == "." -> {}
                comp == ".." -> { if (resolved.isNotEmpty()) resolved.removeLast() }
                else -> {
                    val current = File(root, resolved.joinToString("/") + if (resolved.isEmpty()) "/$comp" else "/$comp")
                    if (Files.isSymbolicLink(current.toPath())) {
                        linkBudget--
                        if (linkBudget < 0) return null
                        val target = try {
                            Files.readSymbolicLink(current.toPath()).toString()
                        } catch (_: IOException) { return null }
                        val tparts = target.split('/').filter { it.isNotEmpty() }
                        if (target.startsWith("/")) {
                            resolved.clear()  // 绝对路径 -> 重定向到 root 下
                        }
                        pending.addAll(0, tparts)
                    } else {
                        resolved.addLast(comp)
                    }
                }
            }
        }
        return File(root, resolved.joinToString("/"))
    }

    /** 压缩格式 */
    private enum class Compression { GZIP, XZ, NONE }

    /**
     * 探测 stream 开头的 magic bytes, 返回 (压缩格式, 可被 tar 重新读取的 stream)。
     * 优先走 mark/reset; 不支持时改为 read + pushback SequenceInputStream。
     */
    private fun detectCompression(input: java.io.InputStream): Pair<Compression, java.io.InputStream> {
        // 需要读 6 字节: gzip=2, xz=6 magic "FD 37 7A 58 5A 00"
        val need = 6
        val head: ByteArray
        if (input.markSupported()) {
            input.mark(need)
            val buf = ByteArray(need)
            var total = 0
            while (total < need) {
                val n = input.read(buf, total, need - total)
                if (n < 0) break
                total += n
            }
            input.reset()
            head = if (total == need) buf else buf.copyOf(total)
        } else {
            val buf = ByteArray(need)
            var total = 0
            while (total < need) {
                val n = input.read(buf, total, need - total)
                if (n < 0) break
                total += n
            }
            head = if (total == need) buf else buf.copyOf(total)
            val pushback = java.io.SequenceInputStream(
                java.io.ByteArrayInputStream(head),
                input,
            )
            return classifyCompression(head) to pushback
        }
        return classifyCompression(head) to input
    }

    private fun classifyCompression(head: ByteArray): Compression {
        // gzip: 1F 8B
        if (head.size >= 2 &&
            (head[0].toInt() and 0xFF) == 0x1F &&
            (head[1].toInt() and 0xFF) == 0x8B) {
            return Compression.GZIP
        }
        // xz: FD 37 7A 58 5A 00
        if (head.size >= 6 &&
            (head[0].toInt() and 0xFF) == 0xFD &&
            (head[1].toInt() and 0xFF) == 0x37 &&
            (head[2].toInt() and 0xFF) == 0x7A &&
            (head[3].toInt() and 0xFF) == 0x58 &&
            (head[4].toInt() and 0xFF) == 0x5A &&
            (head[5].toInt() and 0xFF) == 0x00) {
            return Compression.XZ
        }
        return Compression.NONE
    }

    /** 简易 InputStream 包装,统计读取字节数。
     * 透传 mark/reset/markSupported 给底层委托,这样 mark/reset 能被 GZIPInputStream 正确调用。
     */
    private class CountingInputStream(private val delegate: java.io.InputStream) : java.io.InputStream() {
        var count: Long = 0L
            private set
        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) count++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) count += n
            return n
        }
        override fun available() = delegate.available()
        override fun skip(n: Long): Long = delegate.skip(n)
        override fun mark(readlimit: Int) = delegate.mark(readlimit)
        override fun reset() = delegate.reset()
        override fun markSupported(): Boolean = delegate.markSupported()
        override fun close() = delegate.close()
    }
}

/** 把 int mode 转成 PosixFilePermissions (通过 enum 名称) */
private object PosixMode {
    private val PERMS: List<Pair<Int, java.nio.file.attribute.PosixFilePermission>> = listOf(
        // 用十进制写八进制,避免 Kotlin 旧版不支持 0o 字面量
        Pair(256, java.nio.file.attribute.PosixFilePermission.OWNER_READ),       // 0o400
        Pair(128, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),      // 0o200
        Pair(64,  java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE),    // 0o100
        Pair(32,  java.nio.file.attribute.PosixFilePermission.GROUP_READ),       // 0o040
        Pair(16,  java.nio.file.attribute.PosixFilePermission.GROUP_WRITE),      // 0o020
        Pair(8,   java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE),    // 0o010
        Pair(4,   java.nio.file.attribute.PosixFilePermission.OTHERS_READ),      // 0o004
        Pair(2,   java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE),     // 0o002
        Pair(1,   java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE),   // 0o001
    )

    fun toPosixFilePermissions(mode: Int): Set<java.nio.file.attribute.PosixFilePermission> {
        val out = HashSet<java.nio.file.attribute.PosixFilePermission>()
        for ((bit, perm) in PERMS) {
            if ((mode and bit) != 0) out.add(perm)
        }
        return out
    }
}