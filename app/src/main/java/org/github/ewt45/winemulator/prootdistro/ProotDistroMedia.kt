package org.github.ewt45.winemulator.prootdistro

/**
 * OCI / Docker manifest & blob mediaType 字符串,
 * 跟 `proot_distro/helpers/docker/media.py` 一致。
 */
object ProotDistroMedia {
    const val OCI_MANIFEST_MEDIA = "application/vnd.oci.image.manifest.v1+json"
    const val OCI_CONFIG_MEDIA = "application/vnd.oci.image.config.v1+json"
    const val OCI_LAYER_MEDIA = "application/vnd.oci.image.layer.v1.tar+gzip"
    const val OCI_INDEX_MEDIA = "application/vnd.oci.image.index.v1+json"

    const val DOCKER_MANIFEST_LIST_MEDIA =
        "application/vnd.docker.distribution.manifest.list.v2+json"
    const val DOCKER_MANIFEST_MEDIA =
        "application/vnd.docker.distribution.manifest.v2+json"
}