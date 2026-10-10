package com.java.myapplication.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView 取页器（过 Cloudflare 拦截用）。
 *
 * 背景：经真机对照实验确认，HentaiPaw 站点由 Cloudflare WAF 按 **TLS/客户端指纹** 拦截
 * Android 系统 OkHttp 栈（HttpURLConnection），与请求头、Cookie、出口 IP 均无关
 * （换 3 组请求头、浏览器 UA 全 403；浏览器可正常打开且无需 Cookie）。
 * 因此改用「真实浏览器内核（WebView）」发这个请求，让 CF 看到 Chrome 指纹从而放行。
 *
 * 设计：
 *  - [fetchHtml] 为**阻塞式**（供下载 Service 的同步线程调用）：内部把 WebView 的创建/加载
 *    post 到主线程，再用 CountDownLatch 等待结果；
 *  - WebView 用 applicationContext 创建（不绑定 Activity，避免泄漏）；用完 destroy；
 *  - 等 onPageFinished 后再延迟 [RENDER_DELAY_MS]，让 CF 的 JS 挑战/页面 SSR 渲染完成，
 *    再取 document.documentElement.outerHTML；
 *  - 全程写 DiagLog（error 级，必落盘），记录 URL/title/HTML 长度/是否含 slides/是否含 403。
 *
 * 安全：不注入任何用户凭证；不修改请求头伪造 cookie。仅"以浏览器身份访问公开页面"。
 */
object WebViewGate {

    private val main = Handler(Looper.getMainLooper())

    /** onPageFinished 后再等这么久，给 CF 的 JS 挑战/渲染留时间 */
    private var renderDelayMs = 2500L

    /** 整体超时（从 loadUrl 到取 HTML） */
    private var totalTimeoutMs = 30000L

    fun configure(renderDelayMs: Long, totalTimeoutMs: Long) {
        this.renderDelayMs = renderDelayMs
        this.totalTimeoutMs = totalTimeoutMs
    }

    /**
     * 用 WebView 打开 [url]，返回渲染后的完整 HTML；失败/超时返回 null。
     * 该方法是**阻塞**的，切勿在主线程调用。
     */
    fun fetchHtml(ctx: Context, url: String, userAgent: String? = null): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            DiagLog.error("WV", "fetchHtml 被主线程调用，拒绝（会死锁）")
            return null
        }
        val app = ctx.applicationContext
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        val done = AtomicBoolean(false)

        main.post {
            var web: WebView? = null
            try {
                web = WebView(app)
                val s: WebSettings = web.settings
                s.javaScriptEnabled = true                 // CF 挑战需要 JS
                s.domStorageEnabled = true                 // CF 会写 localStorage
                s.databaseEnabled = true
                s.loadsImagesAutomatically = false          // 只要 HTML，不加载图片，省流量
                s.userAgentString = userAgent ?: s.userAgentString
                s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?, request: WebResourceRequest?
                    ): WebResourceResponse? = null

                    override fun onReceivedError(
                        view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            val code = if (android.os.Build.VERSION.SDK_INT >= 23) error?.errorCode else null
                            val desc = if (android.os.Build.VERSION.SDK_INT >= 23) error?.description else null
                            DiagLog.error("WV", "onReceivedError 主框架 url=${request.url} code=$code desc=$desc")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame == true) {
                            DiagLog.error(
                                "WV",
                                "onReceivedHttpError 主框架 url=${request.url} status=${errorResponse?.statusCode}"
                            )
                        }
                    }

                    override fun onPageFinished(view: WebView?, u: String?) {
                        // onPageFinished 后延迟再取，等 CF JS 挑战跑完 / SSR 渲染
                        main.postDelayed({
                            try {
                                val title = view?.title ?: ""
                                view?.evaluateJavascript(
                                    "document.documentElement.outerHTML"
                                ) { json ->
                                    val html = jsonToString(json)
                                    val len = html.length
                                    val hasSlides = html.contains("\"slides\"")
                                    val has403 = html.contains("Attention Required")
                                        || html.contains("403 Forbidden")
                                    DiagLog.error(
                                        "WV",
                                        "取回 finalUrl=$u title=$title len=$len " +
                                                "hasSlides=$hasSlides has403=$has403"
                                    )
                                    if (done.compareAndSet(false, true)) {
                                        holder[0] = html
                                        latch.countDown()
                                    }
                                    web?.destroy()
                                }
                            } catch (e: Exception) {
                                DiagLog.error("WV", "取 HTML 失败 ${e.javaClass.simpleName}: ${e.message}")
                                if (done.compareAndSet(false, true)) latch.countDown()
                                web?.destroy()
                            }
                        }, renderDelayMs)
                    }
                }

                DiagLog.error("WV", "loadUrl $url (ua=${(userAgent ?: s.userAgentString).take(60)})")
                web.loadUrl(url)
            } catch (e: Exception) {
                DiagLog.error("WV", "创建 WebView 失败 ${e.javaClass.simpleName}: ${e.message}")
                if (done.compareAndSet(false, true)) latch.countDown()
                web?.destroy()
            }
        }

        val ok = try {
            latch.await(totalTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!ok) {
            DiagLog.error("WV", "超时 ${totalTimeoutMs}ms 未取到 HTML: $url")
            return null
        }
        return holder[0]
    }

    /** WebView.evaluateJavascript 回调返回的是 JSON 字符串字面量，做反转义 */
    private fun jsonToString(json: String?): String {
        if (json == null || json == "null") return ""
        var s = json
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            s = s.substring(1, s.length - 1)
        }
        return s
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u002F", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
}
