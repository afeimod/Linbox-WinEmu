package org.github.ewt45.winemulator.prootdistro

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.ui.components.TaskReporter
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 参考 `proot_distro/commands/install.py` 的顶层 install 命令。
 *
 * 给定 image ref (`ubuntu`, `debian`, `ubuntu:24.04`, `kalilinux/kali-rolling`),
 * 拉 manifest → 选 arch → 下载 layers (用 sha256 校验) →
 * 解压到 rootfs 目录 → 写 resolv.conf / hosts / 注册 Android UID /
 * 写假 /proc 数据 → 写 manifest.json。
 *
 * 容器目录约定 (跟 linbox 现有布局保持一致):
 *   files/rootfs/<alias>/...
 * 不像原版 proot-distro 那样用 files/containers/<name>/rootfs,
 * 这样现有 ProotRootfs.getUserInfos 等逻辑不需要改动。
 */
object ProotDistroInstaller {

    private const val TAG = "ProotDistroInstaller"

    /** 安装结果 */
    data class InstallResult(
        val rootfsDir: File,
        val imageRef: String,
        val alias: String,
        val arch: String,
    )

    /**
     * 安装一个 Docker/OCI 镜像到本地 rootfs。
     *
     * @param imageRef 类似 `ubuntu`, `ubuntu:24.04`, `kalilinux/kali-rolling`
     * @param customName 用户指定的容器名 (alias),null 时从 imageRef 派生
     * @param overrideArch 强制使用某个 arch (null = 自动取本机 CPU)
     * @param insecure 跳过 TLS 证书校验 (不推荐)
     * @param reporter 进度报告器
     */
    suspend fun install(
        imageRef: String,
        customName: String? = null,
        overrideArch: String? = null,
        insecure: Boolean = false,
        reporter: TaskReporter? = null,
    ): InstallResult = withContext(Dispatchers.IO) {
        // 1. arch
        val deviceArch = ProotDistroArch.getDeviceCpuArch()
        val distArch: String = overrideArch
            ?.let { ProotDistroArch.normalize(it) ?: throw IllegalArgumentException("unknown architecture '$it'") }
            ?: deviceArch

        // 2. 解析 image ref → 派生 alias
        val alias = customName?.takeIf { it.isNotBlank() }
            ?: ProotDistroRefs.deriveAlias(imageRef)
        ProotDistroNames.requireValidName(alias)
        val rootfsDir = File(Consts.rootfsAllDir, alias)
        val existing = rootfsDir.listFiles()
        if (rootfsDir.exists() && existing != null && existing.isNotEmpty()) {
            throw IllegalStateException(
                "container '$alias' already exists. " +
                "Use a different name, or remove the existing one."
            )
        }

        reporter?.msg("正在从 $imageRef 安装 $alias (arch=$distArch)...")
        reporter?.progress(0f)

        try {
            rootfsDir.mkdirs()
            reporter?.msg("step 1/4: 拉取 manifest + 下载所有 layer")

            // 3. 拉 manifest,得到 layer 列表
            val (manifest, imageConfig, baseUrl, token) =
                pullImage(imageRef, rootfsDir, distArch, insecure, reporter)
            reporter?.msg("step 2/4: 所有 layer 已下载并解压到 ${rootfsDir.name}/")

            // 4. 写 manifest.json
            reporter?.msg("step 3/4: 写 rootfs manifest.json")
            saveContainerManifest(alias, imageRef, distArch, manifest, imageConfig)

            // 5. 后处理 rootfs
            reporter?.msg("step 4/4: 后处理 rootfs (resolv.conf / hosts / Android UID / 假 /proc)")
            if (File(rootfsDir, "etc").isDirectory) {
                reporter?.msg("  写入 /etc/resolv.conf / /etc/hosts ...")
                ProotDistroRootfsFix.writeResolvConf(rootfsDir)
                ProotDistroRootfsFix.writeHosts(rootfsDir)
                if (File(rootfsDir, "etc/passwd").isFile) {
                    reporter?.msg("  注册 Android UID/GID ...")
                    ProotDistroRootfsFix.registerAndroidIds(rootfsDir)
                }
            } else {
                reporter?.msg("  (跳过: rootfs 中找不到 etc/ 目录,layer 似乎没解开?)")
            }
            reporter?.msg("  写入假 /proc 数据 ...")
            ProotDistroRootfsFix.setupFakeSysdata(rootfsDir)

            // 6. 给一个 .alias 文件方便在 linbox 列表里识别
            Utils.Rootfs.setAlias(rootfsDir, alias)

            reporter?.msg("安装完成: ${rootfsDir.absolutePath}")
            reporter?.progress(1f)
            InstallResult(rootfsDir, imageRef, alias, distArch)
        } catch (e: Throwable) {
            // 失败时, 只清掉 rootfs 半成品, oci_layers/ 缓存保留以便重试时复用
            try {
                if (rootfsDir.exists()) {
                    FileUtils.deleteDirectory(rootfsDir)
                }
            } catch (_: Throwable) {}
            // 在抛出前在 msg 末尾追加线索, 方便快速判断错误类型
            e.message?.let { msg ->
                when {
                    msg.contains("integrity check failed", ignoreCase = true) ->
                        android.util.Log.e("ProotDistroInstaller", "layer SHA-256 mismatch: $msg", e)
                    msg.contains("manifest", ignoreCase = true) && msg.contains("not found", ignoreCase = true) ->
                        android.util.Log.e("ProotDistroInstaller", "manifest/image 404: $msg", e)
                    msg.contains("Network error", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true) ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("UnknownHost", ignoreCase = true) ->
                        android.util.Log.e("ProotDistroInstaller", "network error: $msg", e)
                }
            }
            throw e
        }
    }

    // ----------------------------------------------------------------
    // 下面是参考 `proot_distro/helpers/docker/pull.py` 的核心流水线
    // ----------------------------------------------------------------

    private suspend fun pullImage(
        imageRef: String,
        rootfsDir: File,
        arch: String,
        insecure: Boolean,
        reporter: TaskReporter?,
    ): PullResult {
        val parsed = ProotDistroRefs.parseImageRef(imageRef)
        val registry = parsed.registry
        val repo = parsed.repo
        val tag = parsed.tag

        // 准备缓存目录
        ProotDistro.layerCacheDir.mkdirs()
        ProotDistro.manifestCacheDir.mkdirs()

        // 1. 查 manifest 缓存
        val cached = loadManifestCache(imageRef, arch)
        var manifest: JSONObject?
        var imageConfig: JSONObject
        var token: String
        var baseUrl: String

        if (cached != null) {
            manifest = cached.manifest
            imageConfig = cached.imageConfig
            // 校验所有 layer 是否已缓存,如有缺失则只重新拉 token
            val layers = manifest.optJSONArray("layers")
            if (layers != null && allLayersCached(layers)) {
                reporter?.msg("镜像已缓存,无需网络下载 (layers=${layers.length()})")
                token = ""
                baseUrl = if (registry.isEmpty()) ProotDistro.DOCKER_HUB_REGISTRY else "https://$registry"
            } else {
                reporter?.msg("部分 layer 缺失,重新认证 registry...")
                val auth = ProotDistroAuth.getAuthToken(repo, registry, reporter, insecure)
                token = auth.token
                baseUrl = auth.baseUrl
            }
        } else {
            reporter?.msg("  → 认证 registry ${if (registry.isEmpty()) "(Docker Hub)" else registry}")
            val auth = ProotDistroAuth.getAuthToken(repo, registry, reporter, insecure)
            token = auth.token
            baseUrl = auth.baseUrl
            reporter?.msg("  → 获取 manifest $imageRef (arch=$arch)")
            val resolved = resolveSingleManifest(repo, tag, token, baseUrl, insecure, arch, imageRef, reporter)
            manifest = resolved.manifest
            imageConfig = resolved.imageConfig
            // 即使后面 layer 失败, manifest 已拿到, 保存以便重试
            try { saveManifestCache(imageRef, arch, manifest, imageConfig, repo) }
            catch (e: Throwable) { reporter?.msg("  ! 保存 manifest 缓存失败: ${e.message}") }
        }

        // manifest 走两个分支都已被赋值,不可能为 null
        val safeManifest = manifest!!
        val layers = safeManifest.optJSONArray("layers")
            ?: throw RuntimeException("manifest for $imageRef has no layers")
        val nLayers = layers.length()
        if (nLayers == 0) {
            throw RuntimeException("manifest for $imageRef contains no filesystem layers")
        }

        // 2. 逐层下载+解压
        reporter?.msg("  → 共 $nLayers 个 layer")
        // 先报一下已缓存多少个
        val cachedCount = (0 until nLayers).count {
            val d = layers.getJSONObject(it).optString("digest")
            ProotDistroCache.layerCachePath(d).isFile
        }
        if (cachedCount > 0) {
            reporter?.msg("    本地 oci_layers 缓存中已有 $cachedCount / $nLayers 个 layer")
        }
        for (i in 0 until nLayers) {
            val layer = layers.getJSONObject(i)
            val digest = layer.optString("digest")
            val mediaType = layer.optString("mediaType", "")
            if (mediaType.contains("zstd", ignoreCase = true)) {
                throw RuntimeException(
                    "Layer ${i + 1}/$nLayers uses zstd compression which is " +
                    "not supported by Android tar tools. Try a different " +
                    "image tag that ships gzip-compressed layers."
                )
            }
            val shortId = digest.substringAfter(':').take(12)
            val cached = ProotDistroCache.layerCachePath(digest)
            if (cached.isFile) {
                reporter?.msg("  [${i + 1}/$nLayers] $shortId 已缓存 (${cached.length() / 1024} KB),解压中...")
                applyLayer(cached, rootfsDir, reporter)
                reporter?.msg("  [${i + 1}/$nLayers] $shortId 解压完成")
            } else {
                reporter?.msg("  [${i + 1}/$nLayers] $shortId 下载中 (${layer.optLong("size", 0) / 1024} KB)...")
                val downloaded = downloadBlob(repo, digest, token, baseUrl, insecure, reporter)
                reporter?.msg("  [${i + 1}/$nLayers] $shortId 下载完成, 解压中...")
                applyLayer(downloaded, rootfsDir, reporter)
                reporter?.msg("  [${i + 1}/$nLayers] $shortId 解压完成")
            }
        }

        return PullResult(manifest, imageConfig, baseUrl, token)
    }

    private data class PullResult(
        val manifest: JSONObject,
        val imageConfig: JSONObject,
        val baseUrl: String,
        val token: String,
    )

    private data class CachedManifest(
        val manifest: JSONObject,
        val imageConfig: JSONObject,
    )

    private data class ResolvedManifest(
        val manifest: JSONObject,
        val imageConfig: JSONObject,
    )

    private fun loadManifestCache(imageRef: String, arch: String): CachedManifest? {
        val file = ProotDistroCache.manifestCachePath(imageRef, arch)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val manifest = json.optJSONObject("manifest") ?: return null
            val imageConfig = json.optJSONObject("image_config") ?: JSONObject()
            CachedManifest(manifest, imageConfig)
        } catch (_: Throwable) { null }
    }

    private fun saveManifestCache(
        imageRef: String, arch: String,
        manifest: JSONObject, imageConfig: JSONObject, repo: String,
    ) {
        val file = ProotDistroCache.manifestCachePath(imageRef, arch)
        file.parentFile?.mkdirs()
        val payload = JSONObject().apply {
            put("manifest", manifest)
            put("repo", repo)
            put("image_config", imageConfig)
        }
        file.writeText(payload.toString())
    }

    private suspend fun resolveSingleManifest(
        repo: String, tag: String, token: String, baseUrl: String,
        insecure: Boolean, arch: String, imageRef: String,
        reporter: TaskReporter?,
    ): ResolvedManifest {
        val headers = mutableMapOf(
            "User-Agent" to ProotDistro.userAgent,
            "Accept" to listOf(
                ProotDistroMedia.OCI_INDEX_MEDIA,
                ProotDistroMedia.DOCKER_MANIFEST_LIST_MEDIA,
                ProotDistroMedia.OCI_MANIFEST_MEDIA,
                ProotDistroMedia.DOCKER_MANIFEST_MEDIA,
            ).joinToString(", "),
        )
        if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"

        val url = "$baseUrl/v2/$repo/manifests/$tag"
        val body = ProotDistroHttp.httpGet(url, headers, reporter, insecure)
        val data = JSONObject(body)

        // 是 manifest list / index? 选 arch 匹配的 child
        val mediaType = data.optString("mediaType", "")
        if (mediaType == ProotDistroMedia.DOCKER_MANIFEST_LIST_MEDIA ||
            mediaType == ProotDistroMedia.OCI_INDEX_MEDIA ||
            data.has("manifests")) {
            val (dockerArch, dockerVariant) = ProotDistroArch.toDocker(arch)
            val entries = data.optJSONArray("manifests")
                ?: throw RuntimeException("manifest list has no manifests")
            val target = pickPlatform(entries, dockerArch, dockerVariant, imageRef)
                ?: throw RuntimeException(
                    "No image found for architecture '$arch' in '$imageRef'. " +
                    "Visit https://hub.docker.com to look for alternatives."
                )
            val childDigest = target.optString("digest")
            reporter?.msg("选取 $arch manifest...")
            val childHeaders = headers.toMutableMap()
            val childBody = ProotDistroHttp.httpGet(
                "$baseUrl/v2/$repo/manifests/$childDigest",
                childHeaders, reporter, insecure,
            )
            return ResolvedManifest(JSONObject(childBody), JSONObject())
        }

        // 是单个 manifest,拉 config blob
        val cfgDigest = data.optJSONObject("config")?.optString("digest", "").orEmpty()
        var imageConfig = JSONObject()
        if (cfgDigest.isNotEmpty()) {
            try {
                val cfgHeaders = mutableMapOf("User-Agent" to ProotDistro.userAgent)
                if (token.isNotEmpty()) cfgHeaders["Authorization"] = "Bearer $token"
                val cfgBody = ProotDistroHttp.httpGet(
                    "$baseUrl/v2/$repo/blobs/$cfgDigest", cfgHeaders, reporter, insecure,
                )
                imageConfig = JSONObject(cfgBody)
            } catch (_: Throwable) {
                // config 拉不到不影响解压
            }
        }
        return ResolvedManifest(data, imageConfig)
    }

    private fun pickPlatform(
        entries: org.json.JSONArray,
        arch: String,
        variant: String,
        imageRef: String,
    ): JSONObject? {
        // 1. 精确匹配:arch + variant
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val plat = e.optJSONObject("platform") ?: continue
            if (plat.optString("os", "linux") != "linux") continue
            if (plat.optString("architecture") != arch) continue
            if (variant.isNotEmpty() && plat.optString("variant", "") !in listOf(variant, "")) continue
            return e
        }
        // 2. variant-agnostic fallback
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val plat = e.optJSONObject("platform") ?: continue
            if (plat.optString("os", "linux") != "linux") continue
            if (plat.optString("architecture") != arch) continue
            return e
        }
        return null
    }

    private fun allLayersCached(layers: org.json.JSONArray): Boolean {
        for (i in 0 until layers.length()) {
            val digest = layers.getJSONObject(i).optString("digest")
            if (!ProotDistroCache.layerCachePath(digest).isFile) return false
        }
        return true
    }

    /**
     * 下载单个 layer blob 到 cache,流式校验 sha256。
     */
    private suspend fun downloadBlob(
        repo: String, digest: String, token: String, baseUrl: String,
        insecure: Boolean, reporter: TaskReporter?,
    ): File {
        ProotDistroCache.validateDigest(digest)
        val dest = ProotDistroCache.layerCachePath(digest)
        if (dest.isFile) return dest

        val (algo, expectedHex) = digest.split(":", limit = 2)
        if (algo.lowercase() != "sha256") {
            throw RuntimeException("unsupported digest algorithm '$algo' (only sha256)")
        }

        val url = "$baseUrl/v2/$repo/blobs/$digest"
        val headers = mutableMapOf("User-Agent" to ProotDistro.userAgent)
        if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"

        val tmpFile = File(dest.parentFile, dest.name + ".tmp")
        ProotDistroHttp.retry(reporter, "下载 layer ${digest.substringAfter(':').take(12)}") {
            // 直接用底层 HttpURLConnection 才能在写盘同时计算 sha256
            val conn = ProotDistroHttp.openConnection(url, headers, insecure)
            try {
                if (conn.responseCode !in 200..299) {
                    throw RuntimeException("HTTP ${conn.responseCode} ${conn.responseMessage}")
                }
                val totalSize = conn.contentLengthLong
                val md = MessageDigest.getInstance("SHA-256")
                tmpFile.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            md.update(buffer, 0, n)
                            done += n
                            if (totalSize > 0 && reporter != null) {
                                val percent = (done * 100 / totalSize).toInt().coerceIn(0, 100)
                                reporter.progress(percent / 100f)
                            }
                        }
                    }
                }
                val actualHex = md.digest().joinToString("") { "%02x".format(it) }
                if (actualHex != expectedHex.lowercase()) {
                    throw RuntimeException(
                        "layer integrity check failed for $digest: " +
                        "expected $expectedHex, got $actualHex"
                    )
                }
                if (dest.exists()) dest.delete()
                if (!tmpFile.renameTo(dest)) {
                    tmpFile.copyTo(dest, overwrite = true)
                    tmpFile.delete()
                }
            } finally {
                conn.disconnect()
                if (tmpFile.exists()) tmpFile.delete()
            }
        }
        return dest
    }

    private fun applyLayer(layerPath: File, rootfsDir: File, reporter: TaskReporter?) {
        ProotDistroExtract.extractTarToRootfs(
            archive = layerPath,
            rootfsDir = rootfsDir,
            handleWhiteouts = true,
            reporter = reporter,
        )
    }

    private fun saveContainerManifest(
        alias: String,
        imageRef: String,
        arch: String,
        manifest: JSONObject,
        imageConfig: JSONObject,
    ) {
        val manifestFile = File(File(Consts.rootfsAllDir, alias), ".proot-distro-manifest.json")
        val payload = JSONObject().apply {
            put("image_ref", imageRef)
            put("arch", arch)
            put("manifest", manifest)
            put("image_config", imageConfig)
        }
        runCatching { manifestFile.writeText(payload.toString(2)) }
            .onFailure { Log.w(TAG, "could not write manifest.json: ${it.message}") }
    }
}