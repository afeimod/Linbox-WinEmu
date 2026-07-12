package org.github.ewt45.winemulator.deinstaller

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.emu.ProotHelper
import org.github.ewt45.winemulator.emu.ProotRootfs
import java.io.File

/**
 * 在指定 rootfs 内执行一条 shell 命令, 实时回调每行输出.
 *
 * 为什么不直接复用 [org.github.ewt45.winemulator.emu.Proot.attach]?
 *   - attach() 内部组好的命令已经把容器启动到 login shell, 命令列是
 *     `sh -c "<整个 proot 命令串>"`, 没法在 proot 层额外加 `--bind`.
 *   - 我们要 `--bind=<filesDir>/de-installer:/de-installer`,
 *     所以这里**自己组** proot 命令 (照抄 attach() 的核心参数).
 *
 * 复用的项目组件:
 *   - [Consts.prootBin]        : proot 二进制
 *   - [Consts.tmpDir]          : PROOT_TMP_DIR
 *   - [ProotHelper]            : setup_fake_data() / DEFAULT_FAKE_KERNEL_VERSION
 *   - [ProotRootfs]            : getPreferredUser() 推导 uid/gid/home
 *   - [Consts.Pref]            : proot_bool_options, general_shared_ext_path, general_rootfs_lang
 */
object ContainerExec {

    private const val TAG = "ContainerExec"

    fun interface LineCallback {
        fun onLine(line: String)
    }

    data class ExecResult(
        val exitCode: Int,
        /** 命令完整 stdout+stderr, 最后 50KB */
        val fullOutput: String,
    )

    /**
     * 在 [rootfs] 里跑一条 shell 命令.
     *
     * @param rootfs       目标 rootfs
     * @param command      容器内 shell 字符串 (会 sh -c 起来)
     * @param extraBinds   额外要 bind 进容器的 (hostPath, containerPath) 对
     * @param onLine       行回调
     */
    suspend fun run(
        rootfs: File,
        command: String,
        extraBinds: List<Pair<String, String>> = emptyList(),
        onLine: LineCallback? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        val tmpdir = Consts.tmpDir
        val lang = Consts.Pref.general_rootfs_lang.get()
        val userInfo = ProotRootfs.getPreferredUser(rootfs.canonicalFile.name)
        val l2sDir = File(rootfs, ".l2s")
        l2sDir.mkdirs()
        runCatching { Utils.chmod(l2sDir, "755") }
        ProotHelper.setup_fake_data()

        // ---- 组 proot 命令 (基本照抄 Proot.attach) ----
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

        // 注入额外 bind (我们的 de-installer 目录)
        for ((host, cont) in extraBinds) {
            prootCmd.add("--bind=$host:$cont")
        }

        // 用户共享目录 (照搬 attach)
        prootCmd.addAll(
            Consts.Pref.general_shared_ext_path.get().map { bindPath ->
                File(rootfs, bindPath).runCatching {
                    takeIf { org.apache.commons.io.FileUtils.isSymlink(it) }?.delete()
                }
                "--bind=$bindPath"
            }
        )

        // selinux 屏蔽
        prootCmd.add("--bind=${rootfs.absolutePath}/sys/.empty:/sys/fs/selinux")

        // 环境变量
        val envStr = listOf(
            "LANG=$lang",
            "HOME=${userInfo.home}",
            "USER=${userInfo.name}",
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )

        prootCmd.add("/usr/bin/env")
        prootCmd.add("-i")
        prootCmd.addAll(envStr)
        prootCmd.addAll(listOf("sh", "-c", command))

        val pb = ProcessBuilder(prootCmd)
            .directory(rootfs)
            .also {
                it.environment()["PROOT_TMP_DIR"] = tmpdir.absolutePath
                it.environment()["LD_PRELOAD"] = ""
            }
            .redirectErrorStream(true)

        Log.d(TAG, "exec in ${rootfs.name}: ${pb.command().joinToString(" ")}")

        val buffer = StringBuilder()
        try {
            val proc = pb.start()
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    buffer.appendLine(line)
                    onLine?.onLine(line)
                }
            }
            val code = proc.waitFor()
            ExecResult(code, buffer.toString().takeLast(50 * 1024))
        } catch (e: Throwable) {
            buffer.appendLine("[ContainerExec] error: ${e.message}")
            ExecResult(-1, buffer.toString())
        }
    }
}
