package com.java.myapplication.engine

import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 基于 **Cronet**（Chromium 网络栈）的 HTTP 客户端，供 hentaipaw 站点使用。
 *
 * 为什么用 Cronet：
 * - hentaipaw 前的 Cloudflare 对 **TLS 指纹（JA3）** 有风控。
 *   Android 的 java.net / SSLSocket 指纹特征明显，直连一律 403；
 *   而 Python(OpenSSL) 或浏览器内核指纹可过。
 * - Cronet 的 TLS 栈 = Chromium/Chrome，指纹与浏览器一致，可过 CF。
 *
 * 设计：
 * - 引擎单例，懒初始化（须在 App 启动后有 Context 才能建）。
 * - Cronet 是异步回调 API，这里用 CountDownLatch 封装成**同步阻塞**，以适配现有下载引擎。
 * - 支持可选的本地 HTTP 代理（若系统已全局代理可保持 null；需要时可在建引擎前设置）。
 *
 * 安全红线：不内置任何 cookie / token / 密钥。
 */
object CronetHttp {

    /** 与站点脚本一致的 UA（Android Chrome）。 */
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 可选：本地 HTTP 代理（在引擎初始化前设置才生效）。null=直连。 */
    @Volatile
    var proxyHost: String? = null

    @Volatile
    var proxyPort: Int = 0

    private val exec = Executors.newCachedThreadPool()

    @Volatile
    private var engine: CronetEngine? = null

    /** 是否已具备可用的 Cronet 引擎（用于判断回退）。 */
    fun isAvailable(): Boolean = runCatching { buildEngine() != null }.getOrDefault(false)

    @Synchronized
    private fun buildEngine(): CronetEngine? {
        engine?.let { return it }
        val ctx = AppCtx.get() ?: return null
        return try {
            val b = CronetEngine.Builder(ctx)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
            // 可选：显式注入本地 HTTP 代理（Cronet 143 恢复/提供了 setProxyOptions API）。
            // 默认不注入——本项目假设系统已全局代理（VPN 模式，魔方加速器），引擎会自然复用系统路由。
            // 若日后需要强制走某个本地代理，只需设置 CronetHttp.proxyHost/proxyPort 即可。
            val ph = proxyHost
            val pp = proxyPort
            if (!ph.isNullOrBlank() && pp > 0) {
                val proxy = org.chromium.net.Proxy(
                    org.chromium.net.Proxy.HTTP, ph, pp,
                    object : org.chromium.net.Proxy.Callback() {
                        override fun onTunnelHeadersReceived(
                            responseHeaders: List<Map.Entry<String, String>>,
                            statusCode: Int
                        ): Boolean = true
                    }
                )
                b.setProxyOptions(org.chromium.net.ProxyOptions(listOf(proxy)))
            }
            b.build().also {
                engine = it
                DiagLog.error("CRONET", "引擎已初始化（UA=Chrome/120, http2/quic/brotli=on）")
            }
        } catch (e: Exception) {
            DiagLog.error("CRONET", "引擎初始化失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 同步 GET 文本（utf-8）。失败抛 [Http.NetException]。 */
    fun getText(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        accept: String = "*/*",
        timeoutMs: Long = 30000
    ): String {
        return String(getBytes(url, referer, headers, accept, timeoutMs), Charsets.UTF_8)
    }

    /** 同步 GET 字节。失败抛 [Http.NetException]。 */
    fun getBytes(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        accept: String = "*/*",
        timeoutMs: Long = 30000
    ): ByteArray {
        val eng = buildEngine()
            ?: throw Http.NetException(FailReason.NETWORK, "Cronet 引擎不可用")
        val masked = DiagLog.maskUrl(url)
        val latch = CountDownLatch(1)
        val out = AtomicReference<ByteArray>(ByteArray(0))
        val err = AtomicReference<String?>(null)
        val codeRef = AtomicReference(0)
        val cfRay = AtomicReference("-")

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(req: UrlRequest, info: UrlResponseInfo, newUrl: String) {
                req.followRedirect()
            }

            override fun onResponseStarted(req: UrlRequest, info: UrlResponseInfo) {
                codeRef.set(info.httpStatusCode)
                runCatching { cfRay.set(info.allHeaders["cf-ray"]?.firstOrNull() ?: "-") }
                req.read(ByteBuffer.allocateDirect(256 * 1024))
            }

            override fun onReadCompleted(req: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
                buffer.flip()
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                val prev = out.get()
                val merged = ByteArray(prev.size + chunk.size)
                System.arraycopy(prev, 0, merged, 0, prev.size)
                System.arraycopy(chunk, 0, merged, prev.size, chunk.size)
                out.set(merged)
                buffer.clear()
                req.read(buffer)
            }

            override fun onSucceeded(req: UrlRequest, info: UrlResponseInfo) {
                latch.countDown()
            }

            override fun onFailed(req: UrlRequest, info: UrlResponseInfo?, e: CronetException) {
                err.set("${e.javaClass.simpleName}: ${e.message}")
                latch.countDown()
            }

            override fun onCanceled(req: UrlRequest, info: UrlResponseInfo?) {
                err.set("canceled")
                latch.countDown()
            }
        }

        val builder = eng.newUrlRequestBuilder(url, callback, exec)
            .setHttpMethod("GET")
            .allowDirectExecutor()
            .addHeader("User-Agent", UA)
            .addHeader("Accept", accept)
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9")
        referer?.let { builder.addHeader("Referer", it) }
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        val req = builder.build()

        DiagLog.req("CRONET", "GET $masked (referer=${referer ?: "-"})")
        req.start()

        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) {
            runCatching { req.cancel() }
            throw Http.NetException(FailReason.NETWORK, "Cronet 超时 ${timeoutMs}ms")
        }
        val e = err.get()
        if (e != null) {
            DiagLog.error("CRONET", "FAIL $masked | $e")
            throw Http.NetException(FailReason.NETWORK, "Cronet 请求失败：$e")
        }
        val code = codeRef.get()
        val body = out.get()
        DiagLog.error("CRONET", "code=$code cf-ray=${cfRay.get()} len=${body.size} | $masked")
        if (code >= 400) {
            val snippet = runCatching { String(body, Charsets.UTF_8).take(160) }.getOrDefault("")
            DiagLog.error("CRONET", "HTTP $code body=$snippet")
            throw Http.NetException(FailReason.HTTP, "HTTP $code")
        }
        return body
    }

    /** 同步 GET 并写入文件（流式拼接）。返回写入字节数。 */
    fun downloadToFile(
        url: String,
        dest: File,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60000
    ): Long {
        val bytes = getBytes(url, referer, headers, accept = "*/*", timeoutMs = timeoutMs)
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        return bytes.size.toLong()
    }
}
