package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.util.Log
import org.github.ewt45.winemulator.Utils
import java.io.File

/**
 * Wine 工具函数, 移植自 winlator-glibc 的 WineUtils.java。
 *
 * 提供 dosdevices 符号链接创建、wine 系统文件配置等功能。
 */
object GlibcWineUtils {
    private const val TAG = "GlibcWineUtils"

    /**
     * 创建 dosdevices 符号链接 (盘符映射)。
     *
     * c: -> ../drive_c (wine prefix 内的 C 盘)
     * z: -> imagefs rootDir (glibc wine 根目录)
     * 用户定义的盘符 (d:, e: 等) -> 指定路径
     *
     * @param container wine 容器
     * @param imageFsRootDir imagefs 根目录 (Android 路径)
     */
    fun createDosdevicesSymlinks(container: WineContainer, imageFsRootDir: File) {
        val dosdevicesDir = File(container.rootDir, ".wine/dosdevices")
        dosdevicesDir.mkdirs()

        // c: -> ../drive_c
        val cDrive = File(dosdevicesDir, "c:")
        if (!cDrive.exists()) {
            Utils.Files.symlink(File("../drive_c"), cDrive)
        }

        // z: -> imagefs rootDir
        val zDrive = File(dosdevicesDir, "z:")
        if (!zDrive.exists()) {
            Utils.Files.symlink(imageFsRootDir, zDrive)
        }

        // 用户定义的盘符
        for (pair in container.drivesIterator()) {
            val driveLetter = pair[0]
            val path = pair[1]
            if (driveLetter.equals("c", ignoreCase = true) || driveLetter.equals("z", ignoreCase = true)) continue
            val driveFile = File(dosdevicesDir, "${driveLetter.lowercase()}:")
            if (!driveFile.exists()) {
                val targetFile = File(path)
                Utils.Files.symlink(targetFile, driveFile)
            }
        }
    }

    /**
     * 获取 wine 启动命令。
     *
     * 在 winlator-glibc 中, 启动命令格式:
     * wine explorer /desktop=shell,<screenSize> winhandler.exe /dir <exeDir> "<filename>" <args>
     *
     * 在 proot 整合中, 我们简化为:
     * wine explorer /desktop=shell,<screenSize> "<exePath>" <args>
     *
     * 如果没有指定 exe, 则启动 winefile (Wine 文件管理器)。
     */
    /**
     * 构建 wine 启动命令参数列表。
     *
     * 支持三种模式:
     * 1. 内置程序 (taskmgr/notepad/regedit/wineboot/winecfg/explorer/cmd/control):
     *    直接传程序名, 不加 explorer /desktop 包装
     * 2. 无 exe: 启动 winefile (文件管理器), 使用 explorer /desktop
     * 3. 指定 exe 路径: 使用 explorer /desktop 包装, 在虚拟桌面中运行
     *
     * @return 命令参数列表 (不含 wine 本身), 可直接用于 ProcessBuilder
     */
    fun getWineStartCommandList(
        screenSize: String,
        exePath: String? = null,
        exeArgs: String = "",
        workingDir: String? = null
    ): List<String> {
        // wine 内置程序列表 - 直接运行, 不用 explorer /desktop
        val builtinPrograms = setOf(
            "taskmgr", "notepad", "regedit", "wineboot", "winecfg",
            "explorer", "cmd", "control", "msinfo32", "winetricks",
            "wineconsole", "reg", "msiexec", "rundll32"
        )

        // 无参数 → 启动 winefile
        if (exePath.isNullOrEmpty()) {
            return listOf("explorer", "/desktop=shell,$screenSize", "winefile.exe")
        }

        // 内置程序 → 直接运行
        val lowerExe = exePath.lowercase()
        if (exePath in builtinPrograms || lowerExe in builtinPrograms) {
            val result = mutableListOf(exePath)
            if (exeArgs.isNotEmpty()) {
                result.addAll(exeArgs.split(" ").filter { it.isNotEmpty() })
            }
            return result
        }

        // 外部 exe → explorer /desktop 包装
        val result = mutableListOf("explorer", "/desktop=shell,$screenSize")
        if (!workingDir.isNullOrEmpty()) {
            result.add("/dir")
            result.add(workingDir)
        }
        result.add(exePath)
        if (exeArgs.isNotEmpty()) {
            result.addAll(exeArgs.split(" ").filter { it.isNotEmpty() })
        }
        return result
    }

    /**
     * 构建 wine 启动命令字符串 (旧接口, 保留兼容)。
     */
    fun getWineStartCommand(
        screenSize: String,
        exePath: String? = null,
        exeArgs: String = "",
        workingDir: String? = null
    ): String {
        return getWineStartCommandList(screenSize, exePath, exeArgs, workingDir).joinToString(" ")
    }

    /**
     * 生成 box64rc 文件内容 (按进程名分段的 ini 风格配置)。
     * 暂时返回空字符串, 使用 box64 默认配置。
     */
    fun generateBox64rc(): String {
        return ""
    }

    /**
     * 检查并创建 wine prefix 所需的基本目录结构。
     */
    fun ensureWinePrefixStructure(container: WineContainer) {
        val dirs = listOf(
            ".wine",
            ".wine/drive_c",
            ".wine/drive_c/windows",
            ".wine/drive_c/windows/system32",
            ".wine/drive_c/windows/syswow64",
            ".wine/drive_c/windows/temp",
            ".wine/drive_c/users/${GlibcWineConsts.USER}",
            ".wine/drive_c/users/${GlibcWineConsts.USER}/Desktop",
            ".wine/dosdevices"
        )
        for (dir in dirs) {
            File(container.rootDir, dir).mkdirs()
        }
    }
}
