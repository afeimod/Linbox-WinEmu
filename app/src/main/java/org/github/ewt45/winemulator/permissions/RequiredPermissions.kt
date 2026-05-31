package org.github.ewt45.winemulator.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.MainEmuApplication
import org.github.ewt45.winemulator.Utils

enum class RequiredPermissions(
    val displayName: String,
    val description: String,
    val permission: String
) {
    Storage(
        "存储权限",
        "用于读取rootfs,执行文件等。",
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ),
    Notification(
        "通知权限",
        "用于发送通知。",
        Settings.ACTION_APP_NOTIFICATION_SETTINGS
    ), ;

    companion object {
        /** 检查当前权限情况 并返回未授予的权限列表*/
        fun getUnGrantedList() =
            RequiredPermissions.entries.mapNotNull {
                it.takeUnless { Utils.Permissions.isGranted(MainEmuApplication.i, it.permission) }
            }
    }
}