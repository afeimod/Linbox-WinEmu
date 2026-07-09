package org.github.ewt45.winemulator.prootdistro

import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import java.io.File

/**
 * 参考 `proot_distro/helpers/rootfs.py`——
 * 解压完 rootfs 之后做收尾,让 proot 启动后能正常工作。
 */
object ProotDistroRootfsFix {

    /** 写一个最小的 /etc/resolv.conf */
    fun writeResolvConf(rootfs: File) {
        val path = File(rootfs, "etc/resolv.conf")
        try { if (path.exists()) path.delete() } catch (_: Throwable) {}
        path.parentFile?.mkdirs()
        path.writeText(
            "nameserver ${ProotDistro.DEFAULT_PRIMARY_NS}\n" +
            "nameserver ${ProotDistro.DEFAULT_SECONDARY_NS}\n"
        )
    }

    /** 写一个最小的 /etc/hosts */
    fun writeHosts(rootfs: File) {
        val path = File(rootfs, "etc/hosts")
        try { if (path.exists()) path.delete() } catch (_: Throwable) {}
        path.parentFile?.mkdirs()
        path.writeText(
            """
            # IPv4.
            127.0.0.1   localhost.localdomain localhost

            # IPv6.
            ::1         localhost.localdomain localhost ip6-localhost ip6-loopback
            fe00::0     ip6-localnet
            ff00::0     ip6-mcastprefix
            ff02::1     ip6-allnodes
            ff02::2     ip6-allrouters
            ff02::3     ip6-allhosts
            """.trimIndent() + "\n"
        )
    }

    /** 在 passwd/group/shadow 末尾追加当前 Termux 安卓 UID/GID 条目 */
    fun registerAndroidIds(rootfs: File) {
        // 先把 4 个文件设为可写 (镜像里它们可能是 0644)
        for (p in listOf("etc/passwd", "etc/shadow", "etc/group", "etc/gshadow")) {
            val f = File(rootfs, p)
            if (f.exists()) {
                try {
                    f.setReadable(true, false)
                    f.setWritable(true, false)
                } catch (_: Throwable) {}
            }
        }

        val uid: Int
        val gid: Int
        val username: String
        try {
            uid = android.os.Process.myUid()
            // username: 用 uid 的十六进制简化,跟 proot-distro 一样
            gid = uid  // 简单处理:用户主 gid = uid
            username = "u${uid % 100000}_a"  // 避免冲突,简单命名
        } catch (_: Throwable) {
            return
        }

        val passwdFile = File(rootfs, "etc/passwd")
        val shadowFile = File(rootfs, "etc/shadow")
        val groupFile = File(rootfs, "etc/group")
        val gshadowFile = File(rootfs, "etc/gshadow")

        try {
            passwdFile.appendText("aid_$username:x:$uid:$gid:Termux:/:/sbin/nologin\n")
        } catch (_: Throwable) {}
        try {
            shadowFile.appendText("aid_$username:*:18446:0:99999:7:::\n")
        } catch (_: Throwable) {}

        try {
            groupFile.appendText("aid_$username:x:$gid:root,aid_$username\n")
        } catch (_: Throwable) {}
        try {
            if (gshadowFile.exists()) {
                gshadowFile.appendText("aid_$username:*::root,aid_$username\n")
            }
        } catch (_: Throwable) {}
    }

    /**
     * 写一组假的 /proc 文件,让 proot 内部的程序读取 /proc 时拿到合理值
     * (参考 proot-distro 的 setup_fake_sysdata)。
     */
    fun setupFakeSysdata(rootfs: File) {
        for (d in listOf("proc", "sys", "sys/.empty")) {
            val f = File(rootfs, d)
            if (!f.exists()) f.mkdirs()
            Utils.chmod(f, "700")
        }
        writeIfNotExists(File(rootfs, "proc/.loadavg"), "0.12 0.07 0.02 2/165 765")
        writeIfNotExists(File(rootfs, "proc/.version"), "Linux version ${ProotDistro.DEFAULT_FAKE_KERNEL_RELEASE} (proot@termux) (gcc (GCC) 12.2.1 20230201, GNU ld (GNU Binutils) 2.40) ${ProotDistro.DEFAULT_FAKE_KERNEL_VERSION}")
        writeIfNotExists(File(rootfs, "proc/.uptime"), "124.08 932.80")
        // /proc/stat 给一个静态快照,大多数程序只读不解析严格格式
        writeIfNotExists(File(rootfs, "proc/.stat"), DEFAULT_PROC_STAT)
        // 注:这里不写 vmstat 等大数据,等真有程序读再补
    }

    private fun writeIfNotExists(f: File, content: String) {
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
    }

    private val DEFAULT_PROC_STAT = """
        cpu  1957 0 2877 93280 262 342 254 87 0 0
        cpu0 31 0 226 12027 82 10 4 9 0 0
        cpu1 45 0 664 11144 21 263 233 12 0 0
        cpu2 494 0 537 11283 27 10 3 8 0 0
        cpu3 359 0 234 11723 24 26 5 7 0 0
        intr 127541 38 290 0 0 0 0 4 0 1 0 0 25329 258 0 5777 277 0 0 0 0 0 0 0 0
        ctxt 140223
        btime 1680020856
        processes 772
        procs_running 2
        procs_blocked 0
        softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
    """.trimIndent()
}