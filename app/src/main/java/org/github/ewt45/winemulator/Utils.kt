package org.github.ewt45.winemulator

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.system.Os
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okio.Buffer
import okio.source
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.CompressorInputStream
import org.apache.commons.compress.compressors.CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.apache.commons.compress.utils.InputStreamStatistics
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.github.ewt45.winemulator.Consts.Pref.Local.curr_rootfs_name
import org.github.ewt45.winemulator.Consts.rootfsAllDir
import org.github.ewt45.winemulator.Consts.rootfsCurrDir
import org.github.ewt45.winemulator.Utils.Files.selfExists
import org.github.ewt45.winemulator.ui.components.TaskReporter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolutePathString
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readSymbolicLink
import kotlin.math.pow
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

object Utils {
    private const val TAG = "Utils"

    /**
     * 计算sha256的值。比较时注意全部转为大/小写
     */
    suspend fun calculateSha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 8) // 8KB缓冲区
        FileInputStream(file).use { inputStream ->
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        val hashBytes = digest.digest()
        // 转换为十六进制字符串
        return@withContext hashBytes.joinToString("") { "%02x".format(it) }
    }


    /**
     * 输入流内容复制到输出流。使用kt的copyTo 会自动使用buffer. autoCLose是否复制完关闭流，默认开启
     */
    fun streamCopy(input: InputStream, output: OutputStream, autoClose: Boolean = true) {
        input.copyTo(output)
        if (autoClose) {
            output.close()
            input.close()
        }
    }

    suspend fun readLinesProcessOutput(process: Process): String = withContext(Dispatchers.IO) {
        val output: String
        BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            output = lines.joinToString(separator = "\n")
        }
        return@withContext output
    }

    /**
     * 下载链接。
     * @param link http网址
     * @param dstFile 下载为该本地文件
     */
    fun downloadLink(link: String, dstFile: File, onProgress: ((Int) -> Unit)? = null) {
        val conn = URL(link).openConnection()
        val total = conn.contentLengthLong
        conn.getInputStream().use { input ->
            FileOutputStream(dstFile).use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) {
                        onProgress?.invoke(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
    }

    fun createShareTextIntent(text: String): Intent {
        return Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
    }

    /** 获取进程的pid */
    fun Process.getPid(): Int {
        try {
            val property = this::class.declaredMemberProperties.filterIsInstance<KProperty1<Process, Int>>().find { it.name == "pid" }
            if (property == null) return -1
            property.isAccessible = true
            val pid = property.get(this)
            property.isAccessible = false
            return pid
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        }
    }

    /**
     * 获取x11Service对应的进程pid。原理：通过对比进程名（在Manifest中设置的）
     */
    fun Context.getX11ServicePid(): Int {
        return getSystemService(ActivityManager::class.java).runningAppProcesses
            .find { it.processName == "$packageName:xserver" }?.pid ?: -1
    }

    /**
     * chmod. 添加try catch 传入mode为8进制数字的字符串，例如“755”
     */
    fun chmod(file: File, mode: String) {
        try {
            Os.chmod(file.absolutePath, mode.toInt(8))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun File.notExists(): Boolean = !this.exists()

    /** 判断输入流是否为zstd压缩包 */
    fun InputStream.isZstd(): Boolean = checkStreamHeaderMagic(byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte()))

    /** 判断该文件是否为zstd压缩包 */
    fun File.isZstd(): Boolean = checkFileHeaderMagic(byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte()))

    /** 检查输入流头是否为给定标识 */
    private fun InputStream.checkStreamHeaderMagic(header: ByteArray): Boolean {
        try {
            val len = header.size.toLong()
            val fileHeader = source().use { source -> Buffer().also { source.read(it, len) }.readByteArray(len) }
            for (i in 0 until len.toInt())
                if (header[i] != fileHeader[i])
                    return false
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** 检查文件头是否为给定标识 */
    private fun File.checkFileHeaderMagic(header: ByteArray): Boolean {
        try {
            val len = header.size.toLong()
            val fileHeader = source().use { source -> Buffer().also { source.read(it, len) }.readByteArray(len) }
            for (i in 0 until len.toInt())
                if (header[i] != fileHeader[i])
                    return false
            return true
        } catch (e: Exception) {
            return false
        }
    }


    fun printStackTraceToString(e: Throwable): String = e.stackTraceToString()

    /** 检查文件头是否为给定标识. 从输入流当前位置开始读取 header.size 个字节并进行比较。 */
    private fun InputStream.checkHeaderMagic(header: ByteArray): Boolean {
        try {
            val len = header.size.toLong()
            val fileHeader = source().use { source -> Buffer().also { source.read(it, len) }.readByteArray(len) }
            for (i in 0 until len.toInt())
                if (header[i] != fileHeader[i])
                    return false
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** 判断该文件是否为gz压缩包 */
    fun InputStream.isGzip(): Boolean = checkHeaderMagic(byteArrayOf(0x1F.toByte(), 0x8B.toByte()))

    /** 判断该文件是否为xz压缩包 */
    fun InputStream.isXz(): Boolean = checkHeaderMagic(org.tukaani.xz.XZ.HEADER_MAGIC)

    /** 打开uri的输入流。等于 contentResolver.openInputStream(uri) */
    fun Context.openInput(uri: Uri): InputStream? = contentResolver.openInputStream(uri)

    /** 打开uri的输出流。等于 contentResolver.openOutputStream(uri) */
    fun Context.openOutput(uri: Uri): OutputStream? = contentResolver.openOutputStream(uri)

    /** 遍历这些Path及其子Path，广度优先 */
    inline fun MutableList<Path>.walk(callback: (Path) -> Unit) {
        while (isNotEmpty()) {
            val path = removeAt(0)
            callback(path)
            if (path.isDirectory(LinkOption.NOFOLLOW_LINKS)) addAll(path.listDirectoryEntries())
        }
    }

    /** 返回一个path的权限（755这种） */
    fun Path.getMode(): Int {
        var result = 0
        //TODO 代码缩减时enum的ordinal会保留吗
        getPosixFilePermissions(LinkOption.NOFOLLOW_LINKS).forEach { item ->
            result = result or (1 * 2.0.pow((8 - item.ordinal))).toInt() //懒得挨个对比了，直接用序号，应该没问题吧？
        }
        return result
    }

    /** 在[Activity.onSaveInstanceState] 中设置。在 [Activity.onCreate] 中获取判断 */
    var Bundle.activityRecreate: Boolean
        get() = this.getBoolean("activityRecreate", false)
        set(value) = this.putBoolean("activityRecreate", value)

    object Files {
        suspend fun writeToUri(ctx: Context, uri: Uri, content: String): Result<Unit> = withContext(Dispatchers.IO) {
            kotlin.runCatching {
                val result = ctx.contentResolver.openOutputStream(uri)?.use { output ->
                    IOUtils.write(content, output, StandardCharsets.UTF_8)
                }
                if (result == null)
                    throw RuntimeException("无法获取文件输出流")
            }
        }

        suspend fun readFromUri(ctx: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
            kotlin.runCatching {
                val jsonStr = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    IOUtils.readLines(input, StandardCharsets.UTF_8).joinToString(separator = "")
                }
                if (jsonStr == null)
                    throw RuntimeException("无法获取文件输入流")
                return@runCatching jsonStr
            }
        }

        /** 创建符号链接。会检查要成为符号链接的路径，如果已经有一个文件夹且不为符号链接且有内容，则抛出异常 */
        fun symlink(realFile: File, linkFile: File) {
            if (linkFile.exists() && !FileUtils.isSymlink(linkFile) && !linkFile.list().isNullOrEmpty()) {
                Log.e(
                    TAG,
                    "symlink: 停止创建符号链接！要成为符号链接的文件路径已经是一个文件夹，不为符号链接且内部不为空。删除该文件夹可能丢失文件。\n realFile=$realFile, linkFile=$linkFile"
                )
                return
            }
            try {
                linkFile.delete()
                linkFile.parentFile?.mkdirs()
                Os.symlink(realFile.absolutePath, linkFile.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /** FileUtils.writeStringToFile， 保证字符串结尾是一个换行。但是这个函数不会trimIndent */
        fun writeStringToFileWithLF(file: File, str: String, charset: Charset = StandardCharsets.UTF_8) {
            val str1 = str.takeIf { it.endsWith('\n') }?.let { it + "\n" } ?: str
            FileUtils.writeStringToFile(file, str1, charset)
        }

        /** 判断该文件是否为gz压缩包 */
        fun File.isGzip(): Boolean = checkHeaderMagic(byteArrayOf(0x1F.toByte(), 0x8B.toByte()))

        /** 判断该文件是否为xz压缩包 */
        fun File.isXz(): Boolean = checkHeaderMagic(org.tukaani.xz.XZ.HEADER_MAGIC)

        /** 检查文件头是否为给定标识 */
        private fun File.checkHeaderMagic(header: ByteArray): Boolean {
            try {
                val len = header.size.toLong()
                val fileHeader = source().use { source -> Buffer().also { source.read(it, len) }.readByteArray(len) }
                for (i in 0 until len.toInt())
                    if (header[i] != fileHeader[i])
                        return false
                return true
            } catch (e: Exception) {
                return false
            }
        }

        /** 获取自身是否存在。如果为软链接，查看自己而非指向的实际文件是否存在。file.exists()不可靠，用nio的吧 */
        fun File.selfExists(): Boolean = java.nio.file.Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    object Rootfs {
        /** 别名文件名，存放在 rootfs 目录下 */
        private const val ALIAS_FILE_NAME = ".alias"

        /**
         * 获取 rootfs 的显示别名
         * @param rootfsDir rootfs 目录
         * @return 别名，如果不存在则返回文件夹名
         */
        fun getAlias(rootfsDir: File): String {
            val aliasFile = File(rootfsDir, ALIAS_FILE_NAME)
            return if (aliasFile.exists()) {
                aliasFile.readText().trim().takeIf { it.isNotEmpty() } ?: rootfsDir.name
            } else {
                rootfsDir.name
            }
        }

        /**
         * 设置 rootfs 的别名
         * @param rootfsDir rootfs 目录
         * @param alias 别名，为空时删除别名文件
         */
        fun setAlias(rootfsDir: File, alias: String) {
            val aliasFile = File(rootfsDir, ALIAS_FILE_NAME)
            if (alias.isBlank()) {
                aliasFile.delete()
            } else {
                aliasFile.writeText(alias)
            }
        }

        /**
         * 将某一个rootfs激活为当前rootfs（之后可通过rootfsCurrDir 获取)
         * 会将该rootfs文件名保存到datastore
         */
        suspend fun makeCurrent(rootfsDir: File) {
            Files.symlink(rootfsDir, rootfsCurrDir)
            dataStore.edit { it[curr_rootfs_name.key] = rootfsDir.name }
        }

        /**
         * 如果rootfs文件夹下为空或仅有current文件夹，则返回true,此时应该先提醒用户选择一个rootfs
         */
        fun haveNoRootfs(): Boolean {
            val currName = rootfsCurrDir.name
            return !(rootfsAllDir.listFiles()?.any { it.name != currName } ?: false)

        }

        /**
         * 解压一个压缩包(目前支持.tar.xz、.tar.gz 和 .tar.zst) ，其内含一个rootfs, 将其解压到outDir.
         * 解压后，outDir为 [Consts.rootfsAllDir] 中的一个目录，其内部为 bin etc 这种的目录
         * 解压后会做一些处理操作，参考 [postExtractRootfs]
         * uri不是.tar.xz/.tar.gz/.tar.zst时会抛出异常
         * @param reporter 调用[TaskReporter.progressValue] 时传入的是某文件压缩后大小. 本函数会将[TaskReporter.totalValue] 设置为压缩文件总大小
         */
        suspend fun installRootfsFromUrl(
            ctx: Context,
            url: String,
            reporter: TaskReporter
        ): File = withContext(Dispatchers.IO) {
            val tempFile = File(Consts.tmpDir, "download-rootfs.tar.xz").also {
                if (it.exists()) it.delete()
            }

            reporter.msg("开始下载Rootfs：$url")
            Utils.downloadLink(url, tempFile) { percent ->
                reporter.progress(percent.toFloat() / 100f)
                reporter.msg("下载进度：$percent%")
            }

            reporter.msg("下载完成，开始解压...")
            installRootfsArchive(ctx, Uri.fromFile(tempFile), reporter)
        }

        suspend fun installRootfsArchive(ctx: Context, uri: Uri, reporter: TaskReporter): File = withContext(IO) {
            val tmpArchiveFile = File(Consts.tmpDir, "archive-rootfs-tmp").also { it.delete() }
            val tmpOutDir = File(Consts.tmpDir, "extracted-rootfs").also {
                FileUtils.deleteDirectory(it)
                it.mkdirs()
            }
            val compSize = ctx.contentResolver.openFileDescriptor(uri, "r").use { it?.statSize } ?: (1024 * 1024 * 1024L)

            reporter.progress(0F)
            reporter.totalValue = compSize

            //先检测是不是gz或xz或zst. 然后复制文件到内部目录
            val compType = if (ctx.openInput(uri)?.use { it.isXz() } == true) CompressedType.XZ
            else if (ctx.openInput(uri)?.use { it.isGzip() } == true) CompressedType.GZ
            else if (ctx.openInput(uri)?.use { it.isZstd() } == true) CompressedType.TZST
            else throw RuntimeException("该文件不是 xz、gz 或 zst 压缩包。")

            reporter.msg(null, "(1/3) 正在解压到临时文件夹...")
            reporter.totalValue = compSize
            val compressedTarInput = Archive.getCompressedInput(compType, ctx.openInput(uri))
            Archive.decompressCompressedTarStream(compressedTarInput, tmpOutDir, reporter)

            reporter.msg(null, "(2/3) 正在移动到目标文件夹...")
            reporter.progress(0F)
            //寻找rootfs根目录
            val confirmRootfsSubDirs = listOf("etc", "usr")
            val searchDirs = mutableListOf(tmpOutDir)
            var foundRootfsDir: File? = null
            while (searchDirs.size > 0 && foundRootfsDir == null) {
                val nowDir = searchDirs.removeAt(0)
                foundRootfsDir = nowDir.takeIf { it.list()?.toList()?.containsAll(confirmRootfsSubDirs) == true }
                nowDir.listFiles()?.let { searchDirs.addAll(it) }
            }
            if (foundRootfsDir == null)
                throw RuntimeException("无法在解压内容中找到rootfs根目录（包含 etc usr 的文件夹）")

            //固定使用 rootfs-1, rootfs-2... 格式命名
            var num = 1
            rootfsAllDir.list()?.let { while (it.contains("rootfs-$num")) num++ }
            val targetOutDir = File(rootfsAllDir, "rootfs-$num")
            reporter.msg("移动rootfs: $foundRootfsDir -> $targetOutDir")

            FileUtils.moveDirectory(foundRootfsDir, targetOutDir)

            tmpArchiveFile.delete()
            FileUtils.deleteDirectory(tmpOutDir)

            //解压后做一些处理操作
            reporter.msg(null, "解压结束。正在做一些处理...")
            postExtractRootfs(targetOutDir)

            //检查是否存在别名文件，如果不存在则创建默认别名
            val aliasFile = File(targetOutDir, ALIAS_FILE_NAME)
            if (!aliasFile.exists()) {
                setAlias(targetOutDir, "rootfs-$num")
            }

            return@withContext targetOutDir
        }

        /**
         * 从assets目录自动提取rootfs压缩包
         * 支持的格式：rootfs.tar.xz, rootfs.tar.gz, rootfs.tar.zst
         * @param reporter 进度报告器
         * @return 提取后的rootfs目录，如果未找到则返回null
         */
        suspend fun installRootfsFromAssets(ctx: Context, reporter: TaskReporter): File? = withContext(IO) {
            // 查找assets中的rootfs压缩包
            val rootfsFileNames = listOf(
                "rootfs.tar.xz", "rootfs.tar.gz", "rootfs.tar.zst",
                "rootfs.tzst"
            )
            var foundFileName: String? = null
            
            for (fileName in rootfsFileNames) {
                try {
                    val inputStream = ctx.assets.open(fileName)
                    inputStream.close()
                    foundFileName = fileName
                    break
                } catch (e: Exception) {
                    // 文件不存在，继续尝试下一个
                }
            }
            
            if (foundFileName == null) {
                reporter.msg("未在assets中找到rootfs压缩包")
                return@withContext null
            }
            
            reporter.msg("找到assets中的rootfs: $foundFileName")
            
            val tmpOutDir = File(Consts.tmpDir, "extracted-rootfs").also {
                FileUtils.deleteDirectory(it)
                it.mkdirs()
            }
            
            // 根据文件名确定压缩类型
            val compType = when {
                foundFileName.endsWith(".xz") -> CompressedType.XZ
                foundFileName.endsWith(".gz") -> CompressedType.GZ
                foundFileName.endsWith(".zst") || foundFileName.endsWith(".tzst") -> CompressedType.TZST
                else -> throw RuntimeException("不支持的压缩格式: $foundFileName")
            }
            
            reporter.progress(0F)
            // 获取 assets 文件大小
            val compSize = ctx.assets.open(foundFileName).use { it.available().toLong() }
            reporter.totalValue = compSize
            
            reporter.msg(null, "(1/3) 正在解压到临时文件夹...")
            val compressedTarInput = Archive.getCompressedInput(compType, ctx.assets.open(foundFileName))
            Archive.decompressCompressedTarStream(compressedTarInput, tmpOutDir, reporter)
            
            reporter.msg(null, "(2/3) 正在移动到目标文件夹...")
            reporter.progress(0F)
            
            // 寻找rootfs根目录
            val confirmRootfsSubDirs = listOf("etc", "usr")
            val searchDirs = mutableListOf(tmpOutDir)
            var foundRootfsDir: File? = null
            while (searchDirs.size > 0 && foundRootfsDir == null) {
                val nowDir = searchDirs.removeAt(0)
                foundRootfsDir = nowDir.takeIf { it.list()?.toList()?.containsAll(confirmRootfsSubDirs) == true }
                nowDir.listFiles()?.let { searchDirs.addAll(it) }
            }
            if (foundRootfsDir == null)
                throw RuntimeException("无法在解压内容中找到rootfs根目录（包含 etc usr 的文件夹）")
            
            //固定使用 rootfs-1, rootfs-2... 格式命名
            var num = 1
            rootfsAllDir.list()?.let { while (it.contains("rootfs-$num")) num++ }
            val targetOutDir = File(rootfsAllDir, "rootfs-$num")
            reporter.msg("移动rootfs: $foundRootfsDir -> $targetOutDir")
            
            FileUtils.moveDirectory(foundRootfsDir, targetOutDir)
            
            FileUtils.deleteDirectory(tmpOutDir)
            
            // 解压后做一些处理操作
            reporter.msg(null, "解压结束。正在做一些处理...")
            postExtractRootfs(targetOutDir)

            //检查是否存在别名文件，如果不存在则创建默认别名
            val aliasFile = File(targetOutDir, ALIAS_FILE_NAME)
            if (!aliasFile.exists()) {
                setAlias(targetOutDir, "rootfs-$num")
            }

            return@withContext targetOutDir
        }

        /** 根据[path]创建TarArchiveEntry. 将path路径去掉rootfs父目录的路径([removeLen]个字符）作为名字。如果为文件夹在末尾加上 / */
        private fun getTarEntry(path: Path, removeLen: Int): TarArchiveEntry {
            var entryName = path.absolutePathString().substring(removeLen).trim('/')
            if (path.isDirectory(LinkOption.NOFOLLOW_LINKS)) entryName += "/"
            //符号链接时要自己构建，它读文件不会检查符号链接（文件夹倒会。。。它这库也不行啊还以为多好用呢）
            return if (path.isSymbolicLink())
                TarArchiveEntry(entryName, TarConstants.LF_SYMLINK).also { it.linkName = path.readSymbolicLink().pathString }
            else
                TarArchiveEntry(path, entryName, LinkOption.NOFOLLOW_LINKS)
        }

        /**
         * 将指定rootfs压缩为压缩包并导出。压缩包内第一层是该rootfs文件夹，再内部是各基本目录
         */
        suspend fun exportRootfsArchive(ctx: Context, uri: Uri, rootfsDir: File, compType: CompressedType, reporter: TaskReporter) = withContext(IO) {
            // 压缩包内文件去掉rootfs父目录的路径
            val parentPrefixLen = rootfsDir.parentFile!!.absolutePath.length
            // proot绑定的那些目录 内容不添加到压缩包
            val ignoreRootfsSubDirs = setOf("dev", "proc", "tmp", "storage", "sys")
            val rootfsContents = rootfsDir.toPath().listDirectoryEntries().filter { !ignoreRootfsSubDirs.contains(it.name) }
            var fileCount = 0

            reporter.msg("忽略以下文件夹：${ignoreRootfsSubDirs.joinToString(", ")}")
            reporter.msg(null, "正在读取文件夹内容...")
            reporter.totalValue = -1L
            rootfsContents.toMutableList().walk { fileCount++ }
            reporter.msg("共找到${fileCount}个文件")

            reporter.msg(null, "正在压缩全部文件...")
            reporter.totalValue = fileCount.toLong()
            TarArchiveOutputStream(Archive.getCompressedOutput(compType, ctx.openOutput(uri))).use { tOut ->
                // 长文件名
                tOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                //添加rootfs目录
                tOut.putArchiveEntry(getTarEntry(rootfsDir.toPath(), parentPrefixLen))
//                tOut.closeArchiveEntry()
                var tmpCount = 0L
                var tarEntry: TarArchiveEntry? = null
                rootfsContents.toMutableList().walk { filePath ->
                    try {
                        tmpCount++
                        reporter.progressValue(tmpCount)
                        tarEntry = getTarEntry(filePath, parentPrefixLen)
                        // 传入path构建的entry有bug 不会识别文件软链接。不能直接传入path,而是要手动传入文件名和linkFlag...

                        tarEntry!!.mode = filePath.getMode()//PosixFilePermissions.toString(attrs.permissions()).toInt(8)
                        tOut.putArchiveEntry(tarEntry)
                        //文件
                        if (filePath.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                            filePath.inputStream().use { it.copyTo(tOut) }
                        }
                        tOut.closeArchiveEntry()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        reporter.msg("压缩时出现错误。文件=${filePath.pathString} 。 错误=${e.stackTraceToString()}")
                    }
                }
                // 绑定文件夹变为空文件夹放进压缩包
                for (dir in ignoreRootfsSubDirs.filter { it != "storage" }) {
                    val path = Paths.get(rootfsDir.absolutePath, dir)
                    tOut.putArchiveEntry(getTarEntry(path, parentPrefixLen).also { if (dir == "tmp") it.mode = "777".toInt(8) })
                    tOut.closeArchiveEntry()
                }
                tOut.finish()
            }

            reporter.msg(null, "压缩包导出完成！")
        }

        /** 获取当前选择的rootfs。
         * 确保：1. 不为 [rootfsCurrDir] 2. 优先读取上次设置的，如果不存在则随机选一个
         */
        suspend fun getSelectedRootfs(): File? {
            val allAvailable = rootfsAllDir.list() ?: arrayOf()
            val selectedRootfs = curr_rootfs_name.get().takeUnless { it.isEmpty() || !allAvailable.contains(it) }
                ?: allAvailable.find { it != rootfsCurrDir.name }
            return if (selectedRootfs.isNullOrEmpty()) null
            else File(rootfsAllDir, selectedRootfs).takeIf { it.exists() }
        }

        /**
         * 解压rootfs后，需要对其做一些一次性处理
         * - 修改网络相关配置文件
         * - 修复 --link2symlink 产生的符号链接
         */
        suspend fun postExtractRootfs(rootfsDir: File) = withContext(Dispatchers.IO) {
            //来自proot-distro。修改网络配置文件
            File(rootfsDir, "/etc/resolv.conf").run {
                delete()
                writeText(
                    """
                    nameserver 8.8.8.8
                    nameserver 8.8.4.4
                    """.trimIndent().plus("\n")
                )
            }
            File(rootfsDir, "/etc/hosts").run {
                delete()
                writeText(
                    """
                    # IPv4.
                    127.0.0.1   localhost.localdomain localhost
            
                    # IPv6.
                    ::1         localhost.localdomain localhost ip6-localhost ip6-loopback
                    fe00::0     ip6-localnet
                    ff00::0     ip6-mcastprefix
                    ff02::1     ip6-allnodes
                    ff02::2     ip6-allrouters
                    ff02::3     ip6-allhosts
                    """.trimIndent().plus("\n")
                )
            }
            
            // 修复 --link2symlink 产生的符号链接
            fixL2sSymlinks(rootfsDir)
        }

        /**
         * 修复 proot --link2symlink 产生的符号链接问题
         * 当导入其他容器的导出包时，符号链接可能指向旧容器路径，需要修正为当前容器路径
         */
        private fun fixL2sSymlinks(rootfsDir: File) {
            val root = rootfsDir.toPath().toAbsolutePath()
            var fixedCount = 0
            
            try {
                java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        runCatching {
                            if (!java.nio.file.Files.isSymbolicLink(file)) return@runCatching

                            val target = java.nio.file.Files.readSymbolicLink(file)
                            if (!target.isAbsolute) return@runCatching

                            val targetStr = target.toString()
                            // 匹配 "rootfs/" 路径
                            val rootfsIndex = targetStr.lastIndexOf("rootfs/")
                            if (rootfsIndex == -1) return@runCatching

                            // 从 rootfs/ 后面开始截取
                            val afterRootfs = targetStr.substring(rootfsIndex + "rootfs/".length)
                            val firstSlash = afterRootfs.indexOf('/')
                            if (firstSlash == -1) return@runCatching

                            val internalPath = afterRootfs.substring(firstSlash)
                            val correctTarget = root.resolve(internalPath.substring(1))

                            if (target == correctTarget) return@runCatching

                            // 修复符号链接
                            java.nio.file.Files.delete(file)
                            java.nio.file.Files.createSymbolicLink(file, correctTarget)
                            fixedCount++
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException): java.nio.file.FileVisitResult {
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
                
                if (fixedCount > 0) {
                    Log.d("Rootfs", "fixL2sSymlinks: 修复了 $fixedCount 个符号链接")
                }
            } catch (e: Exception) {
                Log.w("Rootfs", "fixL2sSymlinks: 修复符号链接时出错: ${e.message}")
            }
        }
    }

    /** 代表一个符号链接. 用于解压文件时相关处理
     * @param symlink 符号链接的路径（安卓上的路径）
     * @param pointTo 该链接指向的路径（由symlink指定，不一定指向自己app内的路径，可能相对指向自己同目录，可能以rootfs为根目录的绝对路径，当然也可能是l2s文件指向termux内路径）
     */
    private data class SymLink(val symlink: String, val pointTo: String)

    /** 有关一条l2s链的信息。硬链接路径，中间路径和最终路径都是绝对路径，且为正确路径（自己包名开头的）， */
    private data class L2sInfo(
        var interCorrectPath: String,
        val finalCorrectPath: String,
        val interName: String = interCorrectPath.split('/').last(),
        val finalName: String = finalCorrectPath.split('/').last(),
        var hardPaths: MutableSet<String> = mutableSetOf(),
    )

    object Archive {


        /** 将一个普通输入流转换为对应的压缩器输入流 如 [XZCompressorInputStream] [GzipCompressorInputStream] */
        fun getCompressedInput(type: CompressedType, rawInput: InputStream?): CompressorInputStream = when (type) {
            CompressedType.XZ -> XZCompressorInputStream(rawInput)
            CompressedType.GZ -> GzipCompressorInputStream(rawInput)
            CompressedType.TZST -> ZstdCompressorInputStream(rawInput)
        }

        /** 将一个普通输出流转换为对应的压缩器输出流 如 [XZCompressorOutputStream] [GzipCompressorOutputStream] */
        fun getCompressedOutput(type: CompressedType, rawOutput: OutputStream?): CompressorOutputStream<out OutputStream> = when (type) {
            CompressedType.XZ -> XZCompressorOutputStream(rawOutput)
            CompressedType.GZ -> GzipCompressorOutputStream(rawOutput)
            CompressedType.TZST -> ZstdCompressorOutputStream(rawOutput)
        }

        /**
         * 解压一个压缩包输入流，该压缩包解压后应该是一个tar文件，然后将这个tar文件内容解压到指定目录
         * @param archiveInput 对应压缩文件的压缩器输入流，如 [XZCompressorInputStream] [GzipCompressorInputStream]
         * @param outDir 解压到的目录，解压后该文件夹下直接子文件夹应该为 usr bin etc 那些
         * @param reporter 调用[TaskReporter.progressValue] 时传入的是某文件压缩后大小. 请确保在调用此函数前将[TaskReporter.totalValue]设置为正确的值
         * @param entryNameMapper 一个映射函数，输入压缩包内文件名a，返回修改后的文件名b，最终该文件会解压到 File([outDir], b)
         */
        fun decompressCompressedTarStream(
            archiveInput: CompressorInputStream,
            outDir: File,
            reporter: TaskReporter = TaskReporter.Dummy,
            entryNameMapper: (String) -> String = { it },
        ) {
            if (!outDir.exists()) outDir.mkdirs()

            val symLinkList = mutableListOf<SymLink>()
            val dirModeList = mutableListOf<Pair<String, Int>>()  //压缩包内文件夹权限
            var extractCount = 0F //解压出的文件个数

            reporter.msg("正在解压文件...")
            archiveInput.use { zis ->
                val statistics = zis as? InputStreamStatistics
                TarArchiveInputStream(zis).use { tis ->
                    var entry: TarArchiveEntry
                    while (tis.nextEntry.also { entry = it } != null) {
                        extractCount++
                        statistics?.let { reporter.progressValue(statistics.compressedCount) } //更新解压进度
                        val name = entryNameMapper(entry.name)
                        if (name.isEmpty())
                            continue
                        val outFile = File(outDir, name)
                        //确保父目录存在
                        outFile.parentFile?.mkdirs()
                        try {
                            //如果是目录，创建目录
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                                dirModeList.add(outFile.absolutePath to entry.mode)
//                                Os.chmod(outFile.absolutePath, entry.mode) //解压途中修改可能导致丢失写权限，所以全部解压后再处理。
                            }
                            //如果是符号链接
                            else if (entry.isSymbolicLink) {
                                symLinkList.add(SymLink(outFile.absolutePath, entry.linkName))
//                                Os.symlink(entry.linkName, outFile.absolutePath) // 全部解压完再处理吧
                            }
                            //文件，解压
                            else {
                                FileOutputStream(outFile).use { os -> tis.copyTo(os) }
                                Os.chmod(outFile.absolutePath, entry.mode) //不知为何执行权限没同步过来？
                                // FileUtils.copyInputStreamToFile(tis, file); //不能用这个，会自动关闭输入流
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            reporter.msg("解压文件时出错：路径=${outFile.absolutePath.substring(outDir.absolutePath.length)} 。错误消息=${e.stackTraceToString()}")
                        }
                    }
                }
            }

            reporter.msg("正在创建符号链接...")
            reporter.totalValue = symLinkList.size.toLong()
            symLinkList.forEachIndexed { idx, item ->
                reporter.progressValue(idx.toLong())
                try {
                    Os.symlink(item.pointTo, item.symlink)
                } catch (e: Exception) {
                    e.printStackTrace()
                    reporter.msg("创建符号链接时出错。文件=$item 。错误消息=${e.stackTraceToString()}")
                }
            }
            //先完整循环一遍， 确保中间文件和最终文件都已创建。

            //FIXME 由于目前设置PROOT_L2S_DIR为.l2s文件夹时 locale-gen无法创建语言文件，而目前修复l2s又会将文件都放到.l2s文件夹，所以先不处理了，
            fixL2sFiles(outDir, symLinkList, reporter, skipProcess = true)

            // 文件夹权限应该在全部文件和符号链接处理完之后进行。
            reporter.msg("正在恢复文件夹权限...")
            reporter.totalValue = dirModeList.size.toLong()
            dirModeList.forEachIndexed { idx, item ->
                reporter.progressValue(idx.toLong())
                try {
                    Os.chmod(item.first, item.second)
                } catch (e: Exception) {
                    e.printStackTrace()
                    reporter.msg("恢复文件夹权限时出错")
                }
            }
        }

        /**
         * 修复l2s文件软链接指向的路径
         * @param skipProcess 跳过处理。目前指定l2s变量会有问题，所以先不处理了，但保留 .l2s 文件夹
         */
        private fun fixL2sFiles(outDir: File, symLinkList: List<SymLink>, reporter: TaskReporter, skipProcess: Boolean = false) {
            if (skipProcess) {
                reporter.msg("跳过修复l2s文件，保留 .l2s 文件夹供后续处理...")
                // 不再删除 .l2s 文件夹，让 postExtractRootfs 中的 fixL2sSymlinks 来处理符号链接
                return
            }

            reporter.msg("开始修复l2s文件...")

            /** l2s相关文件信息。key为中间文件错误路径（别的包名开头），value为该文件相关信息。 */
            val interToL2sMap: MutableMap<String, L2sInfo> = mutableMapOf()

            // /.l2s文件夹下的文件，优先从此处找。没有再从硬链接同目录找
            val l2sDir = File(outDir, "/.l2s")
            val l2sDirFiles = l2sDir.listFiles() ?: arrayOf()
            val regex4Dec = "^[0-9]{4}$".toRegex()
            for (idx in symLinkList.indices) {
                val item = symLinkList[idx]
                reporter.progressValue(idx.toLong())
                // 修复 proot l2s文件相关的符号链接指向路径。注意列表中的软链接可能为 硬链接 -> 中间文件，也可能为 中间文件 -> 最终文件
                // File.exists()判断软链接自身存在不可靠！用nio的 Files.exists 传入NOFOLLOW_LINKS 判断是准确的
                try {
                    val interPrefix = ".l2s."
                    //硬链接模拟的文件名 (如果该文件是中间文件就直接跳过）
                    val hardFile = File(item.symlink).takeIf { it.selfExists() && !it.name.startsWith(interPrefix) }
                        ?: continue
                    // 中间文件 错误指向的 那个不存在路径。检查：文件名格式 .l2s. + 任意文字 + .四位整数
                    val interWrongFile = File(item.pointTo).takeIf { it.name.startsWith(interPrefix) && it.name.takeLast(4).matches(regex4Dec) }
                        ?: continue
                    //中间文件名
                    val interName = interWrongFile.name
                    //中间文件目前存在路径:  .l2s或同目录下 + 文件名符合格式 + 文件存在
                    val interExistFile = (l2sDirFiles.find { it.name == interName } ?: File(hardFile.parent!!, interName).takeIf { it.selfExists() })
                        ?: throw RuntimeException("存在硬链接模拟文件，但找不到中间存在文件。")
                    // 最终文件 错误不存在的路径:  中间文件为软链接 + 指向的路径

                    val finalWrongFile = java.nio.file.Files.readSymbolicLink(interExistFile.toPath()).toFile()
                        ?: throw RuntimeException("存在硬链接模拟文件和中间文件，但中间存在文件不是软链接。")
                    val finalPrefix = "$interName."
                    // 最终文件名： 中间文件.硬链接个数
                    val finalName = finalWrongFile.name.takeIf { it.startsWith(finalPrefix) && it.substring(finalPrefix.length).matches(regex4Dec) }
                        ?: throw RuntimeException("存在硬链接模拟文件和中间文件，但最终文件名格式错误。")
                    //最终文件目前存在路径:  .l2s或同目录下 + 文件名符合格式 + 文件存在
                    val finalExistFile =
                        (l2sDirFiles.find { it.name == finalName } ?: File(interExistFile.parent!!, finalName).takeIf { it.selfExists() })
                            ?: throw RuntimeException("存在硬链接模拟文件和中间文件，但找不到最终存在文件。")

                    //最后一起处理。这里先存起来。如果半道直接处理，后面再读错误路径可能就读到正确路径上了。
                    val l2sInfo = interToL2sMap[interWrongFile.absolutePath]
                        ?: L2sInfo(interExistFile.absolutePath, finalExistFile.absolutePath, interName, finalName)
                            .also { interToL2sMap[interWrongFile.absolutePath] = it }
                    l2sInfo.hardPaths.add(hardFile.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                    reporter.msg("寻找l2s文件时出错。数据=$item 。错误消息=${e.stackTraceToString()}")
                }
            }

            reporter.msg("寻找l2s文件完成。开始修复l2s文件...")
            reporter.totalValue = interToL2sMap.size.toLong()

            /** 修复全部刚才找到的错误l2s相关软链接。中间路径 -> 最终路径，硬链接路径 -> 中间路径 */
            interToL2sMap.values.forEachIndexed { idx, info ->
                reporter.progressValue(idx.toLong())
                reporter.msg("修复l2s软链接 $info")
                try {
                    //强制放到.l2s文件夹。因为后续proot启动时会指定到这个文件夹
                    var finalInL2s = File(info.finalCorrectPath)
                    if (!finalInL2s.absolutePath.startsWith(l2sDir.absolutePath))
                        finalInL2s = File(l2sDir, finalInL2s.name).also { FileUtils.moveFile(finalInL2s, it) }
                    var interInL2s = File(info.interCorrectPath).also { it.delete() }
                    if (!interInL2s.absolutePath.startsWith(l2sDir.absolutePath))
                        interInL2s = File(l2sDir, interInL2s.name)
                    Os.symlink(finalInL2s.absolutePath, interInL2s.absolutePath)
                    info.hardPaths.forEach {
                        File(it).delete()
                        Os.symlink(interInL2s.absolutePath, it)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    reporter.msg("创建l2s文件正确软链接时出错。数据=$info 。错误消息=${e.stackTraceToString()}")
                }
            }

            reporter.msg("修复l2s文件完成")
            //TODO 要不再检查一遍软链接列表，看看还有没有指向termux的？因为目前检查l2s的时候只检查硬链接，如果是中间文件的话不会做处理。虽然正常情况下有中间文件的话就应该有硬链接？

        }

        /**
         * 解压一个.tar.xz压缩文件
         * @param archiveInput 对应压缩文件的输入流
         * @param outDir 解压到的目录，解压后该文件夹下直接子文件夹应该为 usr bin etc 那些
         * @param reporter 调用[TaskReporter.progressValue] 时传入的是某文件压缩后大小. 请确保在调用此函数前将[TaskReporter.totalValue]设置为正确的值
         * @param entryNameMapper 一个映射函数，输入压缩包内文件名a，返回修改后的文件名b，最终该文件会解压到 File([outDir], b)
         */
        @Throws(IOException::class)
        fun decompressTarXz(
            archiveInput: InputStream?,
            outDir: File,
            reporter: TaskReporter = TaskReporter.Dummy,
            entryNameMapper: (String) -> String = { it },
        ) {
            XZCompressorInputStream(archiveInput).use { decompressCompressedTarStream(it, outDir, reporter, entryNameMapper) }
        }
    }

    object Ui {

        /** 将一个悬浮窗靠向最近的一条边。嵌进去一半. */
        fun View.snapToNearestEdgeHalfway() {
            val parent = parent as? View ?: return
            val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return

            val snapDistanceLeft = left
            val snapDistanceRight = parent.width - right
            val snapDistanceTop = top
            val snapDistanceBottom = parent.height - bottom

            val minDistance = minOf(snapDistanceLeft, snapDistanceRight, snapDistanceTop, snapDistanceBottom)

            val currentLeft = left
            val currentTop = top
            var targetLeft = currentLeft
            var targetTop = currentTop

            when (minDistance) {
                snapDistanceLeft -> targetLeft = -width / 2
                snapDistanceRight -> targetLeft = parent.width - width / 2
                snapDistanceTop -> targetTop = -height / 2
                snapDistanceBottom -> targetTop = parent.height - height / 2
            }

            ValueAnimator.ofInt(currentLeft, targetLeft).apply {
                duration = 300
                addUpdateListener { animation ->
                    lp.leftMargin = animation.animatedValue as Int
                    requestLayout()
                }
            }.start()

            ValueAnimator.ofInt(currentTop, targetTop).apply {
                duration = 300
                addUpdateListener { animation ->
                    lp.topMargin = animation.animatedValue as Int
                    requestLayout()
                }
            }.start()
        }

        /**
         * 用于viewmodel中将 从datastore获取到的flow 转为stateflow
         */
        fun <T> ViewModel.stateInSimple(initValue: T, flow: Flow<T>): StateFlow<T> {
            return flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initValue)
        }

        /**
         * 用于viewmodel中修改datastore的数据
         */
        suspend fun <T> ViewModel.editDateStore(key: Preferences.Key<T>, value: T) = withContext(Dispatchers.IO) {
            dataStore.edit { it[key] = value }
        }

        /** 同[ViewModel.editDateStore]，但会在新的协程中异步执行， */
        fun <T> ViewModel.editDateStoreAsync(key: Preferences.Key<T>, value: T) {
            viewModelScope.launch(Dispatchers.IO) { dataStore.edit { it[key] = value } }
        }
    }

    object Permissions {
        private var requestLauncher: ActivityResultLauncher<String>? = null
        private var notificationRequestLauncher: ActivityResultLauncher<String>? = null

        /** 在授予结果返回时 执行一次 */
        private var requestLauncherCallback: ((Boolean) -> Unit)? = null

        /**
         * 在activity的init中调用，注册其他activity的回调。该函数还会添加一个生命周期回调，在onDestroy时取消注册
         */
        fun registerForActivityResult(a: MainEmuActivity) {
            val onActivityResult: (Boolean) -> Unit = { isGranted ->
                requestLauncherCallback?.invoke(isGranted)
                requestLauncherCallback = null
            }
            requestLauncher = a.registerForActivityResult(ActivityResultContracts.RequestPermission(), onActivityResult)
            notificationRequestLauncher = a.registerForActivityResult(object : ActivityResultContract<String, Boolean>() {
                override fun createIntent(context: Context, input: String) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .apply { putExtra(Settings.EXTRA_APP_PACKAGE, MainEmuApplication.i.packageName) }

                override fun parseResult(resultCode: Int, intent: Intent?) =
                    NotificationManagerCompat.from(MainEmuApplication.i).areNotificationsEnabled()

                override fun getSynchronousResult(context: Context, input: String): SynchronousResult<Boolean>? {
                    val granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
                    return if (granted) SynchronousResult(true) else null
                }

            }, callback = onActivityResult)

            // 手动在onDestroy时调用unregister
            a.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    requestLauncher?.unregister()
                    requestLauncher = null
                    notificationRequestLauncher?.unregister()
                    notificationRequestLauncher = null
                }
            })
        }

        /** 检查一个权限是否已经获取 */
        fun isGranted(ctx: Context, permission: String): Boolean {
            if (permission == android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            }
            return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * 申请一个权限。注意请勿在上一个申请结果返回时再次调用，否则onResult会被覆盖为本次的函数
         * @param onResult [requestLauncher] 申请权限后的回调，参数为isGranted
         */
        fun request(permission: String, onResult: (Boolean) -> Unit) {
            requestLauncherCallback = onResult
            if (permission == android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                notificationRequestLauncher?.launch(permission)
            } else {
                requestLauncher?.launch(permission)
            }
        }
    }

    object Pref {
        private const val TAG = "Utils.Pref"

        /**
         * 接收一个存储用户偏好的map,将其序列化为json
         */
        fun serializeFromMapToJson(map: Map<String, Any>): String {
            return kotlin.runCatching {
                val mapSerializer = MapSerializer(String.serializer(), PrefValueSerializer)
                return@runCatching Json.encodeToString(mapSerializer, map)
            }.onFailure { Log.e(TAG, "map转json失败", it) }.getOrNull() ?: ""

        }

        /**
         * 接收一个json字符串，将其转为map返回。map的key是datastore中对应的Key, value是对应的值
         */
        fun deserializeFromJsonToMap(json: String): Map<String, Any> {
            val _json = json.trim()
            if (_json.isEmpty()) return mapOf()
            return kotlin.runCatching {
                val mapSerializer = MapSerializer(String.serializer(), PrefValueSerializer)
                return@runCatching Json.decodeFromString<Map<String, Any>>(mapSerializer, _json)
            }.onFailure { Log.e(TAG, "获取assets/preferences.json失败\njson:$_json", it) }.getOrNull() ?: mapOf()
        }

        /**
         * 用于序列化/反序列化 偏好数据 -> json。虽说是Any 但是只处理datastore可以存的那几个类型
         */
        private object PrefValueSerializer : KSerializer<Any> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any")

            private val listSerializer = ListSerializer(String.serializer())

            override fun serialize(encoder: Encoder, value: Any) {
                when (value) {
                    is Boolean -> encoder.encodeBoolean(value)
                    is String -> encoder.encodeString(value)
                    is Int -> encoder.encodeInt(value)
                    is Float -> encoder.encodeFloat(value)
                    is Long -> encoder.encodeLong(value)
                    is Double -> encoder.encodeDouble(value)
                    is Set<*> -> {
                        encoder.encodeSerializableValue(listSerializer, value.map { it as String })
//                        if (value.first()?.takeIf { it is String } != null) encoder.encodeSerializableValue(setSerializer, value as Set<String>)
                    }

                    else -> throw IllegalArgumentException("序列化时，Any无法转为常见类型: ${value::class}")
                }
            }

            override fun deserialize(decoder: Decoder): Any {
                return when (val el = (decoder as JsonDecoder).decodeJsonElement()) {
                    is JsonPrimitive -> {
                        when {
                            el.isString -> el.content
                            el.booleanOrNull is Boolean -> el.content.toBoolean()
                            el.intOrNull is Int -> el.content.toInt()
                            el.floatOrNull is Float -> el.content.toFloat()
                            el.longOrNull is Long -> el.content.toLong()
                            el.doubleOrNull is Double -> el.content.toDouble()
                            else -> el.content
                        }
                    }
                    //这个数组每个元素是JsonLiteral(JsonPrimitive) 不是直接String
                    is JsonArray -> {
                        el.mapNotNull { item -> (item as JsonPrimitive).takeIf { it.isString }!!.content }.toSet()
                    }

                    else -> throw IllegalArgumentException("反序列化时，Any无法转为常见类型: $el")
                }
            }
        }
    }

}


class RateLimiter(val delayMs: Long = 1000L) {
    private val lastBlock = AtomicReference<(suspend () -> Unit)?>(null)
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * 延迟一段时间后执行一段代码。
     * 如果这段时间只内有新代码块，则之前的代码块不会被执行，且重新开始倒计时。
     * 请在同一线程内调用
     */
    fun runDelay(block: suspend () -> Unit) {
        lastBlock.set(block)
        scope.launch {
            delay(delayMs)
            if (lastBlock.get() == block) //最后一次设置之后，过了一秒没改过
                block()
        }
    }
}

enum class CompressedType {
    XZ, GZ, TZST,
}

enum class FuncOnChangeAction {
    EDIT,
    ADD,
    DEL,
}
/** 增删改的回调 [FuncOnChange]的同步函数版本 */
typealias FuncOnChangeSync<T> = (oldValue: T, newValue: T, action: FuncOnChangeAction) -> Unit
/** 增删改的回调 异步函数。当为ADD或DEL时old=new */
typealias FuncOnChange<T> = suspend (oldValue: T, newValue: T, action: FuncOnChangeAction) -> Unit

