package com.java.myapplication.engine

import java.net.HttpURLConnection
import java.net.URL

/**
 * 轻量 HTTP 客户端（基于 java.net，零第三方依赖）。
 * 供所有下载引擎复用：统一 UA、超时、GET 正文 / GET 二进制 / 可选 Cookie。
 *
 * 安全红线：cookie 只能通过 [cookieHeader] 由「使用者运行时导入」后传入，
 * 本文件及任何代码路径都不内置、不硬编码任何 token / cookie / 密钥。
 */
object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 运行时导入的 cookie 请求头（Netscape 格式解析后拼接），未导入则为 null */
    var cookieHeader: String? = null

    /** 连接/读取超时（毫秒），可由设置覆盖以适配弱网 */
    var connectTimeoutMs: Int = 15000
    var readTimeoutMs: Int = 30000

    /** 失败重试次数（弱网可调大） */
    var maxRetries: Int = 2

    /**
     * 本地代理配置（用于过 CF / 访问被 SNI 阻断的站点）。
     * null = 直连（默认）；非空 = 所有请求经该 HTTP 代理。
     * 由「代理自动检测」在运行时设置。
     */
    @Volatile
    var proxyHost: String? = null

    @Volatile
    var proxyPort: Int = 0

    /** 是否已启用代理 */
    fun proxyEnabled(): Boolean = !proxyHost.isNullOrBlank() && proxyPort > 0

    /** 带原因分类的网络异常，便于 UI 区分"连接失败/服务器异常/需代理" */
    class NetException(val kind: String, message: String) : Exception(message)

    /**
     * 【临时诊断】对同一 URL 用不同"请求头指纹"各发一次，比较是否被 CF 拦截。
     * 仅用于真机排障定位"请求头 vs 底层"差异；不参与正常下载逻辑。
     * 结果写入 DiagLog（error 级，必落盘）。
     */
    fun diagProbe(url: String) {
        val uaScript = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        val cases = listOf(
            Triple("1.App当前头", UA, mapOf("Accept" to "*/*", "Accept-Language" to "zh-CN,zh;q=0.9")),
            Triple("2.脚本UA精简头", uaScript, mapOf("Accept" to "*/*")),
            Triple("3.浏览器完整头", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Upgrade-Insecure-Requests" to "1"))
        )
        for ((label, ua, hdr) in cases) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Referer", "https://hentaipaw.com/")
                hdr.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                val cfRay = conn.getHeaderField("CF-RAY") ?: "-"
                val serv = conn.getHeaderField("Server") ?: "-"
                DiagLog.error("PROBE", "[$label] code=$code server=$serv cf-ray=$cfRay | UA=${ua.take(60)} | extra=$hdr")
                conn.disconnect()
            } catch (e: Exception) {
                DiagLog.error("PROBE", "[$label] 异常 ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** 简单 GET，返回响应体文本（utf-8，容错） */
    fun getText(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): String {
        return withRetry {
            val conn = open(url, referer, headers)
            try {
                readBodyText(conn)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** GET 返回字节（二进制下载用），自动处理 gzip */
    fun getBytes(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): ByteArray {
        return withRetry {
            val conn = open(url, referer, headers)
            try {
                readBodyBytes(conn)
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * POST 请求，返回响应体文本（utf-8，容错）。
     * 用于 Twitter guest token 等需要 POST 的接口。body 可为空（guest/activate.json 即为空体）。
     */
    fun postText(
        url: String,
        body: String = "",
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String {
        return withRetry {
            val conn = open(url, referer, headers, method = "POST", body = body.toByteArray(Charsets.UTF_8))
            try {
                readBodyText(conn)
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 【关键】读取响应体为字节，并按 Content-Encoding 自动解压 gzip/deflate。
     *
     * 背景：部分站点（如禁漫 API）在请求头带 `Accept-Encoding: gzip, deflate` 时
     * 会返回 gzip 压缩体（magic 0x1f 0x8b）。java.net 不会自动解压，
     * 若直接 toString 会得到乱码 → JSON 解析失败（实测曾导致禁漫"无 data 字段"）。
     */
    private fun readBodyBytes(conn: HttpURLConnection): ByteArray {
        var stream: java.io.InputStream = conn.inputStream ?: return ByteArray(0)
        val enc = (conn.contentEncoding ?: "").lowercase()
        try {
            stream = when {
                enc.contains("gzip") -> java.util.zip.GZIPInputStream(stream)
                enc.contains("deflate") -> java.util.zip.InflaterInputStream(stream)
                else -> {
                    // 部分服务器不声明 Content-Encoding 却仍返回 gzip：按 magic 兜底探测
                    val pushback = java.io.PushbackInputStream(stream, 2)
                    val b = ByteArray(2)
                    val n = pushback.read(b, 0, 2)
                    if (n == 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) {
                        java.util.zip.GZIPInputStream(pushback)
                    } else {
                        if (n > 0) pushback.unread(b, 0, n)
                        pushback
                    }
                }
            }
            return stream.use { it.readBytes() }
        } catch (e: Exception) {
            DiagLog.error("HTTP", "响应解压失败(enc=$enc)：${e.javaClass.simpleName}: ${e.message}")
            // 兜底：退回原始流读取（不解压），至少不抛异常
            return try {
                conn.inputStream.use { it.readBytes() }
            } catch (_: Exception) {
                ByteArray(0)
            }
        }
    }

    /** 读取响应体为文本（utf-8），自动处理 gzip */
    private fun readBodyText(conn: HttpURLConnection): String =
        readBodyBytes(conn).toString(Charsets.UTF_8)

    /**
     * GET 并写入目标文件（流式，避免大文件占满内存）。
     * @return 写入的字节数
     */
    fun downloadToFile(url: String, dest: java.io.File, referer: String? = null, headers: Map<String, String> = emptyMap()): Long {
        return withRetry {
            val conn = open(url, referer, headers)
            try {
                conn.inputStream.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                dest.length()
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * GET 并**追加**写入目标文件（HLS 分片按顺序拼接用）。
     * 不会截断已有内容，分片按调用顺序写入即天然有序。
     * @return 本次追加的字节数
     */
    fun appendToFile(url: String, dest: java.io.File, referer: String? = null, headers: Map<String, String> = emptyMap()): Long {
        return withRetry {
            val conn = open(url, referer, headers)
            try {
                var written = 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(dest, true).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                        }
                    }
                }
                written
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 带指数退避重试。仅对 NETWORK 类错误重试；HTTP（如 403/404）与数据解析错误不重试，
     * 避免在明确失败时浪费时间、也避免对同一资源反复请求。
     */
    private inline fun <T> withRetry(block: () -> T): T {
        var last: Exception? = null
        val attempts = maxRetries.coerceAtLeast(0) + 1
        for (i in 0 until attempts) {
            try {
                return block()
            } catch (e: NetException) {
                last = e
                DiagLog.error("RETRY", "第 ${i + 1}/${attempts} 次失败 kind=${e.kind} msg=${e.message}")
                if (e.kind != FailReason.NETWORK || i == attempts - 1) throw e
            } catch (e: Exception) {
                last = NetException(FailReason.NETWORK, "网络异常:${e.message}")
                DiagLog.error("RETRY", "第 ${i + 1}/${attempts} 次异常 ${e.javaClass.simpleName}: ${e.message}")
                if (i == attempts - 1) throw last
            }
            try {
                Thread.sleep((300L shl i).coerceAtMost(4000L)) // 300 / 600 / 1200 ...ms，封顶 4s
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw last
            }
        }
        throw last ?: NetException(FailReason.NETWORK, "未知网络错误")
    }

    private fun open(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        method: String = "GET",
        body: ByteArray? = null
    ): HttpURLConnection {
        val masked = DiagLog.maskUrl(url)
        DiagLog.req("HTTP", "$method $masked (referer=${referer ?: "-"}, cookie=${!cookieHeader.isNullOrBlank()})")
        val conn = try {
            val u = URL(url)
            if (proxyEnabled()) {
                val p = java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    java.net.InetSocketAddress(proxyHost, proxyPort)
                )
                u.openConnection(p) as HttpURLConnection
            } else {
                u.openConnection() as HttpURLConnection
            }
        } catch (e: Exception) {
            DiagLog.error("HTTP", "无法建立连接：$masked | ${e.javaClass.simpleName}: ${e.message}")
            throw NetException(FailReason.NETWORK, "无法建立连接：${e.message}")
        }
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        referer?.let { conn.setRequestProperty("Referer", it) }
        cookieHeader?.let { conn.setRequestProperty("Cookie", it) }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        // ===== 诊断插桩：自建"App 实际设置的头"记录（不依赖 requestProperties，Android 上它常为空）=====
        val sentHeaderDump = buildString {
            val ch = cookieHeader
            append("User-Agent=").append(UA)
            append("; Accept=*/*")
            append("; Accept-Language=zh-CN,zh;q=0.9")
            append("; Referer=").append(referer ?: "-")
            append("; Cookie=").append(if (ch.isNullOrBlank()) "-" else "(present,${ch.length}B)")
            headers.forEach { (k, v) -> append("; ").append(k).append("=").append(v) }
            // 运行时实际注入的隐式头（Android HttpURLConnection 会自动加的）
            append("; [implicit] Accept-Encoding=").append(conn.getRequestProperty("Accept-Encoding") ?: "(auto)")
        }
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        // ===== 诊断插桩（只读，不影响请求行为）=====
        // 记录本进程实际采用的代理（VPN 全局代理在 JVM 层通常为 DIRECT）
        runCatching {
            val pr = java.net.ProxySelector.getDefault()?.select(java.net.URI(url))?.firstOrNull()
            DiagLog.req("HTTP", "proxy=${pr?.type()}@${pr?.address()} | jvmHttpProxy=" +
                "${System.getProperty("http.proxyHost")}:${System.getProperty("http.proxyPort")}")
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setFixedLengthStreamingMode(body.size)
            runCatching { conn.outputStream.use { it.write(body) } }
        }
        // 主动触发连接，把网络错误归类成 NETWORK / HTTP
        try {
            val code = conn.responseCode
            if (code >= 400) {
                // ===== 诊断插桩：4xx 时记录实际最终 URL + App 实际发出的请求头 =====
                runCatching {
                    DiagLog.error("HTTP", "REQDUMP finalUrl=${DiagLog.maskUrl(conn.url?.toString() ?: "-")} code=$code sentHeaders=[$sentHeaderDump]")
                }.onFailure {
                    DiagLog.error("HTTP", "REQDUMP-FAIL ${it.javaClass.simpleName}: ${it.message}")
                }
                // 抓错误响应体片段，便于判断"被 CF 拦截 / 需要登录 / 站点改版"
                val body = runCatching {
                    conn.errorStream?.use { DiagLog.snippet(it.readBytes()) } ?: ""
                }.getOrDefault("")
                // ===== 诊断插桩：403/4xx 时记录完整响应头（判断是否 CF 挑战）=====
                runCatching {
                    val hdrs = conn.headerFields.entries.joinToString("; ") { e ->
                        "${e.key}: ${e.value?.joinToString(",") ?: ""}"
                    }
                    DiagLog.error("HTTP", "HTTP $code respHeaders=[$hdrs]")
                }
                DiagLog.error("HTTP", "HTTP $code | $masked | contentType=${conn.contentType} | body=$body")
                throw NetException(FailReason.HTTP, "HTTP $code")
            }
            DiagLog.req("HTTP", "OK $code | $masked | contentType=${conn.contentType}")
        } catch (e: NetException) {
            conn.disconnect()
            throw e
        } catch (e: Exception) {
            DiagLog.error("HTTP", "连接/读取失败：$masked | ${e.javaClass.simpleName}: ${e.message}")
            conn.disconnect()
            throw NetException(FailReason.NETWORK, "连接/读取失败：${e.message}")
        }
        return conn
    }
}