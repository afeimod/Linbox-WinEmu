package org.github.ewt45.winemulator.prootdistro

import org.json.JSONObject
import org.github.ewt45.winemulator.ui.components.TaskReporter

/**
 * 参考 `proot_distro/helpers/docker/transport.py` 的
 * `get_auth_token`——拉取 registry OAuth2 token。
 *
 * 返回值是 (token, baseUrl):token 是空字符串表示该 registry
 * 不需要认证 (匿名访问);baseUrl 是后面所有 v2/ 请求要用的根地址。
 */
object ProotDistroAuth {

    data class AuthResult(
        val token: String,
        val baseUrl: String,
    )

    /**
     * 拉取 registry 的 base URL 和 Bearer token。
     *
     * - Docker Hub: 走固定的 auth.docker.io 端点 (匿名用空 PD_DOCKER_AUTH)
     * - 自定义 registry: 先 HEAD /v2/ 探测,401 时从 WWW-Authenticate 解析 realm
     */
    suspend fun getAuthToken(
        repo: String,
        registry: String,
        reporter: TaskReporter?,
        insecure: Boolean = false,
    ): AuthResult {
        // Docker Hub:走固定 auth endpoint
        if (registry.isEmpty()) {
            val url = "${ProotDistro.DOCKER_HUB_AUTH}?service=registry.docker.io&scope=repository:$repo:pull"
            val headers = mutableMapOf("User-Agent" to ProotDistro.userAgent)
            // PD_DOCKER_AUTH 暂不实现,匿名足够 proot-distro 99% 用例
            val body = ProotDistroHttp.httpGet(url, headers, reporter, insecure = false)
            val json = JSONObject(body)
            val token = json.optString("token").ifEmpty { json.optString("access_token") }
            return AuthResult(token, ProotDistro.DOCKER_HUB_REGISTRY)
        }

        // 自定义 registry
        var scheme = "https"
        while (true) {
            val base = "$scheme://$registry"
            val probeHeaders = mapOf("User-Agent" to ProotDistro.userAgent)
            val conn = ProotDistroHttp.openConnection("$base/v2/", probeHeaders, insecure)
            try {
                when (conn.responseCode) {
                    in 200..299 -> {
                        // 匿名访问,无需 token
                        return AuthResult("", base)
                    }
                    401 -> {
                        val wwwAuth = conn.getHeaderField("WWW-Authenticate") ?: ""
                        if (!wwwAuth.lowercase().startsWith("bearer ")) {
                            return AuthResult("", base)
                        }
                        val params = parseBearerChallenge(wwwAuth.substring(7))
                        val realm = params["realm"] ?: return AuthResult("", base)
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
                        return AuthResult(token, base)
                    }
                    else -> throw RuntimeException("registry probe failed: HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 解析 Bearer challenge 的 key=value 参数(支持 quoted 和 bare token 两种形式) */
    private fun parseBearerChallenge(header: String): Map<String, String> {
        val result = HashMap<String, String>()
        // 匹配 key="value" 或 key=value
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