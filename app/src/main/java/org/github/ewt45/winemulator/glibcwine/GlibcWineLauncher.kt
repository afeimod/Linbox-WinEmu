package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.emu.ProotHelper
import org.github.ewt45.winemulator.emu.ProotRootfs
import java.io.File

/**
 * glibc wine 启动器 - 整合的核心组件。
 *
 * 移植自 winlator-glibc 的 GlibcProgramLauncherComponent, 适配为在 proot 容器内运行。
 *
 * ## 架构说明
 *
 * 在 winlator-glibc 中, wine 通过 `ProcessHelper.exec("box64 wine ...", envVars, rootDir)` 直接运行,
 * 工作目录是 imagefs rootDir, X11 通过内置 XServer 或 TX11 (DISPLAY=:0)。
 *
 * 在 linbox 整合中, wine 通过 proot 容器运行:
 * 1. imagefs (glibc wine 运行时) 通过 --bind 挂载到 proot 容器内的 /opt/glibc-wine
 * 2. wine 在 proot 容器内启动, 共享 proot 的 X11 (DISPLAY=:13, Termux-X11)
 * 3. 音频共享 proot 的 PulseAudio (PULSE_SERVER=tcp:127.0.0.1:4713)
 * 4. SysV SHM 由 proot 的 --sysvipc 标志处理, 无需 libandroid-sysvshm.so
 * 5. box64 动态翻译 x86_64 wine 二进制, 使用 imagefs 内的 glibc 库
 *
 * ## 启动流程
 *
 * 1. 激活 wine 容器 (创建 xuser -> xuser-<id> 符号链接)
 * 2. 创建 dosdevices 盘符映射
 * 3. 构建 proot 命令 (含 imagefs 绑定挂载)
 * 4. 设置 wine 环境变量 (路径适配 /opt/glibc-wine 挂载点)
 * 5. 执行 box64 + wine 启动命令
 *
 * ## 两种模式
 *
 * - [launchStandalone]: 创建独立的 proot 进程运行 wine (类似 ContainerExec)
 * - [generateLaunchScript]: 生成启动脚本, 供已运行的 proot 容器调用
 */
class GlibcWineLauncher(private val context: Context) {
    private val TAG = "GlibcWineLauncher"

    val imageFs = GlibcImageFs.find(context)
    val containerManager = WineContainerManager(context)

    /**
     * 启动结果。
     */
    data class LaunchResult(
        val success: Boolean,
        val exitCode: Int = -1,
        val output: String = "",
        val error: String? = null,
    )

    /**
     * 构建 wine 环境变量集合。
     *
     * 所有路径都适配 proot 容器内的 /opt/glibc-wine 挂载点。
     *
     * @param container wine 容器
     * @param wineInfo wine 版本信息
     * @return 环境变量集合
     */
    fun buildWineEnvVars(container: WineContainer, wineInfo: WineInfo): GlibcEnvVars {
        val envVars = GlibcEnvVars()
        val root = GlibcWineConsts.CONTAINER_IMAGEFS_ROOT // /opt/glibc-wine

        val isArm64EC = wineInfo.arch.equals("arm64ec", ignoreCase = true)

        // ====== 基础路径变量 ======
        envVars.put("HOME", "${root}${GlibcWineConsts.HOME_PATH_REL}")
        envVars.put("USER", GlibcWineConsts.USER)
        envVars.put("TMPDIR", "/tmp") // 使用 proot 的 /tmp
        envVars.put("DISPLAY", GlibcWineConsts.DISPLAY) // :13, 共享 Termux-X11
        envVars.put("PULSE_SERVER", GlibcWineConsts.PULSE_SERVER) // 共享 PulseAudio
        envVars.put("WINEPREFIX", "${root}${GlibcWineConsts.WINEPREFIX_REL}")

        // ====== wine 路径变量 ======
        val wineDir = if (wineInfo.isDefaultWine() && wineInfo.path != null) {
            "$root${wineInfo.path}"
        } else {
            "$root${GlibcWineConsts.WINE_PATH_REL}"
        }
        val wineBinDir = "$wineDir/bin"
        val wineLibDir = "$wineDir/lib"
        val wineLib64Dir = "$wineDir/lib64"
        val wineDllDir = "$wineDir/lib/wine"

        envVars.put("PATH", "$wineBinDir:/usr/bin:/usr/local/bin:/bin")
        envVars.put("LD_LIBRARY_PATH", "$wineLib64Dir:$wineLibDir:${root}${GlibcWineConsts.GLIBC64_DIR_REL}")
        envVars.put("WINEDLLPATH", wineDllDir)
        envVars.put("FONTCONFIG_PATH", "${root}${GlibcWineConsts.FONTCONFIG_DIR_REL}")

        // ====== box64 环境变量 (仅 x86_64 架构) ======
        if (!isArm64EC) {
            addBox64EnvVars(envVars, container, root)
        } else {
            // arm64ec 使用 FEX, 设置 HODLL
            when (container.fexPreset) {
                0 -> envVars.put("HODLL", "libwow64fex.dll")
                1 -> envVars.put("HODLL", "wowbox64.dll")
                else -> envVars.remove("HODLL")
            }
        }

        // ====== wine 调试 ======
        envVars.put("WINEDEBUG", "-all") // 默认关闭调试输出

        // ====== box64 rc 文件 ======
        if (!isArm64EC) {
            val rcfile = File(container.rootDir, ".box64rc")
            if (rcfile.exists()) {
                envVars.put("BOX64_RCFILE", "${root}${GlibcWineConsts.HOME_PATH_REL}/.box64rc")
            }
        }

        // ====== 容器自定义环境变量 (mesa, zink, 渲染等) ======
        val containerEnvVars = GlibcEnvVars(container.envVars)
        envVars.putAll(containerEnvVars)

        // ====== 图形驱动相关 ======
        val graphicsDriver = container.graphicsDriver.lowercase()
        when {
            graphicsDriver.contains("turnip") -> {
                envVars.put("GALLIUM_DRIVER", "zink")
            }
            graphicsDriver.contains("virgl") -> {
                envVars.put("GALLIUM_DRIVER", "virpipe")
            }
            graphicsDriver.contains("freedreno") -> {
                envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl")
            }
        }

        // ====== LC_ALL ======
        if (container.lcAll.isNotEmpty()) {
            envVars.put("LC_ALL", container.lcAll)
        }

        // ====== 光标主题 ======
        if (container.cursorTheme.isNotEmpty()) {
            envVars.put("XCURSOR_THEME", container.cursorTheme)
        }
        if (container.cursorSize.isNotEmpty()) {
            envVars.put("XCURSOR_SIZE", container.cursorSize)
        }

        return envVars
    }

    /**
     * 添加 box64 环境变量。
     */
    private fun addBox64EnvVars(envVars: GlibcEnvVars, container: WineContainer, root: String) {
        envVars.put("BOX64_NOBANNER", "1")
        envVars.put("BOX64_DYNAREC", "1")
        envVars.put("BOX64_MMAP32", "1")
        envVars.put("BOX64_X11GLX", "1")

        // box64 64 位库搜索路径
        val box64LdLibPath = listOf(
            "${root}${GlibcWineConsts.X86_64_GLIBC_DIR_REL}",
            "${root}${GlibcWineConsts.GLIBC64_DIR_REL}"
        ).joinToString(":")
        envVars.put("BOX64_LD_LIBRARY_PATH", box64LdLibPath)

        // box64 预设环境变量
        val presetEnvVars = Box64PresetManager.getEnvVars(container.box64Preset)
        envVars.putAll(presetEnvVars)
    }

    /**
     * 构建 wine 启动命令行。
     *
     * x86_64: box64 <winePath> explorer /desktop=shell,<size> <exe>
     * arm64ec: <ld-linux> <winePath> explorer /desktop=shell,<size> <exe>
     */
    fun buildWineCommand(
        container: WineContainer,
        wineInfo: WineInfo,
        exePath: String? = null,
        exeArgs: String = "",
        workingDir: String? = null
    ): String {
        val root = GlibcWineConsts.CONTAINER_IMAGEFS_ROOT
        val isArm64EC = wineInfo.arch.equals("arm64ec", ignoreCase = true)

        val wineDir = if (wineInfo.isDefaultWine() && wineInfo.path != null) {
            "$root${wineInfo.path}"
        } else {
            "$root${GlibcWineConsts.WINE_PATH_REL}"
        }
        val wineBinPath = "$wineDir/bin/wine"
        val screenSize = container.screenSize.ifEmpty { GlibcWineConsts.DEFAULT_SCREEN_SIZE }

        val wineStartCmd = GlibcWineUtils.getWineStartCommand(screenSize, exePath, exeArgs, workingDir)

        return if (!isArm64EC) {
            // x86_64: box64 wine ...
            val box64Path = "${root}${GlibcWineConsts.BOX64_BIN_REL}"
            "$box64Path $wineBinPath $wineStartCmd"
        } else {
            // arm64ec: ld-linux wine ...
            val ldPath = "${root}${GlibcWineConsts.ARM64EC_LD_REL}"
            "$ldPath $wineBinPath $wineStartCmd"
        }
    }

    /**
     * 独立模式: 创建独立的 proot 进程运行 wine。
     *
     * 类似 ContainerExec, 但:
     * 1. 额外绑定 imagefs 到 /opt/glibc-wine
     * 2. 设置 wine 环境变量
     * 3. 执行 box64 + wine 命令
     *
     * @param rootfs proot rootfs 目录
     * @param container wine 容器
     * @param exePath 要执行的可执行文件路径 (null 则启动 winefile)
     * @param exeArgs 执行参数
     * @param workingDir 工作目录
     * @param onOutput 输出回调
     * @return 启动结果
     */
    suspend fun launchStandalone(
        rootfs: File,
        container: WineContainer,
        exePath: String? = null,
        exeArgs: String = "",
        workingDir: String? = null,
        onOutput: ((String) -> Unit)? = null
    ): LaunchResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "独立模式启动 wine: container=${container.name}, exe=$exePath")

        // 1. 激活 wine 容器
        containerManager.activateContainer(container)

        // 2. 创建 dosdevices 盘符映射
        // (不手动创建 wine prefix, wine 首次运行时自动初始化)
        GlibcWineUtils.createDosdevicesSymlinks(container, imageFs.rootDir)

        // 3. 确保 box64 已解压
        // (imagefs 安装时已包含 box64, 无需额外解压)

        // 4. 构建 proot 命令
        val wineInfo = WineInfo.fromIdentifier(context, container.wineVersion)
        val wineCommand = buildWineCommand(container, wineInfo, exePath, exeArgs, workingDir)
        val wineEnvVars = buildWineEnvVars(container, wineInfo)

        val prootCmd = buildProotCommand(rootfs, wineCommand, wineEnvVars, container)

        Log.d(TAG, "proot 命令: ${prootCmd.joinToString(" ")}")

        // 5. 执行
        val pb = ProcessBuilder(prootCmd)
            .directory(rootfs)
            .also {
                it.environment()["PROOT_TMP_DIR"] = Consts.tmpDir.absolutePath
                it.environment()["LD_PRELOAD"] = ""
            }
            .redirectErrorStream(true)

        val buffer = StringBuilder()
        try {
            val proc = pb.start()
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    buffer.appendLine(line)
                    onOutput?.invoke(line)
                }
            }
            val code = proc.waitFor()
            Log.i(TAG, "wine 进程退出, code=$code")
            LaunchResult(success = code == 0, exitCode = code, output = buffer.toString())
        } catch (e: Throwable) {
            Log.e(TAG, "wine 启动失败", e)
            LaunchResult(success = false, error = e.message)
        }
    }

    /**
     * 构建 proot 命令 (含 imagefs 绑定挂载和 wine 环境变量)。
     *
     * 遵循 ContainerExec 的命令构造逻辑, 但增加:
     * 1. imagefs 绑定: --bind=<filesDir>/glibc-wine:/opt/glibc-wine
     * 2. wine 环境变量 (DISPLAY=:13, PULSE_SERVER, HOME, WINEPREFIX, LD_LIBRARY_PATH 等)
     * 3. wine 启动命令
     */
    private suspend fun buildProotCommand(
        rootfs: File,
        wineCommand: String,
        wineEnvVars: GlibcEnvVars,
        container: WineContainer
    ): MutableList<String> {
        val tmpdir = Consts.tmpDir
        val lang = Consts.Pref.general_rootfs_lang.get()
        val userInfo = ProotRootfs.getPreferredUser(rootfs.canonicalFile.name)
        val l2sDir = File(rootfs, ".l2s")
        l2sDir.mkdirs()
        runCatching { Utils.chmod(l2sDir, "755") }
        ProotHelper.setup_fake_data()

        val prootCmd = mutableListOf(
            Consts.prootBin.absolutePath,
            *Consts.Pref.proot_bool_options.get().toTypedArray(),
            "--kernel-release=${ProotHelper.DEFAULT_FAKE_KERNEL_VERSION}",
            "--rootfs=${rootfs.absolutePath}",
            "--change-id=${userInfo.uid}:${userInfo.gid}",
            "--cwd=${userInfo.home}",
            "--bind=${tmpdir.absolutePath}:/tmp",
            "--bind=${rootfs.absolutePath}/tmp:/dev/shm",
            "--bind=/sys",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/dev",
        )

        // ====== 关键: 绑定 imagefs 到容器内 /opt/glibc-wine ======
        prootCmd.add("--bind=${imageFs.rootDir.absolutePath}:${GlibcWineConsts.CONTAINER_MOUNT_POINT}")

        // ====== 用户共享路径绑定 ======
        prootCmd.addAll(
            Consts.Pref.general_shared_ext_path.get().map { bindPath ->
                File(rootfs, bindPath).runCatching {
                    takeIf { org.apache.commons.io.FileUtils.isSymlink(it) }?.delete()
                }
                "--bind=$bindPath"
            }
        )
        prootCmd.add("--bind=${rootfs.absolutePath}/sys/.empty:/sys/fs/selinux")

        // ====== 环境变量: proot 基础 + wine 专用 ======
        // 先设置 proot 基础环境变量
        val envStr = mutableListOf(
            "LANG=$lang",
            "USER=${userInfo.name}",
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )

        // 覆盖 HOME 为 wine 容器的 HOME
        // (wine 需要 HOME 指向 imagefs 的 /home/xuser)
        val wineHome = wineEnvVars.get("HOME")
        if (wineHome.isNotEmpty()) {
            envStr.add("HOME=$wineHome")
        } else {
            envStr.add("HOME=${userInfo.home}")
        }

        // 添加 wine 专用环境变量
        for (key in wineEnvVars) {
            if (key == "HOME" || key == "LANG" || key == "USER" || key == "TMPDIR") continue
            envStr.add("$key=${wineEnvVars.get(key)}")
        }

        prootCmd.add("/usr/bin/env")
        prootCmd.add("-i")
        prootCmd.addAll(envStr)
        prootCmd.addAll(listOf("sh", "-c", wineCommand))

        return prootCmd
    }

    /**
     * 生成 wine 启动脚本, 供已运行的 proot 容器调用。
     *
     * ## 工作原理
     *
     * wine 不在 proot 容器内运行, 而是直接在 Android 上运行(使用 imagefs 的 glibc 库)。
     * 此脚本通过 fifo 向 Android 端的 [GlibcWineCommandServer] 发送启动命令。
     *
     * fifo 路径: /opt/glibc-wine/tmp/wine-cmd (即 imagefs/tmp/wine-cmd, 通过 --bind 暴露)
     *
     * 命令格式:
     * - 空行或 "winefile" → 启动 winefile
     * - "exe:<路径>" → 启动指定 exe
     * - "kill" → 停止 wine
     */
    fun generateLaunchScript(container: WineContainer): String {
        val sb = StringBuilder()
        sb.append("#!/bin/sh\n")
        sb.append("# linbox-wine: glibc wine 启动脚本\n")
        sb.append("# 由 GlibcWineLauncher 自动生成\n")
        sb.append("#\n")
        sb.append("# 工作原理:\n")
        sb.append("#   wine 不在 proot 内运行, 而是直接在 Android 上运行\n")
        sb.append("#   此脚本通过 fifo 向 Android 端发送启动命令\n")
        sb.append("#   X11 和音频通过共享的 Termux-X11/PulseAudio 实现\n")
        sb.append("\n")
        sb.append("# fifo 路径 (imagefs 通过 --bind 挂载到 /opt/glibc-wine)\n")
        sb.append("FIFO=\"/opt/glibc-wine/tmp/wine-cmd\"\n")
        sb.append("\n")
        sb.append("# 检查 fifo 是否存在\n")
        sb.append("if [ ! -p \"\$FIFO\" ]; then\n")
        sb.append("    echo \"错误: fifo 不存在 (\$FIFO)\"\n")
        sb.append("    echo \"请确保 glibc wine 服务已启动\"\n")
        sb.append("    exit 1\n")
        sb.append("fi\n")
        sb.append("\n")
        sb.append("# 发送命令\n")
        sb.append("if [ -z \"\$1\" ]; then\n")
        sb.append("    # 无参数, 启动 winefile\n")
        sb.append("    echo \"winefile\" > \"\$FIFO\" &\n")
        sb.append("else\n")
        sb.append("    # 有参数, 启动指定程序\n")
        sb.append("    echo \"exe:\$@\" > \"\$FIFO\" &\n")
        sb.append("fi\n")
        sb.append("\n")
        sb.append("# 等待写入完成\n")
        sb.append("wait\n")
        sb.append("echo \"命令已发送, wine 窗口将在桌面显示\"\n")

        return sb.toString()
    }

    /**
     * 获取需要添加到 proot 启动命令的额外绑定挂载。
     *
     * 在 Proot.attach() 中使用此方法, 将 imagefs 绑定到容器内 /opt/glibc-wine,
     * 使已运行的 proot 容器可以访问 glibc wine 运行时。
     */
    fun getExtraBinds(): List<Pair<String, String>> {
        return listOf(
            Pair(imageFs.rootDir.absolutePath, GlibcWineConsts.CONTAINER_MOUNT_POINT)
        )
    }

    /**
     * 将启动脚本部署到 proot rootfs 中。
     *
     * @param rootfs proot rootfs 目录
     * @param container wine 容器
     * @return 脚本在容器内的路径
     */
    fun deployLaunchScript(rootfs: File, container: WineContainer): String {
        val scriptDir = File(rootfs, "usr/local/bin")
        scriptDir.mkdirs()
        val scriptFile = File(scriptDir, "linbox-wine")
        scriptFile.writeText(generateLaunchScript(container))

        // 设置可执行权限
        try {
            android.system.Os.chmod(scriptFile.absolutePath, 493) // 0755
        } catch (e: Exception) {
            Log.w(TAG, "设置脚本权限失败", e)
        }

        Log.i(TAG, "启动脚本已部署: ${scriptFile.path}")
        return "/usr/local/bin/linbox-wine"
    }

    /**
     * 停止 wine 进程 (通过 wineserver -k)。
     */
    suspend fun stopWine(container: WineContainer): LaunchResult = withContext(Dispatchers.IO) {
        val wineInfo = WineInfo.fromIdentifier(context, container.wineVersion)
        val rootDir = imageFs.rootDir
        val root = imageFs.rootPath
        val wineServerPath = if (wineInfo.isDefaultWine() && wineInfo.path != null) {
            "$root${wineInfo.path}/bin/wineserver"
        } else {
            "$root${GlibcWineConsts.WINE_PATH_REL}/bin/wineserver"
        }

        // 用 glibc 动态链接器启动 wineserver
        val glibcLd = "$root${GlibcWineConsts.ARM64EC_LD_REL}"
        val glibcLibPath = listOf(
            "$root${GlibcWineConsts.GLIBC64_DIR_REL}",
            "$root${GlibcWineConsts.X86_64_GLIBC_DIR_REL}"
        ).joinToString(":")

        val command = if (wineInfo.arch.equals("arm64ec", ignoreCase = true)) {
            listOf(glibcLd, "--library-path", glibcLibPath, wineServerPath, "-k")
        } else {
            val box64Path = "$root${GlibcWineConsts.BOX64_BIN_REL}"
            listOf(glibcLd, "--library-path", glibcLibPath, box64Path, wineServerPath, "-k")
        }

        Log.i(TAG, "停止 wine: ${command.joinToString(" ")}")
        return@withContext try {
            val pb = ProcessBuilder(command).directory(rootDir)
            pb.environment().putAll(buildDirectEnvVars(container, wineInfo, root))
            pb.environment().remove("LD_LIBRARY_PATH")
            val proc = pb.start()
            proc.waitFor()
            LaunchResult(success = true)
        } catch (e: Exception) {
            Log.e(TAG, "停止 wine 失败", e)
            LaunchResult(success = false, error = e.message)
        }
    }

    // ====== 直接在 Android 运行 (不在 proot 内) ======

    /**
     * 直接在 Android 上启动 wine (不在 proot 容器内)。
     *
     * wine 使用 imagefs 的 glibc 库 (通过 LD_LIBRARY_PATH), 直接在 Android 进程空间运行。
     * X11 通过 Termux-X11 (DISPLAY=:13) 共享, 音频通过 PulseAudio (tcp:127.0.0.1:4713) 共享。
     *
     * 此方法由 [GlibcWineCommandServer] 在收到 fifo 命令后调用。
     *
     * @param exePath 要执行的 exe 路径 (null 启动 winefile)
     * @param exeArgs 执行参数
     * @return 启动结果
     */
    fun launchDirect(exePath: String?, exeArgs: String = ""): LaunchResult {
        Log.i(TAG, "直接模式启动 wine (Android 原生): exe=$exePath")

        // 使用 rootPath 得到 /data/data/... 形式路径
        // 因为 imagefs 中的二进制是按 /data/data/<pkg>/files/imagefs 编译的 (RPATH 硬编码)
        val rootDir = imageFs.rootDir
        val root = imageFs.rootPath
        Log.i(TAG, "imagefs 根目录 (rootPath): $root")
        Log.i(TAG, "imagefs 根目录 (rootDir): ${rootDir.absolutePath}")

        // 获取容器 (可选, 没有就用默认配置)
        // wine 不依赖容器存在, 首次运行会自动创建 prefix
        val container = containerManager.getActivatedContainer()
            ?: containerManager.getContainers().firstOrNull()
        if (container != null) {
            // 确保容器已激活, 创建 dosdevices 盘符映射
            containerManager.activateContainer(container)
            GlibcWineUtils.createDosdevicesSymlinks(container, rootDir)
        } else {
            Log.w(TAG, "没有 wine 容器, 使用默认配置直接启动 (wine 会自动创建 prefix)")
        }

        // 使用容器的 wine 版本, 或默认 x86_64 wine
        val wineInfo = if (container != null) {
            WineInfo.fromIdentifier(context, container.wineVersion)
        } else {
            WineInfo.MAIN_WINE_VERSION
        }

        // 构建环境变量 (使用 /data/data/... 绝对路径)
        val envVars = buildDirectEnvVars(container, wineInfo, root)

        // 构建启动命令
        val isArm64EC = wineInfo.arch.equals("arm64ec", ignoreCase = true)
        val wineDir = if (wineInfo.isDefaultWine() && wineInfo.path != null) {
            "$root${wineInfo.path}"
        } else {
            "$root${GlibcWineConsts.WINE_PATH_REL}"
        }
        val wineBinPath = "$wineDir/bin/wine"
        val screenSize = container?.screenSize?.ifEmpty { GlibcWineConsts.DEFAULT_SCREEN_SIZE }
            ?: GlibcWineConsts.DEFAULT_SCREEN_SIZE
        // 使用 List 形式避免空格分割 bug (exe 路径可能含空格)
        val wineArgs = GlibcWineUtils.getWineStartCommandList(screenSize, exePath, exeArgs, null)

        // 关键: 用 glibc 动态链接器 (ld-linux-aarch64.so.1) 启动 box64
        // 因为 box64 是按 glibc 编译的, 不能直接用 Android bionic 加载
        // 用 --library-path 代替 LD_LIBRARY_PATH, 避免污染子进程 (如 /system/bin/sh)
        val glibcLd = "$root${GlibcWineConsts.ARM64EC_LD_REL}" // /usr/lib/ld-linux-aarch64.so.1
        val glibcLibPath = listOf(
            "$root${GlibcWineConsts.GLIBC64_DIR_REL}",
            "$root${GlibcWineConsts.X86_64_GLIBC_DIR_REL}"
        ).joinToString(":")

        val cmd = if (!isArm64EC) {
            val box64Path = "$root${GlibcWineConsts.BOX64_BIN_REL}"
            // ld-linux-aarch64.so.1 --library-path <glibc libs> box64 wine <args>
            listOf(glibcLd, "--library-path", glibcLibPath, box64Path, wineBinPath) + wineArgs
        } else {
            // arm64ec: 直接用 glibc ld 启动 wine (不需要 box64)
            listOf(glibcLd, "--library-path", glibcLibPath, wineBinPath) + wineArgs
        }

        Log.i(TAG, "启动命令: ${cmd.joinStringSafe()}")
        Log.i(TAG, "工作目录: $root")
        Log.i(TAG, "WINEPREFIX: ${envVars["WINEPREFIX"]}")
        Log.i(TAG, "DISPLAY: ${envVars["DISPLAY"]}")
        Log.i(TAG, "PATH: ${envVars["PATH"]}")
        Log.i(TAG, "glibc LD: $glibcLd")
        Log.i(TAG, "glibc lib path: $glibcLibPath")
        Log.i(TAG, "BOX64_LD_LIBRARY_PATH: ${envVars["BOX64_LD_LIBRARY_PATH"]}")
        Log.i(TAG, "WINEDLLPATH: ${envVars["WINEDLLPATH"]}")
        Log.i(TAG, "WINEDEBUG: ${envVars["WINEDEBUG"]}")

        return try {
            val pb = ProcessBuilder(cmd)
                .directory(rootDir)
                .redirectErrorStream(true)
            pb.environment().putAll(envVars)
            // 不设置全局 LD_LIBRARY_PATH (会破坏 /system/bin/sh 等系统程序)
            // glibc 库路径通过 ld-linux 的 --library-path 参数传递
            pb.environment().remove("LD_LIBRARY_PATH")
            pb.environment()["PROOT_TMP_DIR"] = ""
            pb.environment()["LD_PRELOAD"] = ""

            val proc = pb.start()
            GlibcWineCommandServer // 引用以确保类加载

            // 在后台线程读取输出 (持续读取直到进程结束)
            Thread {
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            Log.i(TAG, "[wine] $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取 wine 输出失败", e)
                }
                // 进程输出结束后, 打印退出码
                try {
                    val exitCode = proc.waitFor()
                    Log.i(TAG, "[wine] 进程结束, 退出码: $exitCode")
                } catch (e: Exception) {
                    Log.e(TAG, "[wine] 等待进程结束失败", e)
                }
            }.start()

            // 通过反射设置 wineProcess (GlibcWineCommandServer 中)
            setWineProcess(proc)

            Log.i(TAG, "wine 进程已启动")
            LaunchResult(success = true)
        } catch (e: Throwable) {
            Log.e(TAG, "wine 启动失败", e)
            LaunchResult(success = false, error = e.message)
        }
    }

    /** 安全的 joinToString, 避免空格分割问题 */
    private fun List<String>.joinStringSafe(): String = joinToString(" ")

    /**
     * 构建 wine 直接运行的环境变量 (使用 /data/data/... 绝对路径)。
     *
     * 注意: 不设置 LD_LIBRARY_PATH, 因为它会污染子进程 (如 /system/bin/sh)。
     * glibc 库路径通过 ld-linux-aarch64.so.1 的 --library-path 参数传递。
     *
     * @param container wine 容器 (可为 null, null 时使用默认配置)
     * @param root imagefs 根目录的 canonical 路径 (/data/data/... 形式)
     */
    private fun buildDirectEnvVars(container: WineContainer?, wineInfo: WineInfo, root: String): Map<String, String> {
        val envVars = mutableMapOf<String, String>()

        val isArm64EC = wineInfo.arch.equals("arm64ec", ignoreCase = true)

        // ====== 基础路径变量 (使用 /data/data/... 路径) ======
        envVars["HOME"] = "$root${GlibcWineConsts.HOME_PATH_REL}"
        envVars["USER"] = GlibcWineConsts.USER
        envVars["TMPDIR"] = "$root${GlibcWineConsts.TMP_DIR_REL}"
        envVars["DISPLAY"] = GlibcWineConsts.DISPLAY
        envVars["PULSE_SERVER"] = GlibcWineConsts.PULSE_SERVER
        // WINEPREFIX 使用默认路径, wine 首次运行时会自动初始化
        envVars["WINEPREFIX"] = "$root${GlibcWineConsts.WINEPREFIX_REL}"

        // ====== wine 路径变量 ======
        val wineDir = if (wineInfo.isDefaultWine() && wineInfo.path != null) {
            "$root${wineInfo.path}"
        } else {
            "$root${GlibcWineConsts.WINE_PATH_REL}"
        }
        // PATH 包含 /system/bin, 让 lscpu 等 Android 系统工具可用 (box64 启动时会调用)
        envVars["PATH"] = "$wineDir/bin:/usr/bin:/bin:/system/bin:/system/xbin"
        // 不设置 LD_LIBRARY_PATH! 会破坏 /system/bin/sh 等系统程序
        // glibc 库路径通过 ld-linux-aarch64.so.1 --library-path 传递
        envVars["WINEDLLPATH"] = "$wineDir/lib/wine:$wineDir/lib64/wine"
        envVars["FONTCONFIG_PATH"] = "$root${GlibcWineConsts.FONTCONFIG_DIR_REL}"

        // ====== box64 环境变量 (仅 x86_64) ======
        if (!isArm64EC) {
            // 调试: 不禁用 banner, 方便排查
            envVars["BOX64_NOBANNER"] = "0"
            envVars["BOX64_DYNAREC"] = "1"
            envVars["BOX64_MMAP32"] = "1"
            envVars["BOX64_X11GLX"] = "1"
            // BOX64_LD_LIBRARY_PATH: box64 加载 x86_64 ELF 时查找依赖库的路径
            // 必须包含: x86_64 glibc 库 + wine 的 lib/lib64 (ntdll.so 依赖 libwine.so.1 等)
            envVars["BOX64_LD_LIBRARY_PATH"] = listOf(
                "$root${GlibcWineConsts.X86_64_GLIBC_DIR_REL}",  // x86_64 glibc 库
                "$root${GlibcWineConsts.GLIBC64_DIR_REL}",         // aarch64 glibc 库
                "$wineDir/lib",                                     // wine 32 位库
                "$wineDir/lib64",                                   // wine 64 位库
                "$wineDir/lib/wine",                                // wine DLL
                "$root${GlibcWineConsts.CONTENTS_DIR_REL}/lib"      // 其他内容库
            ).joinToString(":")
            // box64 预设 (容器为 null 时使用兼容性预设)
            val presetId = container?.box64Preset ?: Box64Preset.COMPATIBILITY
            val presetEnvVars = Box64PresetManager.getEnvVars(presetId)
            for (key in presetEnvVars) {
                envVars[key] = presetEnvVars.get(key)
            }
        } else {
            // arm64ec 的 fex 预设 (容器为 null 时默认 0)
            when (container?.fexPreset ?: 0) {
                0 -> envVars["HODLL"] = "libwow64fex.dll"
                1 -> envVars["HODLL"] = "wowbox64.dll"
            }
        }

        // 调试模式: 启用 wine 和 box64 日志, 方便排查问题
        // 调试完成后可改回 WINEDEBUG=-all
        envVars["WINEDEBUG"] = "+loaddll,+module"

        // ====== 容器自定义环境变量 (容器为 null 时用默认) ======
        val envVarsStr = container?.envVars ?: GlibcWineConsts.DEFAULT_ENV_VARS
        val containerEnvVars = GlibcEnvVars(envVarsStr)
        for (key in containerEnvVars) {
            envVars[key] = containerEnvVars.get(key)
        }

        // ====== 图形驱动 (容器为 null 时用默认 turnip) ======
        val graphicsDriver = (container?.graphicsDriver ?: GlibcWineConsts.DEFAULT_GRAPHICS_DRIVER).lowercase()
        when {
            graphicsDriver.contains("turnip") -> envVars["GALLIUM_DRIVER"] = "zink"
            graphicsDriver.contains("virgl") -> envVars["GALLIUM_DRIVER"] = "virpipe"
            graphicsDriver.contains("freedreno") -> envVars["MESA_LOADER_DRIVER_OVERRIDE"] = "kgsl"
        }

        container?.lcAll?.takeIf { it.isNotEmpty() }?.let { envVars["LC_ALL"] = it }
        container?.cursorTheme?.takeIf { it.isNotEmpty() }?.let { envVars["XCURSOR_THEME"] = it }
        container?.cursorSize?.takeIf { it.isNotEmpty() }?.let { envVars["XCURSOR_SIZE"] = it }

        return envVars
    }

    /**
     * 杀死 wine 进程。
     */
    fun killWine() {
        try {
            wineProcess?.destroyForcibly()
            wineProcess = null
            Log.i(TAG, "wine 进程已杀死")
        } catch (e: Exception) {
            Log.e(TAG, "杀死 wine 失败", e)
        }
    }

    /** 当前 wine 进程 (供 CommandServer 管理) */
    @Volatile
    private var wineProcess: Process? = null

    private fun setWineProcess(proc: Process) {
        wineProcess = proc
    }
}
