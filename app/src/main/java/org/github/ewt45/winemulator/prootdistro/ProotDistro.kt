package org.github.ewt45.winemulator.prootdistro

import org.github.ewt45.winemulator.Consts
import java.io.File

/**
 * 参考 proot-distrosrc 翻译到 Kotlin 的实现。
 *
 * 本文件汇总 proot-distro 用到的全局常量 / 路径 / 版本信息。
 * 安装入口见 [ProotDistroInstaller.install]。
 */
object ProotDistro {
    /** 用户面对的程序名,和原版一致 */
    const val PROGRAM_NAME = "proot-distro"

    /** 真假 kernel 信息——给 rootfs /proc/.version 用 */
    const val DEFAULT_FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Distro"
    const val DEFAULT_FAKE_KERNEL_VERSION =
        "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"

    const val DEFAULT_PRIMARY_NS = "8.8.8.8"
    const val DEFAULT_SECONDARY_NS = "8.8.4.4"

    /** Docker Hub 官方 registry */
    const val DOCKER_HUB_REGISTRY = "https://registry-1.docker.io"
    const val DOCKER_HUB_AUTH = "https://auth.docker.io/token"

    /** 层缓存与 manifest 缓存都放在 app cacheDir 下 */
    val cacheBaseDir: File
        get() = File(Consts.cacheDir, "proot-distro")

    val layerCacheDir: File
        get() = File(cacheBaseDir, "oci_layers")

    val manifestCacheDir: File
        get() = File(cacheBaseDir, "oci_manifests")

    /** 容器清单文件 (manifest.json) 写在 rootfs 内 */
    fun containerManifest(name: String): File = File(Consts.rootfsAllDir, "$name/.proot-distro-manifest.json")

    val userAgent: String
        get() = "$PROGRAM_NAME/1.0 (linbox)"
}
