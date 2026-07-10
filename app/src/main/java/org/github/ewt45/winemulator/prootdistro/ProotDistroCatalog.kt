package org.github.ewt45.winemulator.prootdistro

/**
 * proot-distro 官方支持的发行版列表 (Docker Hub 上的镜像)。
 * 用户在 UI 上点选,直接拉取对应镜像即可。
 *
 * 添加新条目时只需在 [entries] 里追加。字段:
 *   displayName  显示名
 *   imageRef     默认 image ref (tag),可被用户改写
 *   description  简介
 *   source       "oci" (走 [ProotDistroInstaller.install] 拉 Docker/OCI layer)
 *                或 "tarball" (走 [ProotDistroInstaller.installFromTarball]
 *                直接下 rootfs tarball, 跳过 OCI registry)
 *   altImageRef  可选: 当 host 是 arm64 而 [imageRef] 不支持 arm64 时,
 *                自动换用这个 image ref (OCI mode 下生效)
 *   altSource    可选: 当 source="tarball" 且 host 不是 arm64 时,
 *                自动换用 altImageRef (OCI mode)
 *
 *   大多数条目只填 imageRef/description/source="oci", 其它默认 null。
 */
data class ProotDistroEntry(
    val displayName: String,
    val imageRef: String,
    val description: String,
    val source: String = "oci",
    val altImageRef: String? = null,
    val altSource: String? = null,
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
            // 官方 docker.io/library/archlinux 只有 x86_64 (没有 arm64),
            // 而且 archlinuxarm/aarch64 在 DaoCloud 同步镜像里不在白名单 (403)。
            // 因此在 arm64 设备上自动改走 [ProotDistroInstaller.installFromTarball] 路径,
            // 从清华/阿里云/网易镜像下载 ArchLinuxARM-aarch64-latest.tar.gz。
            "Arch Linux", "archlinux:latest",
            "滚动更新,always bleeding edge (x86_64)",
            source = "oci",
            altImageRef = "archlinuxarm-rootfs",
            altSource = "tarball",
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