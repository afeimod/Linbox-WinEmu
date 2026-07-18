package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.util.Log
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.github.ewt45.winemulator.Utils
import java.io.File

/**
 * glibc wine 镜像文件系统安装器。
 *
 * 负责从 assets 解压 imagefs.tzst (zstd 压缩的 glibc rootfs) 到 <filesDir>/imagefs/。
 *
 * imagefs.tzst 包含完整的运行时:
 * - wine 二进制和库 (x86_64 和 arm64ec 两个版本)
 * - box64 二进制
 * - glibc 系统库
 * - Mesa/Turnip/VirGL 图形驱动
 * - fontconfig 字体配置
 *
 * 安装流程:
 * 1. 清理旧镜像 (保留 home/ 目录, 即用户容器数据)
 * 2. 解压 imagefs.tzst
 * 3. 写入版本文件
 *
 * 注意: 用户已有完整的 imagefs.tzst 包, 只需解压这一个文件即可。
 */
object GlibcImageFsInstaller {
    private const val TAG = "GlibcImageFsInstaller"

    /** 镜像资源文件名 (位于 assets/glibc-wine/ 中) */
    const val IMAGEFS_ASSET = "glibc-wine/imagefs.tzst"

    /**
     * 检查并安装镜像 (如果需要)。
     * 在 Application.onCreate 或首次使用 glibc wine 时调用。
     */
    fun installIfNeeded(context: Context, onProgress: ((Int) -> Unit)? = null, onComplete: ((Boolean) -> Unit)? = null) {
        val imageFs = GlibcImageFs.find(context)
        if (imageFs.isValid() && imageFs.getVersion() >= GlibcWineConsts.LATEST_VERSION) {
            Log.i(TAG, "镜像已存在且版本最新 (${imageFs.getVersion()}), 跳过安装")
            onComplete?.invoke(true)
            return
        }
        installFromAssets(context, onProgress, onComplete)
    }

    /**
     * 从 assets 安装镜像。
     * 只解压 imagefs.tzst, 用户已有完整包。
     */
    fun installFromAssets(context: Context, onProgress: ((Int) -> Unit)? = null, onComplete: ((Boolean) -> Unit)? = null) {
        val imageFs = GlibcImageFs.find(context)
        val rootDir = imageFs.rootDir

        Thread {
            try {
                Log.i(TAG, "开始安装 glibc wine 镜像到 ${rootDir.path}")
                clearRootDir(rootDir)

                // 从 assets 解压 imagefs.tzst (zstd 压缩的 tar)
                val success = try {
                    ZstdCompressorInputStream(context.assets.open(IMAGEFS_ASSET)).use { zis ->
                        Utils.Archive.decompressCompressedTarStream(zis, rootDir)
                    }
                    Log.i(TAG, "imagefs.tzst 解压成功")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "assets 中未找到 $IMAGEFS_ASSET, 请确保 imagefs.tzst 已放入 assets/glibc-wine/", e)
                    false
                }

                if (success) {
                    imageFs.createImgVersionFile(GlibcWineConsts.LATEST_VERSION.toInt())
                    Log.i(TAG, "镜像安装成功, 版本 ${GlibcWineConsts.LATEST_VERSION}")
                    onComplete?.invoke(true)
                } else {
                    // 创建基本目录结构, 即使没有镜像也能知道 glibc wine 功能存在
                    rootDir.mkdirs()
                    File(rootDir, "home").mkdirs()
                    File(rootDir, "opt").mkdirs()
                    File(rootDir, "usr/local/bin").mkdirs()
                    File(rootDir, "usr/lib").mkdirs()
                    File(rootDir, "tmp").mkdirs()
                    Log.w(TAG, "镜像未安装, 已创建基本目录结构")
                    onComplete?.invoke(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "镜像安装失败", e)
                onComplete?.invoke(false)
            }
        }.start()
    }

    /**
     * 清理 rootDir, 但保留 home/ 目录 (用户容器数据)。
     */
    private fun clearRootDir(rootDir: File) {
        if (rootDir.isDirectory) {
            rootDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name == "home") {
                    return@forEach // 保留容器数据
                }
                file.deleteRecursively()
            }
        } else {
            rootDir.mkdirs()
        }
    }

    /**
     * 检查镜像是否已安装且包含 wine 二进制。
     */
    fun isInstalled(context: Context): Boolean {
        val imageFs = GlibcImageFs.find(context)
        if (!imageFs.isValid()) return false
        // 检查 box64 或 wine 是否存在
        val box64 = imageFs.getBox64Bin()
        val wineDir = File(imageFs.rootDir, GlibcWineConsts.WINE_X86_64_PATH_REL)
        return box64.exists() || wineDir.exists()
    }
}
