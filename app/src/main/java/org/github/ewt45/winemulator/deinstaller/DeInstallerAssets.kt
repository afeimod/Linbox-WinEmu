package org.github.ewt45.winemulator.deinstaller

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 把 assets/de-installer/ 下的脚本同步到 app filesDir/de-installer/,
 * 然后通过 [ContainerExec] 的 --bind 挂进容器:
 *   -b <filesDir>/de-installer:/de-installer
 *
 * 调用点: Application.onCreate() 调一次.
 */
object DeInstallerAssets {

    private const val ASSETS_DIR = "de-installer"
    private const val TARGET_DIR_NAME = "de-installer"

    fun ensureDeployed(context: Context): File {
        val target = File(context.filesDir, TARGET_DIR_NAME)
        if (!target.exists()) target.mkdirs()
        copyAssetDir(context, ASSETS_DIR, target)
        target.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".sh")) {
                runCatching { f.setExecutable(true, false) }
            }
        }
        return target
    }

    /** 给 [ContainerExec] 的 extraBinds 用 */
    fun bindForContainer(context: Context): Pair<String, String> {
        val dir = ensureDeployed(context)
        return dir.absolutePath to "/de-installer"
    }

    private fun copyAssetDir(context: Context, assetPath: String, outDir: File) {
        val assets = context.assets
        val list = runCatching { assets.list(assetPath) }.getOrNull() ?: return
        if (list.isEmpty()) {
            outDir.parentFile?.mkdirs()
            runCatching {
                assets.open(assetPath).use { input ->
                    FileOutputStream(outDir).use { output -> input.copyTo(output) }
                }
            }
        } else {
            outDir.mkdirs()
            for (name in list) {
                copyAssetDir(context, "$assetPath/$name", File(outDir, name))
            }
        }
    }
}
