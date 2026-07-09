package org.github.ewt45.winemulator.prootdistro

import java.io.File
import java.security.MessageDigest

/**
 * 参考 `proot_distro/helpers/docker/cache.py`。
 *
 * 管理 layer blob 和 manifest 的本地磁盘缓存:
 *   layer_cache_path(digest) -> cacheBaseDir/oci_layers/<algo_hex>
 *   manifest_cache_path(ref, arch) -> cacheBaseDir/oci_manifests/<sha>.json
 *
 * digest 用作文件名之前会先校验格式,防止恶意输入绕过缓存目录
 * (例如 "../foo:bar" 路径穿越)。
 */
object ProotDistroCache {

    /** OCI digest grammar (algorithm ":" encoded hex). */
    private val DIGEST_RE = Regex(
        "^[A-Za-z0-9]+(?:[+_.-][A-Za-z0-9]+)*:[A-Fa-f0-9]+$"
    )

    /** 校验 digest 格式,拒绝含路径分隔符 / 空段 / 异常字符串 */
    fun validateDigest(digest: String): String {
        if (!DIGEST_RE.matches(digest)) {
            throw RuntimeException("malformed digest: $digest")
        }
        return digest
    }

    /** layer blob 在磁盘上的路径 */
    fun layerCachePath(digest: String): File {
        validateDigest(digest)
        return File(ProotDistro.layerCacheDir, digest.replace(':', '_'))
    }

    /** manifest cache 的路径——key 是 canonical ref + arch 的 sha256 前 16 字节 */
    fun manifestCachePath(imageRef: String, arch: String): File {
        val canonical = canonicalKey(imageRef, arch)
        val key = sha256Hex(canonical).substring(0, 16)
        return File(ProotDistro.manifestCacheDir, "$key.json")
    }

    private fun canonicalKey(imageRef: String, arch: String): String {
        val parsed = ProotDistroRefs.parseImageRef(imageRef)
        val regPart = if (parsed.registry.isEmpty()) "" else "${parsed.registry}/"
        return "$regPart${parsed.repo}:${parsed.tag}_$arch"
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}