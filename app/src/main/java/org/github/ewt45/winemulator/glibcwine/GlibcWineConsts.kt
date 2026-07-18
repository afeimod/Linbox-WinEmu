package org.github.ewt45.winemulator.glibcwine

/**
 * glibc wine 运行时的常量定义。
 *
 * 整合架构说明:
 * - glibc wine 的 imagefs 安装在 <filesDir>/imagefs/ 下 (即 /data/data/<pkg>/files/imagefs/),
 *   与 proot 的 rootfs (<filesDir>/rootfs/) 完全分离, 不冲突。
 * - imagefs.tzst 解压后包含完整的 wine、box64、glibc 库等, 无需其他资源文件。
 * - proot 容器启动时, 通过 --bind 将 imagefs 挂载到容器内的 /opt/glibc-wine。
 * - wine 通过 box64 在 proot 容器内启动, 共享 proot 的 X11 (DISPLAY=:13) 和 PulseAudio (tcp:127.0.0.1:4713)。
 * - 用户可在 proot 容器桌面里通过 sh 脚本 (linbox-wine) 调用 imagefs 里的 glibc wine。
 */
object GlibcWineConsts {
    private val TAG = "GlibcWineConsts"

    /** imagefs 在 Android 文件系统中的目录名 (位于 filesDir 下) */
    const val IMAGEFS_DIR_NAME = "imagefs"

    /** imagefs 在 proot 容器内的挂载点路径 */
    const val CONTAINER_MOUNT_POINT = "/opt/glibc-wine"

    /** imagefs 内的用户名 */
    const val USER = "xuser"

    /** imagefs 内的 HOME 路径 (相对 imagefs root) */
    const val HOME_PATH_REL = "/home/$USER"

    /** imagefs 内的 WINEPREFIX 路径 (相对 imagefs root) */
    const val WINEPREFIX_REL = "$HOME_PATH_REL/.wine"

    /** imagefs 内的缓存路径 */
    const val CACHE_PATH_REL = "$HOME_PATH_REL/.cache"

    /** imagefs 内的配置路径 */
    const val CONFIG_PATH_REL = "$HOME_PATH_REL/.config"

    /** imagefs 配置目录 (winlator 自身配置, 非 wine) */
    const val WINLATOR_CONFIG_DIR_REL = "/.winlator"

    /** imagefs 版本文件 */
    const val IMG_VERSION_FILE_REL = "$WINLATOR_CONFIG_DIR_REL/.img_version"

    /** 最新 imagefs 版本 */
    const val LATEST_VERSION: Byte = 11

    /** wine 默认安装路径 (相对 imagefs root) */
    const val WINE_PATH_REL = "/opt/wine"

    /** x86_64 wine 路径 */
    const val WINE_X86_64_PATH_REL = "/opt/x86_64-wine"

    /** arm64ec wine 路径 */
    const val WINE_ARM64EC_PATH_REL = "/opt/arm64ec-wine"

    /** box64 二进制路径 (相对 imagefs root) */
    const val BOX64_BIN_REL = "/usr/local/bin/box64"

    /** arm64ec 动态链接器路径 (相对 imagefs root) */
    const val ARM64EC_LD_REL = "/usr/lib/ld-linux-aarch64.so.1"

    /** 64 位 glibc 库目录 */
    const val GLIBC64_DIR_REL = "/usr/lib"

    /** 32 位 glibc 库目录 */
    const val GLIBC32_DIR_REL = "/usr/lib/arm-linux-gnueabihf"

    /** x86_64 glibc 库目录 (供 box64 使用) */
    const val X86_64_GLIBC_DIR_REL = "/usr/lib/x86_64-linux-gnu"

    /** fontconfig 配置目录 */
    const val FONTCONFIG_DIR_REL = "/usr/etc/fonts"

    /** 已安装 wine 版本存放目录 */
    const val INSTALLED_WINE_DIR_REL = "/opt/installed-wine"

    /** 临时目录 */
    const val TMP_DIR_REL = "/tmp"

    /** contents 内容目录 */
    const val CONTENTS_DIR_REL = "/opt/contents"

    // ====== proot 容器内路径 (imagefs 挂载到 CONTAINER_MOUNT_POINT 后) ======

    /** proot 容器内 imagefs 的 root 路径 */
    const val CONTAINER_IMAGEFS_ROOT = CONTAINER_MOUNT_POINT

    /** proot 容器内 wine HOME 路径 */
    const val CONTAINER_HOME = "$CONTAINER_MOUNT_POINT$HOME_PATH_REL"

    /** proot 容器内 WINEPREFIX 路径 */
    const val CONTAINER_WINEPREFIX = "$CONTAINER_MOUNT_POINT$WINEPREFIX_REL"

    /** proot 容器内 box64 路径 */
    const val CONTAINER_BOX64 = "$CONTAINER_MOUNT_POINT$BOX64_BIN_REL"

    // ====== 共享的 X11 和音频配置 ======

    /** X11 显示号 (与 proot 共享 Termux-X11) */
    const val DISPLAY = ":13"

    /** PulseAudio 服务器 (与 proot 共享) */
    const val PULSE_SERVER = "tcp:127.0.0.1:4713"

    // ====== 默认版本号 ======

    object DefaultVersion {
        const val BOX64 = "0.4.2"
        const val TURNIP = "26.2.0"
        const val VIRGL = "23.1.9"
        const val FREEDRENO = "26.1.0"
        const val DXVK = "2.3.1"
        const val D8VK = "1.0"
        const val VKD3D = "2.12"
        const val CNC_DDRAW = "6.6"
    }

    // ====== 容器默认配置 ======

    /** 默认环境变量 (渲染、音频等关键变量) */
    const val DEFAULT_ENV_VARS = "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=sysmem,noconform WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1 MANGOHUD=1 MANGOHUD_CONFIG=fps,frame_timing=0,ram,gpu_name,vulkan_driver,cpu_mhz,arch,exec_name,swap,font_size=24,engine_version,position=top-left,background_alpha=0.0,hud_no_margin GST_DEBUG=1 vblank_mode=0 TZ=Asia/Shanghai"

    const val DEFAULT_SCREEN_SIZE = "1280x720"
    const val DEFAULT_GRAPHICS_DRIVER = "turnip"
    const val DEFAULT_AUDIO_DRIVER = "pulseaudio"
    const val DEFAULT_DXWRAPPER = "dxvk"
    const val DEFAULT_WINCOMPONENTS = "direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1"
    const val FALLBACK_WINCOMPONENTS = "direct3d=0,directsound=0,directmusic=0,directshow=0,directplay=0,vcrun2010=0,wmdecoder=0"
    const val DEFAULT_CURSOR_THEME = "BreezeX-Light"
    const val DEFAULT_CURSOR_SIZE = "24"

    const val STARTUP_SELECTION_NORMAL: Byte = 0
    const val STARTUP_SELECTION_ESSENTIAL: Byte = 1
    const val STARTUP_SELECTION_AGGRESSIVE: Byte = 2
}
