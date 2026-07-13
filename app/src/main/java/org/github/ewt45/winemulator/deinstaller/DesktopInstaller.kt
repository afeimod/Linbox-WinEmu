package org.github.ewt45.winemulator.deinstaller

import android.content.Context
import android.util.Log
import java.io.File

object DesktopInstaller {

    private const val TAG = "DesktopInstaller"

    suspend fun install(
        context: Context,
        rootfs: File,
        choice: DesktopChoice,
        onLine: (String) -> Unit,
        onDone: (success: Boolean, errMsg: String?) -> Unit,
    ) {
        if (choice == DesktopChoice.SKIP) {
            onLine("[installer] 用户选择跳过, 不安装桌面")
            onDone(true, null)
            return
        }
        try {
            val bindPair = DeInstallerAssets.bindForContainer(context)
            onLine("[installer] 脚本目录: ${bindPair.first}")
            onLine("[installer] 目标容器: ${rootfs.absolutePath}")
            onLine("[installer] 目标桌面: ${choice.displayName}")

            val cmd = "bash /de-installer/install_de.sh ${choice.value}"
            val result = ContainerExec.run(
                rootfs = rootfs,
                command = cmd,
                extraBinds = listOf(bindPair),
                onLine = { line -> onLine(line) },
            )

            if (result.exitCode == 0) {
                onLine("[installer] ✅ 安装完成 (exit=${result.exitCode})")
                onDone(true, null)
            } else {
                val msg = "install_de.sh 退出码 ${result.exitCode}, 详见上方日志"
                onLine("[installer] ❌ $msg")
                onDone(false, msg)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
            onLine("[installer] ❌ 异常: ${t::class.simpleName}: ${t.message}")
            onDone(false, t.message)
        }
    }
}
