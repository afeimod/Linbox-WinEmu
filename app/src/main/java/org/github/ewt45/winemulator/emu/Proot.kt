package org.github.ewt45.winemulator.emu

import android.util.Log
import androidx.compose.ui.text.toLowerCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Consts.Pref.general_shared_ext_path
import org.github.ewt45.winemulator.Consts.Pref.proot_bool_options
import org.github.ewt45.winemulator.Consts.rootfsCurrL2sDir
import org.github.ewt45.winemulator.Utils.chmod
import org.github.ewt45.winemulator.emu.ProotHelper.DEFAULT_FAKE_KERNEL_VERSION
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 连接linux的终端。输入命令或获取输出
 */
class Proot {
    private val TAG = "Proot"

    companion object {
        /**上次执行proot时的完整命令, 仅用于显示，可能无法真正用于执行 */
        var lastTimeCmd = ""
    }

    suspend fun attach(): ProcessBuilder = withContext(Dispatchers.IO) {
        val rootfs = Consts.rootfsCurrDir
        val tmpdir = Consts.tmpDir
        val lang = Consts.Pref.general_rootfs_lang.get()

        //TODO 每次运行前清空tmp。不对不能在tx11启动后清空 不然和容器连不上了
//        tmpdir.deleteRecursively()
//        tmpdir.mkdirs()
//        chmod(tmpdir, "1777")

        rootfsCurrL2sDir.mkdirs()
        chmod(rootfsCurrL2sDir, "755")
//        ProotHelper.createStartSh()
        ProotHelper.setup_fake_data()
        editEtcLocaleGen(rootfs, lang)

        //proot命令的参数使用 大量参考Proot-Distro

        //登陆时使用指定用户名。优先使用非root用户。从/etc/passwd获取uid, gid, home, shell
        val userInfo = ProotRootfs.getPreferredUser(rootfs.canonicalFile.name)

        val prootCmd = mutableListOf(
            Consts.prootBin.absolutePath,
//            "--root-id", // root用户登录
            *proot_bool_options.get().toTypedArray(), // proot的一般参数。用户可能会修改。 默认 "-L","--link2symlink","--kill-on-exit","--sysvipc",
            "--kernel-release=$DEFAULT_FAKE_KERNEL_VERSION",
            "--rootfs=${rootfs.absolutePath}",
            "--change-id=${userInfo.uid}:${userInfo.gid}",
            "--cwd=${userInfo.home}",
            "--bind=${tmpdir.absolutePath}:/tmp",
            "--bind=${rootfs.absolutePath}/tmp:/dev/shm", //将tmp用作 /dev/shm， 这么做会有冲突吗
            "--bind=/sys",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/dev",
//            "--bind=/storage/emulated/0/Download",
        )

        //proot-distro里这三个好像无条件绑定的，但实际上不绑定也已经存在了
        File("/dev/stderr").takeIf { !it.exists() }?.let {
            prootCmd.add("--bind=/proc/self/fd/2:/dev/stderr")
        }
        File("/dev/stdout").takeIf { !it.exists() }?.let {
            prootCmd.add("--bind=/proc/self/fd/1:/dev/stdout")
        }
        File("/dev/stdin").takeIf { !it.exists() }?.let {
            prootCmd.add("--bind=/proc/self/fd/0:/dev/stdin")
        }

        ProotHelper.setup_fake_data()
        prootCmd.add("--bind=${rootfs.absolutePath}/sys/.empty:/sys/fs/selinux") //假装没有selinux
        prootCmd.addAll(
            mapOf(
                "/proc/.loadavg" to "/proc/loadavg",
                "/proc/.stat" to "/proc/stat",
                "/proc/.uptime" to "/proc/uptime",
                "/proc/.version" to "/proc/version",
                "/proc/.vmstat" to "/proc/vmstat",
                "/proc/.sysctl_entry_cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
                "/proc/.sysctl_inotify_max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
            ).mapNotNull { bindIfNotReadable(rootfs, it.key, it.value) })



        prootCmd.addAll(general_shared_ext_path.get().map { bindPath ->
            File(rootfs, bindPath).runCatching { takeIf { FileUtils.isSymlink(it) }?.delete() }
            "--bind=$bindPath"
        })


        val loginEnvs = EnvMap()
        //最先读取 etc/environment 里的变量。之后如果有想自己覆盖的，override=true就行了。如果放到后面读，可能会导致拼接而非覆盖，最终值出现问题
        readEtcEnvironment(rootfs, loginEnvs)
//        loginEnvs.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games") // etc/environment 里应该有
//        loginEnvs.put("LD_LIBRARY_PATH","/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf")
//        loginEnvs.put("LC_ALL", "en_US.UTF-8", true) //LC_ALL仅供调试用，覆盖LANG及所有LC_
        loginEnvs.put("LANG", lang, true)//覆盖未通过LC_ 指定的变量
        loginEnvs.put("HOME", userInfo.home, true)
        loginEnvs.put("USER", userInfo.name, true)
        loginEnvs.put("TMPDIR", "/tmp", true)
        loginEnvs.put("DISPLAY", ":13", true)
        loginEnvs.put("PULSE_SERVER", "tcp:127.0.0.1:4713", true)
        //安装了不知道什么？mesa-dri-gallium ? mesa-gles?之后，需要加这个参数否则xfce4不启动了（没输出也不退出）
//            "LIBGL_ALWAYS_SOFTWARE=1",

        prootCmd.addAll(
            listOf(
                "/usr/bin/env",
                "-i",
                *loginEnvs.toArray(),
                userInfo.shell, "-l", // -l: 交互式shell，-c: 执行某命令并退出
            )
        )


        val prootCmdProotPart = prootCmd.toMutableList()
        prootCmd.clear()
        //sh -c 之后应该用一个字符串 不应再分割了
        prootCmd.addAll(listOf("sh", "-c", prootCmdProotPart.joinToString(" "))) //umask 0022 ;//TODO umask先不设置了？
        lastTimeCmd = "sh -c \\\n" + prootCmdProotPart.joinToString(" \\\n")
        Log.d(TAG, "attach: 最终prootcmd=$lastTimeCmd")

        val processBuilder = ProcessBuilder(prootCmd)
            .directory(rootfs)
            .also {
                it.environment()["PROOT_TMP_DIR"] = Consts.tmpDir.absolutePath
                it.environment()["LD_PRELOAD"] = ""
                //FIXME 设置l2s_dir时 locale-gen无法正常工作。临时去掉。后续查明原因再加回来。
//                it.environment()["PROOT_L2S_DIR"] = rootfsCurrL2sDir.absolutePath // link2symlink 相关
            }
            .redirectErrorStream(true)

        return@withContext processBuilder
    }

    /**
     * 读取/etc/environment下的环境变量 并添加到 [envMap]
     */
    private fun readEtcEnvironment(rootfs: File, envMap: EnvMap) {
        try {
            for (l in File(rootfs, "/etc/environment").readLines()) {
                val line = l.trim()
                line.takeIf { !line.startsWith('#') && line.contains('=') }?.let {
                    val split = line.split("=", limit = 2)
                    envMap.put(split[0], split[1].trim('\"'))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 编辑/etc/locale.gen,后续会执行locale-gen 生成对应语言文件 */
    private fun editEtcLocaleGen(rootfs: File, targetLocale: String) {
        try {
            val file = File(rootfs, "/etc/locale.gen").takeIf { it.exists() } ?: return
            val regexCharNum = "[^a-zA-Z0-9]".toRegex()
            val lines = FileUtils.readLines(file, StandardCharsets.UTF_8).map {
                //遍历文件，找到对应语言那行，去掉开头注释（如果有）
                val uncommentLine = it.trimStart('#').trim()
                val locale = uncommentLine.split(' ').takeIf { parts -> parts.size == 2 }?.get(0) ?: return@map it
                val comp1 = locale.replace(regexCharNum, "").lowercase()
                val comp2 = targetLocale.replace(regexCharNum, "").lowercase()
                return@map if (comp1 == comp2) uncommentLine else it
            }
            FileUtils.writeLines(file, lines)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 如果文件存在且可以读取内容. 返回null [filePath]为相对rootfs的路径 */
    private fun File.takeIfCantRead(): File? {
        return try {
            takeUnless { it.exists() && it.canRead() }
        } catch (e: Exception) {
            this
        }
    }


    /**
     * 如果[bindTo]无法读取的话. 绑定 File(rootfsCurrDir, [bindFrom]):filePath.
     * @param bindTo 安卓上的绝对路径. 如果该文件不可读，则作为proot 绑定到的rootfs目标路径
     * @param bindFrom rootfs中某个文件的安卓绝对路径
     * @return --bind 的字符串，未绑定时返回null
     */
    private fun bindIfNotReadable(rootfs: File, bindFrom: String, bindTo: String): String? {
        return File(bindTo).takeIfCantRead()?.let { "--bind=${File(rootfs, bindFrom).absolutePath}:$bindTo" }
    }


}

class EnvMap {
    val map = mutableMapOf<String, String>()

    /**
     * 新增/更改环境变量。将value放在现有value之前。如果override为true则替换现有value
     */
    fun put(k: String, v: String, override: Boolean = false) {
        val k1 = k.trim()
        val v1 = v.trim()
        if (k1.contains("=")) Log.w("TAG", "key不应包含=: key=$k1  value=$v1")
        val oldV = map[k1]
        map[k1] = if (oldV != null && !override) "$v1:$oldV" else v1
    }

    fun get(k: String): String = map.getOrDefault(k, "")

    /** 返回一个数组，包含当前所有环境变量，每个元素是 字符串 k=v */
    fun toArray(): Array<String> = map.toList().map { "${it.first}=${it.second}" }.toTypedArray()
}