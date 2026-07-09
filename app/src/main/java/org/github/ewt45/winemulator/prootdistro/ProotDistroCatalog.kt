package org.github.ewt45.winemulator.prootdistro

/**
 * proot-distro 官方支持的发行版列表 (Docker Hub 上的镜像)。
 * 用户在 UI 上点选,直接拉取对应镜像即可。
 *
 * 添加新条目时只需在 [entries] 里追加。字段:
 *   displayName  显示名
 *   imageRef     默认 image ref (tag),可被用户改写
 *   description  简介
 */
data class ProotDistroEntry(
    val displayName: String,
    val imageRef: String,
    val description: String,
)

object ProotDistroCatalog {

    val entries: List<ProotDistroEntry> = listOf(
        ProotDistroEntry(
            "Ubuntu", "ubuntu:24.04",
            "Debian 系最流行的桌面/服务器发行版",
        ),
        ProotDistroEntry(
            "Debian", "debian:bookworm",
            "稳定、安全、自由的通用发行版 (Debian 12)",
        ),
        ProotDistroEntry(
            "Alpine", "alpine:latest",
            "极小的 musl libc 发行版,~5MB 基础",
        ),
        ProotDistroEntry(
            "Arch Linux", "archlinux:latest",
            "滚动更新,always bleeding edge",
        ),
        ProotDistroEntry(
            "Fedora", "fedora:latest",
            "Red Hat 系的社区上游,默认 GNOME",
        ),
        ProotDistroEntry(
            "Kali Linux", "kalilinux/kali-rolling:latest",
            "Debian 派生,内置大量安全/渗透测试工具",
        ),
        ProotDistroEntry(
            "openSUSE", "opensuse/tumbleweed:latest",
            "滚动更新 RPM 系",
        ),
        ProotDistroEntry(
            "Void Linux", "voidlinux/voidlinux:latest",
            "独立发行版,使用 runit + xbps",
        ),
        ProotDistroEntry(
            "CentOS", "quay.io/centos/centos:latest",
            "CentOS Stream 替代品 (从 quay.io 拉取)",
        ),
        ProotDistroEntry(
            "Oracle Linux", "container-registry.oracle.com/os/oraclelinux:latest",
            "Oracle 维护的 RHEL 兼容发行版",
        ),
    )
}