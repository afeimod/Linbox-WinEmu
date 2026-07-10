package org.github.ewt45.winemulator.prootdistro

import org.json.JSONObject
import org.github.ewt45.winemulator.ui.components.TaskReporter

/**
 * 参考 `proot_distro/helpers/docker/transport.py` 的
 * `get_auth_token`——拉取 registry OAuth2 token。
 *
 * 同时支持国内镜像: 通过 [Registry] 配置对象,可以让 getAuthToken
 * 走 DaoCloud / USTC 等反代镜像 (国内不需要挂v即可下载)。
 *
 * 返回值是 (token, baseUrl):token 是空字符串表示该 registry
 * 不需要认证 (匿名访问);baseUrl 是后面所有 v2/ 请求要用的根地址。
 */
object ProotDistroAuth {

    data class AuthResult(
        val token: String,
        val baseUrl: String,
        /** 用的是哪个 registry 端点 (调试用) */
        val registry: String,
    )

    /**
     * 一个 registry / mirror 的完整配置。
     *
     * @param label 给人看的名字 (在日志/UI 显示, 如 "Docker Hub" / "DaoCloud")
     * @param baseUrl manifest + blob 请求的根地址 (如 "https://registry-1.docker.io")
     * @param tokenIssuer 直接构造 token URL 的 issuer (覆盖 401 challenge 解析)。
     *                     null = 走 WWW-Authenticate 解析 (标准 OCI 行为)。
     *                     非 null 时强制用这个 URL 换 token。
     */
    data class Registry(
        val label: String,
        val baseUrl: String,
        /**
         * 可选: 主动指定 token issuer URL (覆盖 401 challenge 解析)。
         * null = 走标准 OCI 流程 (按 WWW-Authenticate 头解析 realm)。
         * 一些国内镜像不返回标准 challenge, 需要这个。
         * `{repo}` 会被替换为仓库名 (如 "library/ubuntu")。
         */
        val tokenIssuer: String? = null,
        val isDockerHub: Boolean = false,
    )

    /**
     * 已知的反代镜像列表 (按优先级, 第一个优先)。
     *
     * - DaoCloud 镜像 (国内首选, 走 OCI 标准 challenge, 速度快, 国内不挂v即可拉)
     * - Docker Hub 官方 (国外 / 挂v 环境, 国外用户, 或 DaoCloud 某 tag 还没同步的兜底)
     *
     * DaoCloud 完全兼容 Docker v2 API (manifest 格式 / mediaType / token 流程),
     * 不用任何 tokenIssuer 就能在它上面走完整安装流程。
     *
     * 多个 mirror 会按顺序尝试, 第一个成功换到 token 的获胜。
     */
    val KNOWN_MIRRORS: List<Registry> = listOf(
        // DaoCloud (国内, 走第一, 国内不挂v即可拉)
        Registry("DaoCloud", "https://docker.m.daocloud.io"),
        // Docker Hub 官方 (国外 / 挂v 环境, DaoCloud 同步延迟时兜底)
        Registry("Docker Hub", "https://registry-1.docker.io", isDockerHub = true),
    )

    /**
     * 拉取 registry 的 base URL 和 Bearer token。
     *
     * @param repo 仓库名 (如 "library/ubuntu")
     * @param registryMirror 要试的镜像列表, 按顺序尝试, 第一个成功的获胜
     * @param insecure 是否跳过 TLS 证书校验
     */
    suspend fun getAuthToken(
        repo: String,
        registryMirror: List<Registry>,
        reporter: TaskReporter?,
        insecure: Boolean = false,
    ): AuthResult {
        var lastError: Throwable? = null
        for ((idx, reg) in registryMirror.withIndex()) {
            try {
                reporter?.msg("  → 尝试镜像 ${idx + 1}/${registryMirror.size}: ${reg.label} (${reg.baseUrl})")
                return getAuthTokenForOne(reg, repo, reporter, insecure)
            } catch (e: Throwable) {
                lastError = e
                reporter?.msg("    ! ${reg.label} 失败: ${e::class.simpleName}: ${e.message?.take(200)}")
                // 继续尝试下一个
            }
        }
        throw RuntimeException(
            "所有 ${registryMirror.size} 个镜像都失败。最后一个错误: " +
            (lastError?.message ?: lastError?.toString() ?: "unknown")
        )
    }

    /** 兼容旧 API: 给一个 registry 名字 (Docker Hub 时传空) */
    suspend fun getAuthToken(
        repo: String,
        registry: String,
        reporter: TaskReporter?,
        insecure: Boolean = false,
    ): AuthResult {
        val reg = if (registry.isEmpty()) KNOWN_MIRRORS[0]
                  else Registry(registry, "https://$registry", isDockerHub = (registry == "registry-1.docker.io"))
        return getAuthToken(repo, listOf(reg), reporter, insecure)
    }

    private suspend fun getAuthTokenForOne(
        reg: Registry,
        repo: String,
        reporter: TaskReporter?,
        insecure: Boolean,
    ): AuthResult {
        // Docker Hub 走专用 auth 端点
        if (reg.isDockerHub) {
            val url = "${ProotDistro.DOCKER_HUB_AUTH}?service=registry.docker.io&scope=repository:$repo:pull"
            val headers = mutableMapOf("User-Agent" to ProotDistro.userAgent)
            val body = ProotDistroHttp.httpGet(url, headers, reporter, insecure = false)
            val json = JSONObject(body)
            val token = json.optString("token").ifEmpty { json.optString("access_token") }
            return AuthResult(token, reg.baseUrl, reg.label)
        }

        // 如果配了固定 token issuer,直接用它 (国内镜像走这条)
        if (reg.tokenIssuer != null) {
            val tokenUrl = reg.tokenIssuer.replace("{repo}", repo)
            val headers = mutableMapOf("User-Agent" to ProotDistro.userAgent)
            val body = ProotDistroHttp.httpGet(tokenUrl, headers, reporter, insecure)
            val json = JSONObject(body)
            val token = json.optString("token").ifEmpty { json.optString("access_token") }
            if (token.isEmpty()) throw RuntimeException("token response is empty")
            return AuthResult(token, reg.baseUrl, reg.label)
        }

        // 标准 OCI 流程: probe /v2/,从 401 的 WWW-Authenticate 解析 realm
        var scheme = "https"
        while (true) {
            val base = "$scheme://${extractHost(reg.baseUrl)}"
            val probeHeaders = mapOf("User-Agent" to ProotDistro.userAgent)
            val conn = ProotDistroHttp.openConnection("$base/v2/", probeHeaders, insecure)
            try {
                when (conn.responseCode) {
                    in 200..299 -> {
                        return AuthResult("", base, reg.label)
                    }
                    401 -> {
                        val wwwAuth = conn.getHeaderField("WWW-Authenticate") ?: ""
                        if (!wwwAuth.lowercase().startsWith("bearer ")) {
                            return AuthResult("", base, reg.label)
                        }
                        val params = parseBearerChallenge(wwwAuth.substring(7))
                        val realm = params["realm"] ?: return AuthResult("", base, reg.label)
                        val service = params["service"] ?: ""
                        val scopeParam = "scope=repository:$repo:pull"
                        val serviceParam = if (service.isNotEmpty())
                            "service=${java.net.URLEncoder.encode(service, "UTF-8")}" else ""
                        val sep = if ('?' in realm) "&" else "?"
                        val tokenUrl = "$realm$sep${listOf(serviceParam, scopeParam).filter { it.isNotEmpty() }.joinToString("&")}"
                        val tokenHeaders = mapOf("User-Agent" to ProotDistro.userAgent)
                        val body = ProotDistroHttp.httpGet(tokenUrl, tokenHeaders, reporter, insecure)
                        val json = JSONObject(body)
                        val token = json.optString("token").ifEmpty { json.optString("access_token") }
                        if (token.isEmpty()) throw RuntimeException("token response is empty")
                        return AuthResult(token, base, reg.label)
                    }
                    else -> throw RuntimeException("registry probe failed: HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 从 https://host[:port] 里抽 host[:port] 部分 */
    private fun extractHost(baseUrl: String): String {
        return baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }

    /** 解析 Bearer challenge 的 key=value 参数 (支持 quoted 和 bare token 两种形式) */
    private fun parseBearerChallenge(header: String): Map<String, String> {
        val result = HashMap<String, String>()
        val regex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
        for (m in regex.findAll(header)) {
            val key = m.groupValues[1]
            val quoted = m.groupValues[2]
            val bare = m.groupValues[3]
            result[key] = if (quoted.isNotEmpty()) quoted else bare
        }
        return result
    }
}