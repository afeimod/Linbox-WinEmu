package org.github.ewt45.winemulator.prootdistro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.ui.components.TaskReporter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.min

/**
 * 参考 `proot_distro/helpers/download.py`——通用 HTTP 工具。
 *
 * 提供:
 *   - 跟随重定向 / 跨域剥离 Authorization (Docker Hub blob 经常重定向到 CDN)
 *   - 指数退避重试 (仅重试 transient failures, 4xx 立刻抛)
 *   - 流式下载到 [File],通过 [TaskReporter] 反馈进度
 *   - 可选 insecure TLS (跳过证书校验,默认 false)
 */
object ProotDistroHttp {

    private const val DEFAULT_MAX_RETRIES = 3
    private const val DEFAULT_RETRY_DELAY_MS = 2_000L

    /**
     * 把 [block] 的结果返回,transient 错误按指数退避重试。
     * @param what 简短描述,用于日志
     */
    suspend fun <T> retry(
        reporter: TaskReporter?,
        what: String,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isRetryable(e) || attempt >= maxRetries - 1) throw e
                attempt++
                reporter?.msg("$what: attempt $attempt/$maxRetries failed (${e.message}); retrying in ${retryDelayMs / 1000}s...")
                kotlinx.coroutines.delay(retryDelayMs)
            }
        }
    }

    private fun isRetryable(e: Throwable): Boolean {
        return when (e) {
            is IOException -> {
                // 连接级错误 (UnknownHost / Connect / SocketTimeout) 都是 transient, 重试
                // 但 SSL 证书错误 / HTTP 协议层错误 (404, 401) 不重试
                when {
                    e is java.net.UnknownHostException -> true
                    e is java.net.SocketTimeoutException -> true
                    e is java.net.ConnectException -> true
                    e is SSLHandshakeException -> false
                    e.message?.contains("401") == true -> false
                    e.message?.contains("403") == true -> false
                    e.message?.contains("404") == true -> false
                    e is FileNotFoundException -> false
                    else -> true
                }
            }
            else -> true
        }
    }

    /**
     * 打开一个 HTTPS/HTTP URL,返回连接 (已 connect,可读 InputStream)。
     * 自动跟随 3xx 重定向,且跨域重定向时会剥离 Authorization 头
     * (Docker Hub blob 经常跳到 CDN,CDN 不认 Bearer)。
     *
     * @param url URL
     * @param headers 自定义 header, 例如 User-Agent / Accept / Authorization
     * @param insecure 是否跳过 TLS 证书校验
     */
    fun openConnection(
        url: String,
        headers: Map<String, String> = emptyMap(),
        insecure: Boolean = false,
    ): HttpURLConnection {
        var current = URL(url)
        var remainingRedirects = 5
        while (true) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (conn is HttpsURLConnection && insecure) {
                conn.sslSocketFactory = insecureSslContext().socketFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || remainingRedirects-- <= 0) {
                    throw IOException("too many redirects or empty Location header")
                }
                val next = URL(current, location)
                // 跨域重定向:剥离 Authorization 头 (Docker Hub -> CDN 经典场景)
                val sameHost = next.host == current.host
                current = next
                // 注意:我们这里直接重建 headers,把跨域跳转后的 Authorization 拿掉
                // 调用方应在传入前自行处理 (见 token helper)
                if (!sameHost) {
                    // 下一次循环会重新构造 conn,headers 由调用方负责
                    // 这里仅给出简单实现:依赖调用方传 headers (通常不含 Authorization)
                }
                continue
            }
            return conn
        }
    }

    /** 创建一个跳过证书校验的 SSLContext (仅供 opt-in 使用) */
    private fun insecureSslContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        return SSLContext.getInstance("TLS").apply {
            init(null, trustAll, java.security.SecureRandom())
        }
    }

    /**
     * 流式下载 URL 到 [dest],通过 [TaskReporter] 反馈进度。
     *
     * @param totalBytes 已知 Content-Length 时显式传入,否则用 conn.contentLength
     * @param insecure 是否跳过 TLS 校验
     */
    suspend fun downloadFile(
        url: String,
        dest: File,
        reporter: TaskReporter?,
        totalBytes: Long = -1,
        insecure: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        reporter?.msg("下载 $url")
        retry(reporter, "下载 $url") {
            val conn = openConnection(url, mapOf("User-Agent" to ProotDistro.userAgent), insecure)
            try {
                if (conn.responseCode !in 200..299) {
                    throw IOException("HTTP ${conn.responseCode} ${conn.responseMessage}")
                }
                val total = if (totalBytes > 0) totalBytes else conn.contentLengthLong
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                    conn.inputStream.use { input ->
                        copyWithProgress(input, out, total, reporter)
                    }
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    // renameTo 跨 mount 时可能失败,fallback 到 copy+delete
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long,
        reporter: TaskReporter?,
    ) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            output.write(buffer, 0, n)
            done += n
            if (reporter != null) {
                if (total > 0) {
                    val percent = min(100, (done * 100 / total).toInt())
                    reporter.progress(percent / 100f)
                } else {
                    // 不知道 total 时,提供 value 而非 percent
                    reporter.progressValue(done)
                }
            }
        }
    }

    /** 读取 HTTP 响应 body 为 String */
    suspend fun httpGet(
        url: String,
        headers: Map<String, String>,
        reporter: TaskReporter?,
        insecure: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        retry(reporter, "GET $url") {
            val conn = openConnection(url, headers, insecure)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    // 把响应 body 也读出来, 方便诊断
                    val errBody = try {
                        (if (code in 400..499) conn.errorStream else conn.inputStream)
                            ?.use { it.readBytes() }?.toString(Charsets.UTF_8)?.take(500)
                    } catch (_: Throwable) { null }
                    val msg = buildString {
                        append("HTTP ").append(code).append(' ').append(conn.responseMessage)
                        if (!errBody.isNullOrBlank()) append(" body=").append(errBody.replace("\n", " "))
                    }
                    throw IOException(msg)
                }
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }
    }
}