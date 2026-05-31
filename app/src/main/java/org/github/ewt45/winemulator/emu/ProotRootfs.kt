package org.github.ewt45.winemulator.emu

import kotlinx.serialization.json.Json
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Consts
import java.io.File
import java.nio.charset.StandardCharsets


class ProotRootfs {
    data class UserInfo(
        val name: String,
        val uid: Long,
        val gid: Long,
        val home: String,
        val shell: String,
    ) {
        companion object {
            val ROOT = UserInfo("root", 0L, 0L, "/root", "/bin/sh")
        }
    }


    companion object {
        /** 获取一个rootfs文件夹内/etc/passwd 中存储的用户信息。按用户名字母顺序排序 */
        fun getUserInfos(rootfs: File): List<UserInfo> {
            var returnValue:List<UserInfo>
            try {
                returnValue = FileUtils.readLines(File(rootfs, "/etc/passwd"), StandardCharsets.UTF_8).mapNotNull { line ->
                    line.split(":").takeIf { it.size == 7 }?.let {
                        val uid = it[2].toLong()
                        if (uid < 1000L && uid != 0L) return@let null
                        if (uid == 65534L) return@let null
                        return@let UserInfo(it[0], uid, it[3].toLong(), it[5], it[6])
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                returnValue = listOf()
            }
            if (returnValue.find { it.name == "root" } == null)
                returnValue += (UserInfo.ROOT)
            //proot-distro 把termux作为用户加进去了。在别的app里用不了所以不显示
            return returnValue.filter { !it.name.startsWith("aid_") }.sortedBy { it.name }
        }

        /**
         * 从/.emuconf 获取当前选择的文件夹名称
         */
        fun getCurrentSelectUser(name:String) {
            TODO("目前是写在了datastore里（json存储）")
        }

        /**
         * 同 [getPreferredUser](String?, List)
         * @param rootfsName 作为 [Consts.Pref.Local.rootfs_login_user_json] 的map的key 获取存储的user名。不能为current
         */
        suspend fun getPreferredUser(rootfsName: String):UserInfo  {
            if (rootfsName == Consts.rootfsCurrDir.name) throw IllegalArgumentException("用于搜索的 rootfsName 不能为 'current'")
            val userMap: Map<String, String> = Json.decodeFromString(Consts.Pref.Local.rootfs_login_user_json.get())
            return getPreferredUser(userMap[rootfsName], getUserInfos(File(Consts.rootfsAllDir,rootfsName)))
        }

        /**
         * 获取某rootfs的应该使用的登陆用户名。
         * 首先从本地json中读取，如果没有，则优先返回非root用户。最后选项是root用户
         * @param lastSelectedUserName 本地存储的该rootfs对应的默认用户名，可能为null（若没存过）
         * @param allUsers 该rootfs全部可选的用户列表
         */
        fun getPreferredUser(lastSelectedUserName: String?, allUsers: List<UserInfo>): UserInfo = allUsers.run {
            var foundInfo = find { info -> info.name == lastSelectedUserName }
            if (foundInfo == null) foundInfo = find { info -> info.name != "root" }
            if (foundInfo == null) foundInfo = find { info -> info.name == "root" }
            if (foundInfo == null) foundInfo = UserInfo.ROOT
            return foundInfo
        }
    }
}