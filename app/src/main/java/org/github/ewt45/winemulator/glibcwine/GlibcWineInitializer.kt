package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * glibc wine 运行时初始化器。
 *
 * 负责在应用启动或首次使用 glibc wine 时:
 * 1. 安装 imagefs (glibc wine 镜像)
 * 2. 创建默认 wine 容器 (如果没有)
 * 3. 部署启动脚本到 proot rootfs
 * 4. 验证运行时完整性
 *
 * 使用方式:
 * ```kotlin
 * GlibcWineInitializer.initialize(context) { ready ->
 *     if (ready) {
 *         // glibc wine 已就绪
 *     }
 * }
 * ```
 */
object GlibcWineInitializer {
    private const val TAG = "GlibcWineInitializer"

    /**
     * 初始化状态。
     */
    enum class State {
        NOT_INITIALIZED,
        INSTALLING,
        READY,
        MISSING_IMAGEFS,
        ERROR
    }

    /**
     * 初始化 glibc wine 运行时。
     *
     * @param context 应用上下文
     * @param onProgress 安装进度回调 (0-100)
     * @param onComplete 完成回调, 参数为是否成功
     */
    fun initialize(
        context: Context,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        Log.i(TAG, "开始初始化 glibc wine 运行时")

        // 1. 启动命令服务器 (监听 fifo, 供 proot 容器内调用)
        GlibcWineCommandServer.start(context)

        // 2. 安装 imagefs
        GlibcImageFsInstaller.installIfNeeded(context, onProgress) { installed ->
            if (!installed) {
                Log.w(TAG, "imagefs 未安装, glibc wine 功能不可用")
                onComplete?.invoke(false)
                return@installIfNeeded
            }

            // 3. 验证运行时 (只检查 box64 和 wine 二进制是否存在)
            val valid = validateRuntime(context)
            if (valid) {
                Log.i(TAG, "glibc wine 运行时初始化完成")
            } else {
                Log.w(TAG, "glibc wine 运行时验证失败")
            }
            onComplete?.invoke(valid)
        }
    }

    /**
     * 验证 glibc wine 运行时是否完整。
     */
    fun validateRuntime(context: Context): Boolean {
        val imageFs = GlibcImageFs.find(context)
        if (!imageFs.isValid()) {
            Log.w(TAG, "imagefs 无效")
            return false
        }

        // 检查关键文件是否存在
        val box64 = imageFs.getBox64Bin()
        if (!box64.exists()) {
            Log.w(TAG, "box64 不存在: ${box64.path}")
            // box64 可能需要单独下载, 暂时不阻止
        }

        val wineDir = File(imageFs.rootDir, GlibcWineConsts.WINE_X86_64_PATH_REL)
        if (!wineDir.exists()) {
            Log.w(TAG, "x86_64 wine 不存在: ${wineDir.path}")
            return false
        }

        val wineBin = File(wineDir, "bin/wine")
        if (!wineBin.exists()) {
            Log.w(TAG, "wine 二进制不存在: ${wineBin.path}")
            return false
        }

        Log.i(TAG, "运行时验证通过")
        return true
    }

    /**
     * 获取初始化状态。
     */
    fun getState(context: Context): State {
        val imageFs = GlibcImageFs.find(context)
        return when {
            !imageFs.rootDir.isDirectory -> State.NOT_INITIALIZED
            !imageFs.isValid() -> State.MISSING_IMAGEFS
            validateRuntime(context) -> State.READY
            else -> State.ERROR
        }
    }

    /**
     * 部署 wine 启动脚本到 proot rootfs。
     *
     * 在 proot 容器启动前调用, 将 linbox-wine 脚本放置到 /usr/local/bin/ 下。
     * 用户可以在 proot 终端中运行 `linbox-wine` 来启动 wine。
     *
     * @param context 应用上下文
     * @param rootfs proot rootfs 目录
     * @return 是否部署成功
     */
    fun deployToRootfs(context: Context, rootfs: File): Boolean {
        return try {
            val launcher = GlibcWineLauncher(context)
            launcher.deployLaunchScript(rootfs)
            Log.i(TAG, "wine 启动脚本已部署到 rootfs")
            true
        } catch (e: Exception) {
            Log.e(TAG, "部署启动脚本失败", e)
            false
        }
    }

    /**
     * 获取需要添加到 proot 启动命令的额外绑定挂载。
     *
     * 在 Proot.attach() 中调用此方法, 将 imagefs 绑定到容器内 /opt/glibc-wine。
     * 这样 proot 容器启动后, 用户可以直接通过 /opt/glibc-wine/ 访问 wine 运行时。
     */
    fun getProotExtraBinds(context: Context): List<Pair<String, String>> {
        val imageFs = GlibcImageFs.find(context)
        if (!imageFs.isValid()) return emptyList()
        return listOf(
            Pair(imageFs.rootDir.absolutePath, GlibcWineConsts.CONTAINER_MOUNT_POINT)
        )
    }
}
