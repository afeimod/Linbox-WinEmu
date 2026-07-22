package org.github.ewt45.winemulator.emu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.CompressedType
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.Utils.chmod
import org.github.ewt45.winemulator.ui.components.TaskReporter
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * 原生 glibc + box64 + Wine 的独立 winecfg 测试。
 *
 * 不经过 PRoot：
 * 1. 确保 rootfs 已解压并设为 current
 * 2. 从 assets 解压 box64 / wine / wine 前缀
 * 3. 启动 X11 后再执行:
 *    ld-linux -> box64 -> wine explorer /desktop=shell,WxH winecfg
 * 4. DISPLAY=:13 + TMPDIR=应用 cache/tmp，与 X11Service 一致
 */
object NativeWineCfgTest {
    private const val TAG = "NativeWineCfgTest"
    private const val NATIVE_USER = "xuser"
    private const val DISPLAY = ":13"
    private const val BOX64_ASSET = "native-box64.tar.xz"
    private const val WINE_ASSET = "native-wine.tar.xz"
    private const val PREFIX_ASSET = "native-wine-prefix.tzst"
    private const val WINE_INSTALL_DIR = "opt/wine"

    @Volatile
    var lastCmd: String = ""
        private set

    @Volatile
    var lastLogSnippet: String = ""
        private set

    @Volatile
    private var process: Process? = null

    private val recentLogs = ConcurrentLinkedQueue<String>()

    data class RuntimeFiles(
        val rootfs: File,
        val linker: File,
        val box64: File,
        val wine: File,
        val home: File,
        val winePrefix: File,
        val aarch64LibPath: String,
        val box64LibPath: String,
    )

    suspend fun prepare(ctx: Context, onStatus: (String) -> Unit = {}): RuntimeFiles =
        withContext(Dispatchers.IO) {
            stop()
            recentLogs.clear()
            lastLogSnippet = ""

            onStatus("准备 rootfs…")
            val rootfs = ensureRootfs(ctx)
            Utils.Rootfs.makeCurrent(rootfs)

            // box64/glibc 编译时写死 INTERP 为 files/imagefs/usr/lib/ld-linux-...
            // Wine 二次 exec 会直接 exec box64，必须让该路径可用。
            onStatus("对齐 imagefs 路径…")
            ensureImageFsLink(ctx, rootfs)

            onStatus("安装 box64…")
            installBox64FromAssets(ctx, rootfs, onStatus)

            onStatus("安装 Wine…")
            installWineFromAssets(ctx, rootfs, onStatus)

            onStatus("准备 Wine 前缀…")
            ensureWinePrefix(ctx, rootfs, onStatus)

            resolveRuntime(rootfs)
        }

    /**
     * 原生 rootfs/box64 的动态链接器路径写死为:
     * /data/data/<pkg>/files/imagefs/usr/lib/ld-linux-aarch64.so.1
     * Linbox 实际 rootfs 在 files/rootfs/current。
     * 首次启动可用 ld-linux --library-path 绕过；
     * 但 wine 二次 exec 会直接 exec box64，内核按 INTERP 找 imagefs，必须存在。
     */
    private fun ensureImageFsLink(ctx: Context, rootfs: File) {
        val imageFs = File(ctx.filesDir, "imagefs")
        val target = rootfs.canonicalFile
        try {
            if (java.nio.file.Files.isSymbolicLink(imageFs.toPath()) || imageFs.exists()) {
                val current = runCatching { imageFs.canonicalFile }.getOrNull()
                if (current != null && current.absolutePath == target.absolutePath) {
                    Log.d(TAG, "imagefs 已指向 ${target.absolutePath}")
                    return
                }
                // 旧链接/空目录可替换；非空真实目录不硬删，避免误伤
                if (java.nio.file.Files.isSymbolicLink(imageFs.toPath()) ||
                    (imageFs.isDirectory && imageFs.list().isNullOrEmpty()) ||
                    imageFs.isFile
                ) {
                    imageFs.delete()
                } else {
                    Log.w(TAG, "imagefs 已存在且非空，无法替换为 rootfs 链接: ${imageFs.absolutePath}")
                    // 至少补 linker 路径
                    val linkerDst = File(imageFs, "usr/lib/ld-linux-aarch64.so.1")
                    val linkerSrc = listOf(
                        File(rootfs, "usr/lib/ld-linux-aarch64.so.1"),
                        File(rootfs, "lib/ld-linux-aarch64.so.1"),
                    ).firstOrNull { it.isFile }
                    if (linkerSrc != null && !linkerDst.exists()) {
                        linkerDst.parentFile?.mkdirs()
                        runCatching {
                            java.nio.file.Files.createSymbolicLink(linkerDst.toPath(), linkerSrc.toPath())
                        }.onFailure {
                            org.apache.commons.io.FileUtils.copyFile(linkerSrc, linkerDst)
                        }
                    }
                    return
                }
            }
            java.nio.file.Files.createSymbolicLink(imageFs.toPath(), target.toPath())
            Log.d(TAG, "已创建 imagefs -> ${target.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "创建 imagefs 链接失败，尝试复制关键 linker", e)
            // 兜底：至少保证 INTERP 文件存在
            val linkerSrc = listOf(
                File(rootfs, "usr/lib/ld-linux-aarch64.so.1"),
                File(rootfs, "lib/ld-linux-aarch64.so.1"),
            ).firstOrNull { it.isFile }
                ?: throw RuntimeException("无法准备 imagefs 链接，且找不到 ld-linux", e)
            val linkerDst = File(ctx.filesDir, "imagefs/usr/lib/ld-linux-aarch64.so.1")
            linkerDst.parentFile?.mkdirs()
            org.apache.commons.io.FileUtils.copyFile(linkerSrc, linkerDst)
            linkerDst.setExecutable(true)
        }
    }

    suspend fun start(runtime: RuntimeFiles, onStatus: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        stop()
        recentLogs.clear()
        lastLogSnippet = ""
        onStatus("启动 winecfg…")
        startWineCfg(runtime)
        // 给 Wine/box64 一点时间启动；若秒退则直接抛出日志
        delay(2500)
        val p = process
        if (p == null || !p.isAlive) {
            val code = runCatching { p?.exitValue() }.getOrNull()
            val logs = drainLogs()
            lastLogSnippet = logs
            throw RuntimeException(
                "winecfg 进程已退出(code=$code)。\n" +
                    "命令:\n$lastCmd\n\n日志:\n${logs.ifBlank { "(无输出)" }}"
            )
        }
        onStatus("winecfg 进程运行中")
    }

    fun stop() {
        val p = process
        process = null
        if (p != null) {
            runCatching { p.destroy() }
            runCatching {
                if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
    }

    private suspend fun ensureRootfs(ctx: Context): File {
        var rootfs = Utils.Rootfs.getSelectedRootfs()
        if (rootfs == null || !rootfs.exists()) {
            rootfs = Utils.Rootfs.installRootfsFromAssets(ctx, TaskReporter.Dummy)
                ?: throw RuntimeException("assets 中未找到 rootfs.tzst，无法安装原生 glibc rootfs")
        }
        if (!File(rootfs, "usr/share/X11/xkb").isDirectory) {
            throw RuntimeException("rootfs 缺少 usr/share/X11/xkb，X11 无法启动: ${File(rootfs, "usr/share/X11/xkb").absolutePath}")
        }
        return rootfs
    }

    private fun installBox64FromAssets(ctx: Context, rootfs: File, onStatus: (String) -> Unit) {
        val existing = findBox64(rootfs)
        if (existing != null) {
            existing.setExecutable(true)
            onStatus("box64 已存在: ${existing.absolutePath}")
            return
        }
        ctx.assets.open(BOX64_ASSET).use { input ->
            Utils.Archive.decompressTarXz(input, rootfs) { name ->
                name.removePrefix("./").trimStart('/')
            }
        }
        val box64 = findBox64(rootfs)
            ?: throw RuntimeException("解压 $BOX64_ASSET 后未找到 usr/local/bin/box64")
        box64.setExecutable(true)
        onStatus("box64 已安装: ${box64.absolutePath}")
    }

    private fun installWineFromAssets(ctx: Context, rootfs: File, onStatus: (String) -> Unit) {
        val existing = findWine(rootfs)
        if (existing != null) {
            existing.setExecutable(true)
            onStatus("Wine 已存在: ${existing.absolutePath}")
            return
        }

        val tmp = File(Consts.tmpDir, "native-wine-extract").also {
            if (it.exists()) org.apache.commons.io.FileUtils.deleteDirectory(it)
            it.mkdirs()
        }
        try {
            ctx.assets.open(WINE_ASSET).use { input ->
                Utils.Archive.decompressTarXz(input, tmp) { name ->
                    name.removePrefix("./").trimStart('/')
                }
            }
            val wineRoot = findWineRootInDir(tmp)
                ?: throw RuntimeException("解压 $WINE_ASSET 后未找到 bin/wine")
            val target = File(rootfs, WINE_INSTALL_DIR)
            if (target.exists()) org.apache.commons.io.FileUtils.deleteDirectory(target)
            target.parentFile?.mkdirs()
            org.apache.commons.io.FileUtils.moveDirectory(wineRoot, target)
            File(target, "bin/wine").setExecutable(true)
            File(target, "bin/wineserver").takeIf { it.exists() }?.setExecutable(true)
            onStatus("Wine 已安装: ${target.absolutePath}")
        } finally {
            runCatching { org.apache.commons.io.FileUtils.deleteDirectory(tmp) }
        }
    }

    private fun ensureWinePrefix(ctx: Context, rootfs: File, onStatus: (String) -> Unit) {
        val home = File(rootfs, "home/$NATIVE_USER").also { it.mkdirs() }
        val winePrefix = File(home, ".wine")
        val marker = File(winePrefix, "system.reg")
        if (marker.isFile) {
            onStatus("Wine 前缀已存在: ${winePrefix.absolutePath}")
            return
        }

        // 空目录会让 Wine 误判前缀存在，必须清掉后装入可用前缀
        if (winePrefix.exists()) {
            org.apache.commons.io.FileUtils.deleteDirectory(winePrefix)
        }
        winePrefix.mkdirs()

        // 优先用 Winlator 的 x86_64 前缀模板；资产不存在时再尝试 wineboot
        val hasPrefixAsset = runCatching {
            ctx.assets.open(PREFIX_ASSET).use { }
            true
        }.getOrDefault(false)

        if (hasPrefixAsset) {
            val tmp = File(Consts.tmpDir, "native-prefix-extract").also {
                if (it.exists()) org.apache.commons.io.FileUtils.deleteDirectory(it)
                it.mkdirs()
            }
            try {
                val comp = Utils.Archive.getCompressedInput(
                    CompressedType.TZST,
                    ctx.assets.open(PREFIX_ASSET)
                )
                Utils.Archive.decompressCompressedTarStream(comp, tmp) { name ->
                    name.removePrefix("./").trimStart('/')
                }
                // 模板根可能是 .wine/ 或直接 drive_c/
                val source = when {
                    File(tmp, ".wine").isDirectory -> File(tmp, ".wine")
                    File(tmp, "drive_c").isDirectory -> tmp
                    File(tmp, "system.reg").isFile -> tmp
                    else -> findDirContaining(tmp, "system.reg")
                        ?: throw RuntimeException("前缀模板中未找到 system.reg")
                }
                org.apache.commons.io.FileUtils.copyDirectory(source, winePrefix)
                File(winePrefix, ".update-timestamp").writeText("disable\n")
                onStatus("Wine 前缀已从模板安装: ${winePrefix.absolutePath}")
            } finally {
                runCatching { org.apache.commons.io.FileUtils.deleteDirectory(tmp) }
            }
        } else {
            File(winePrefix, ".update-timestamp").writeText("disable\n")
            onStatus("未找到前缀模板，将由 winecfg/wineboot 首次创建前缀")
        }

        // dosdevices/c: 等符号链接若缺失，Wine 通常可补；这里至少保证目录存在
        File(winePrefix, "dosdevices").mkdirs()
        File(winePrefix, "drive_c").mkdirs()
    }

    private fun findDirContaining(root: File, fileName: String): File? {
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (File(cur, fileName).isFile) return cur
            cur.listFiles()?.filter { it.isDirectory }?.forEach { queue.add(it) }
        }
        return null
    }

    private fun findBox64(rootfs: File): File? = listOf(
        File(rootfs, "usr/local/bin/box64"),
        File(rootfs, "usr/bin/box64"),
        File(rootfs, "bin/box64"),
    ).firstOrNull { it.isFile }

    private fun findWine(rootfs: File): File? {
        val candidates = mutableListOf(
            File(rootfs, "$WINE_INSTALL_DIR/bin/wine"),
            File(rootfs, "opt/wine/bin/wine"),
            File(rootfs, "usr/local/bin/wine"),
            File(rootfs, "usr/bin/wine"),
        )
        rootfs.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith("wine")) {
                candidates += File(child, "bin/wine")
            }
        }
        File(rootfs, "opt").listFiles()?.forEach { child ->
            if (child.isDirectory) candidates += File(child, "bin/wine")
        }
        return candidates.firstOrNull { it.isFile }
    }

    private fun findWineRootInDir(dir: File): File? {
        val queue = ArrayDeque<File>()
        queue.add(dir)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (File(cur, "bin/wine").isFile) return cur
            cur.listFiles()?.filter { it.isDirectory }?.forEach { queue.add(it) }
        }
        return null
    }

    private fun resolveRuntime(rootfs: File): RuntimeFiles {
        val linker = listOf(
            File(rootfs, "usr/lib/ld-linux-aarch64.so.1"),
            File(rootfs, "lib/ld-linux-aarch64.so.1"),
        ).firstOrNull { it.isFile }
            ?: throw RuntimeException("rootfs 中未找到 ld-linux-aarch64.so.1")

        val box64 = findBox64(rootfs)
            ?: throw RuntimeException("rootfs 中未找到 box64")
        val wine = findWine(rootfs)
            ?: throw RuntimeException("rootfs 中未找到 wine")
        val wineDir = wine.parentFile?.parentFile
            ?: throw RuntimeException("无法解析 Wine 安装目录")

        val home = File(rootfs, "home/$NATIVE_USER").also { it.mkdirs() }
        val winePrefix = File(home, ".wine")
        Consts.tmpDir.mkdirs()
        chmod(Consts.tmpDir, "777")
        File(Consts.tmpDir, ".X11-unix").mkdirs()
        File(rootfs, "tmp").mkdirs()

        // aarch64 库：加载 box64 本体
        val aarch64LibPath = listOf(
            File(rootfs, "usr/lib"),
            File(rootfs, "lib"),
        ).filter { it.isDirectory }.map { it.absolutePath }.joinToString(":")

        // x86_64 库：box64 执行 wine 时搜索
        val wineLib = File(wineDir, "lib")
        val wineLib64 = File(wineDir, "lib64")
        val wineUnix = File(wineLib, "wine/x86_64-unix")
        val wineLibParts = listOf(wineUnix, wineLib64, wineLib)
            .filter { it.isDirectory }
            .map { it.absolutePath }
        val box64GuestLibs = listOf(
            File(rootfs, "usr/lib/box64-x86_64-linux-gnu"),
            File(rootfs, "usr/lib/x86_64-linux-gnu"),
            File(rootfs, "lib/x86_64-linux-gnu"),
        ).filter { it.isDirectory }.map { it.absolutePath }

        val box64LibPath = (box64GuestLibs + wineLibParts).joinToString(":")

        return RuntimeFiles(
            rootfs = rootfs,
            linker = linker,
            box64 = box64,
            wine = wine,
            home = home,
            winePrefix = winePrefix,
            aarch64LibPath = aarch64LibPath,
            box64LibPath = box64LibPath,
        )
    }

    private suspend fun startWineCfg(runtime: RuntimeFiles) {
        val rootfs = runtime.rootfs
        val lang = Consts.Pref.general_rootfs_lang.get()
        val resolution = Consts.Pref.general_resolution.get().ifBlank { "1280x720" }

        val pathDirs = listOf(
            runtime.wine.parentFile!!.absolutePath,
            File(rootfs, "usr/local/bin").absolutePath,
            File(rootfs, "usr/bin").absolutePath,
            File(rootfs, "bin").absolutePath,
        ).joinToString(":")

        val box64Bash = listOf(
            File(rootfs, "usr/local/bin/box64-bash"),
            File(rootfs, "usr/bin/box64-bash"),
        ).firstOrNull { it.isFile }

        // 注意：绝不能把 glibc 的 usr/lib 写进进程环境的 LD_LIBRARY_PATH。
        // 那里的 libc.so 是 GNU ld 链接脚本（/* GNU ld script */），
        // wine 二次 exec / 调用 Android sh 时会报 bad ELF magic，并导致
        // "wine: could not exec the wine loader"。
        // aarch64 库只通过 ld-linux 的 --library-path 传给 box64。
        val env = EnvMap()
        env.put("HOME", runtime.home.absolutePath, true)
        env.put("USER", NATIVE_USER, true)
        // 必须与 X11Service 的 TMPDIR 一致，客户端才能找到 .X11-unix/X13
        env.put("TMPDIR", Consts.tmpDir.absolutePath, true)
        env.put("DISPLAY", DISPLAY, true)
        env.put("LANG", lang, true)
        env.put("PATH", pathDirs, true)
        // 仅给 box64 转译 x86_64 wine 用；不要塞 aarch64 glibc 目录
        env.put("BOX64_LD_LIBRARY_PATH", runtime.box64LibPath, true)
        env.put("WINEPREFIX", runtime.winePrefix.absolutePath, true)
        env.put("WINELOADER", runtime.wine.absolutePath, true)
        // Android 上 preloader/noexec 映射常失败；跳过 wine-preloader
        env.put("WINELOADERNOEXEC", "1", true)
        env.put("WINEDEBUG", "+err,+fix", true)
        env.put("BOX64_NOBANNER", "0", true)
        env.put("BOX64_DYNAREC", "1", true)
        env.put("BOX64_MMAP32", "1", true)
        env.put("BOX64_X11GLX", "1", true)
        env.put("BOX64_LOG", "1", true)
        env.put("BOX64_EXIT", "0", true)
        // 让 wine 二次 exec 时 box64 能再次被内核加载（依赖 imagefs 链接）
        env.put("BOX64", runtime.box64.absolutePath, true)
        env.put("WINE_HOST_XDG_CURRENT_DESKTOP", "1", true)
        env.put("PULSE_SERVER", "tcp:127.0.0.1:4713", true)
        if (box64Bash != null) {
            env.put("BOX64_BASH", box64Bash.absolutePath, true)
        }

        val fontConfig = listOf(
            File(rootfs, "usr/etc/fonts"),
            File(rootfs, "etc/fonts"),
        ).firstOrNull { it.isDirectory }
        if (fontConfig != null) {
            env.put("FONTCONFIG_PATH", fontConfig.absolutePath, true)
        }

        val wineDllPath = listOf(
            File(runtime.wine.parentFile!!.parentFile, "lib/wine"),
            File(runtime.wine.parentFile!!.parentFile, "lib64/wine"),
        ).firstOrNull { it.isDirectory }
        if (wineDllPath != null) {
            env.put("WINEDLLPATH", wineDllPath.absolutePath, true)
        }

        // Winlator 同款：explorer /desktop 保证 GUI 落到虚拟桌面
        val cmd = listOf(
            runtime.linker.absolutePath,
            "--library-path", runtime.aarch64LibPath,
            runtime.box64.absolutePath,
            runtime.wine.absolutePath,
            "explorer",
            "/desktop=shell,$resolution",
            "winecfg",
        )
        lastCmd = cmd.joinToString(" ")
        Log.d(TAG, "启动命令=$lastCmd")
        Log.d(TAG, "DISPLAY=$DISPLAY TMPDIR=${Consts.tmpDir.absolutePath}")
        Log.d(TAG, "WINEPREFIX=${runtime.winePrefix.absolutePath}")
        Log.d(TAG, "WINELOADER=${runtime.wine.absolutePath}")
        Log.d(TAG, "不设置 LD_LIBRARY_PATH；aarch64 仅 --library-path=${runtime.aarch64LibPath}")
        Log.d(TAG, "BOX64_LD_LIBRARY_PATH=${runtime.box64LibPath}")

        val pb = ProcessBuilder(cmd)
            .directory(rootfs)
            .redirectErrorStream(true)
        // 先清掉 Android 继承的库路径/preload，避免污染 wine 二次 exec 的 sh/loader
        pb.environment().remove("LD_LIBRARY_PATH")
        pb.environment().remove("LD_PRELOAD")
        pb.environment().remove("GLIBC_LD_LIBRARY_PATH")
        pb.environment().remove("GLIBC_LD_PRELOAD")
        env.map.forEach { (k, v) -> pb.environment()[k] = v }

        val started = pb.start()
        process = started
        Thread({
            try {
                BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line ?: continue
                        Log.d(TAG, "winecfg: $text")
                        recentLogs.add(text)
                        while (recentLogs.size > 200) recentLogs.poll()
                    }
                }
                val code = started.waitFor()
                Log.d(TAG, "winecfg 退出码=$code")
                recentLogs.add("进程退出码=$code")
            } catch (e: Exception) {
                Log.e(TAG, "读取 winecfg 输出失败", e)
                recentLogs.add("读取输出失败: ${e.message}")
            } finally {
                if (process === started) process = null
                lastLogSnippet = drainLogs()
            }
        }, "NativeWineCfg-log").apply { isDaemon = true }.start()
    }

    private fun drainLogs(): String = recentLogs.toList().takeLast(80).joinToString("\n")
}
