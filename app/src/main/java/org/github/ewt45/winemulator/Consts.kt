package org.github.ewt45.winemulator

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.system.Os
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlin.reflect.full.declaredMemberProperties

object Consts {
    private val TAG = "Consts"
    var isDebug = true
    lateinit var cacheDir: File

    /** 用于proot绑定 /tmp 的安卓路径 */
    lateinit var tmpDir: File

    /** 此文件夹内包含各种rootfs. files/rootfs */
    lateinit var rootfsAllDir: File

    /** 当前激活的rootfs, 应该为一个指向实际rootfs的软链接. files/rootfs/current */
    lateinit var rootfsCurrDir: File

    /** tx11 需要用到的xkb文件夹， rootfs可以安装libxkbcommon包来获取. 路径：[rootfsCurrDir]/usr/share/X11/xkb */
    lateinit var rootfsCurrXkbDir: File

    /** rootfs中的/tmp文件夹。启动proot前应将其变为[tmpDir]的符号链接。路径：[rootfsCurrDir]/tmp */
    lateinit var rootfsCurrTmpDir: File

    /** proot --link2symlink 时 要存放唯一真实文件的文件夹。路径：[rootfsCurrDir]/.l2s */
    lateinit var rootfsCurrL2sDir: File

    /** 容器启动时执行的初始化脚本 */
    lateinit var rootfsCurrStartSh: File

    /** 一个用于测试的alpine rootfs. files/rootfs/alpine-aarch64 */
    lateinit var alpineRootfsDir: File

    /** 在一个rootfs内部，存放模拟器配置相关的文件夹的名称。 */
    val rootfsEmuConfDirName: String = "/.emuconf"

    /** proot二进制文件. files/proot  */
    lateinit var prootBin: File

    /** 存储pulseaudio相关文件的文件夹 */
    lateinit var pulseDir: File

    /** 运行pulse时环境变量HOME设置为该路径，用于寻找~/.config 等 */
    lateinit var pulseHomeDir: File
    lateinit var pulseBin: File

    /** apk自身所在路径 */
    lateinit var apkFilePath: String

    /** 定义在assets中的默认值，此map中的值会优先于代码中的默认值生效。key为datastore的某个key, value为对应value */
    private lateinit var prefInAssets: Map<String, Any>

    object Ui {
        /** 最小化时的宽高dp值 */
        val minimizedIconSize = 36
    }

    /**
     * 用户偏好相关.
     * 如果assets中指定了默认值，会覆盖这里的默认值
     */
    object Pref {
        data class Item<T>(val key: Preferences.Key<T>, val default: T, val flow: Flow<T>) {
            /** 获取最新的值. 本地未存储时返回[default] */
            suspend fun get(): T = flow.first()
        }

        /** 全部设置项。用于批量操作 例如导出导入，重置 */
        val allItems by lazy { getAllPrefItems() }

        val general_resolution by item("general_resolution", "1280x720")
        val general_rootfs_lang by item("general_rootfs_lang", "zh_CN.utf8")
        val general_shared_ext_path by item("shared_ext_path", setOf("/storage/emulated/0/Download"))
        val proot_bool_options by item("proot_bool_options", setOf( "-L", "--link2symlink", "--sysvipc", "--kill-on-exit", /*"--root-id",*/))
        val proot_startup_cmd by item("proot_startup_cmd", "")

        // Input Controls Settings
        val inputcontrols_enabled by item("inputcontrols_enabled", false)
        val inputcontrols_profile_id by item("inputcontrols_profile_id", -1)
        val inputcontrols_opacity by item("inputcontrols_opacity", 0.4f)
        val inputcontrols_haptics by item("inputcontrols_haptics", true)
        
        // Theme Settings
        // 主题偏好: 0 = 跟随系统, 1 = 暗色主题, 2 = 亮色主题
        val general_theme_mode by item("general_theme_mode", 1)

        // X11 Settings - 触摸方式: 0=虚拟触控板, 1=模拟触摸, 2=触摸屏
        val x11_touch_mode by item("x11_touch_mode", 0)
        // X11 Settings - 屏幕方向: 10=跟随系统, 11=横屏, 12=竖屏, 13=反向横屏, 14=反向竖屏
        val x11_screen_orientation by item("x11_screen_orientation", 10)
        // X11 Settings - 显示缩放: 30-300
        val x11_display_scale by item("x11_display_scale", 100)
        // X11 Settings - 保持屏幕常亮
        val x11_keep_screen_on by item("x11_keep_screen_on", true)
        // X11 Settings - termux-x11 AAR 虚拟按键方向键修复开关 (对应 pref "hardwareKbdScancodesWorkaround")
        // 用户语义: true=打开修复(写到 AAR pref=false, 方向键正确); false=关闭(写到 AAR pref=true, AAR 默认行为)
        val x11_virtual_keys_fix_direction by item("x11_virtual_keys_fix_direction", true)
        // X11 Settings - 全屏模式
        val x11_fullscreen by item("x11_fullscreen", true)
        // X11 Settings - 使用刘海屏区域
        val x11_hide_cutout by item("x11_hide_cutout", true)
        // X11 Settings - PIP画中画模式
        val x11_pip_mode by item("x11_pip_mode", false)
        // X11 Settings - 分辨率: 格式为"宽x高"，如"1280x720"
        val x11_resolution by item("x11_resolution", "1280x720")

        /** 仅在此设备存储，不应用于导出导入。 */
        object Local {
            /** 当前使用的rootfs名，[Consts.rootfsAllDir]目录下的某个文件夹名，可能为空字符串或对应文件夹不存在 */
            val curr_rootfs_name by item("local_curr_rootfs_name", "")
            /** 记录当前存在的rootfs 如果要使用该rootfs, 应该登陆哪个用户。 存为json字符串。转换时应该变成一个map, key是rootfs文件夹名, value是用户名  */
            val rootfs_login_user_json by item("local_rootfs_login_user", "{}")
            /** 跳过权限申请 */
            val skip_permissions by item("skip_permissions", false)
        }

        /**
         * 初始化Item需要在读取assets之后，lazy的话 第一次用到Pref时Consts应该已经初始化好了吧。用lateinit的话还需要多写一行
         */
        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> item(name: String, default: T): Lazy<Item<T>> = lazy {
            val key: Preferences.Key<T> = when (T::class) {
                Set::class -> stringSetPreferencesKey(name)
                String::class -> stringPreferencesKey(name)
                Boolean::class -> booleanPreferencesKey(name)
                Int::class -> intPreferencesKey(name)
                Float::class -> floatPreferencesKey(name)
                Long::class -> longPreferencesKey(name)
                Double::class -> doublePreferencesKey(name)
                else -> throw IllegalArgumentException("Unsupported type: ${T::class.simpleName}")
            } as Preferences.Key<T>
            val finalDefault = (prefInAssets[name].takeIf { it is T } ?: default) as T
            return@lazy Item(key, finalDefault, dataStore.data.map { it[key] ?: finalDefault })
        }

        /** 反射获取全部设置项 */
        @Suppress("UNCHECKED_CAST")
        private fun getAllPrefItems(): List<Item<Any>> {
            return Pref::class.declaredMemberProperties
                .filter { it.returnType.classifier == Pref.Item::class }
                .mapNotNull { property -> property.call(Pref) as? Pref.Item<Any> }
        }
    }

    //TODO 将费时操作移到异步函数中
    /**
     * 初始化。使用前先调用一次
     */
    fun init(ctx: Context) {
        isDebug = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        cacheDir = ctx.cacheDir
        cacheDir.mkdirs()

        tmpDir = File(cacheDir, "tmp")
        tmpDir.mkdirs()
        Os.chmod(tmpDir.absolutePath, "777".toInt(8))

        val fileDir = ctx.filesDir
        rootfsAllDir = File(fileDir, "rootfs")
        rootfsAllDir.mkdirs()

        rootfsCurrDir = File(rootfsAllDir, "current")
        alpineRootfsDir = File(rootfsAllDir, "alpine-aarch64") //这个等解压的时候再创建吧
        rootfsCurrXkbDir = File(rootfsCurrDir, "usr/share/X11/xkb")
        rootfsCurrTmpDir = File(rootfsCurrDir, "tmp")
        rootfsCurrL2sDir = File(rootfsCurrDir, "/.l2s")
        rootfsCurrStartSh = File(rootfsCurrDir, "/.emuconf/start.sh")

        //proot从assets解压
        prootBin = File(fileDir, "proot")
        if (!prootBin.exists()) {
            Utils.streamCopy(ctx.assets.open("proot"), FileOutputStream(prootBin))
        }
        prootBin.setExecutable(true)

        pulseDir = File(ctx.filesDir, "pulseaudio")
        pulseHomeDir = File(pulseDir, "homedir")
        pulseBin = File(pulseDir, "pulseaudio")
        if (!pulseBin.exists()) {
            pulseDir.delete()
            Utils.Archive.decompressTarXz(ctx.assets.open("pulseaudio.tar.xz"), pulseDir)
            pulseHomeDir.mkdirs()
        }
        pulseBin.setExecutable(true)

        apkFilePath = ctx.applicationInfo.sourceDir

        //优先生效的用户偏好
        val prefInAssetsJson = IOUtils.toString(ctx.assets.open("preferences.json"), StandardCharsets.UTF_8)
        prefInAssets = Utils.Pref.deserializeFromJsonToMap(prefInAssetsJson)
    }
}




