package com.java.myapplication.site

import com.java.myapplication.engine.AppCtx
import com.java.myapplication.engine.CronetHttp
import com.java.myapplication.engine.Downloader
import com.java.myapplication.engine.DiagLog
import com.java.myapplication.engine.FailReason
import com.java.myapplication.engine.Http
import com.java.myapplication.engine.ImagePdf
import com.java.myapplication.engine.InputParser
import com.java.myapplication.engine.ProgressCallback
import com.java.myapplication.engine.WebViewGate
import java.io.File
import java.net.URLEncoder

/**
 * HentaiPaw 漫画下载器。
 *
 * ⚠️ 本实现严格对齐已跑通的成功脚本 `hp3.py`（逐行参考，非自研猜测）：
 *
 * 站点实测结论（来自 hp3.py）：
 * - 站点为 Next.js SSR 应用，阅读页 URL 形如：
 *       https://hentaipaw.com/viewer?articleId={id}&page=1
 *   （⚠️ 之前 App 用 /g/{id} 是错的，该路径不存在）
 * - 整话的图片列表以 JSON 内联在页面里，字段名为 "slides"，形如：
 *       "slides":[{"src":"https://cdn.imagedeliveries.com/...webp"}, ...]
 *   因此必须从内联 JSON 里抠图，而不是抓所有 <img src>（会抓到 logo/广告/头像）
 * - 图片 CDN 主机：cdn.imagedeliveries.com
 * - 图片本身无加密，带 Referer=https://hentaipaw.com/ 即可直连下载
 * - 搜索：GET https://hentaipaw.com/articles/search?keyword=<kw>&page=1
 *   结果卡片形如 <a href="/articles/<id>"><img alt="标题" ...>，正则跨标签抽 id
 * - 图片落盘前做文件头校验（防 CF 拦截页被当图片存成假成功）
 * - 该站为大陆直连被 SNI 阻断的站点，运行时走系统全局代理即可
 *
 * 输入自动识别：
 *   * 传纯数字                 -> articleId
 *   * 传含 articleId= 的链接   -> 抽出 articleId
 *   * 传 /viewer/{id} 链接      -> 抽出 id
 *   * 其它关键词               -> 搜索模式（取首个结果）
 *
 * 零密钥、零 cookie。
 */
class HentaiPawDownloader : Downloader {
    override val siteId = "hentaipaw"

    private companion object {
        const val BASE = "https://hentaipaw.com/"
        const val UA = "Mozilla/5.0 (Linux; Android 14; 2312DRA50C) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        /** 图片 CDN 主机（用于兜底正则） */
        const val CDN_HOST = "cdn.imagedeliveries.com"
    }

    /** articleId 的链接形态 */
    private val idPatterns = listOf(
        Regex("""[?&]articleId=(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""/viewer/(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""/(?:g|gallery|view|article|comic)/(\d+)""", RegexOption.IGNORE_CASE)
    )

    override fun download(
        taskId: Long,
        input: String,
        outputDir: String,
        options: Map<String, String>,
        callback: ProgressCallback
    ) {
        try {
            val raw = input.trim()
            val id = InputParser.resolveId(
                input = raw,
                patterns = idPatterns,
                allowPureDigits = true,
                allowBareToken = false
            )?.takeIf { it.all { c -> c.isDigit() } }

            if (id != null) {
                downloadById(taskId, id, outputDir, options, callback)
            } else if (InputParser.isUrl(raw)) {
                callback.onFailed(taskId, FailReason.msg(FailReason.INPUT, "无法从链接中识别 articleId：$raw"))
            } else {
                // 关键词搜索（对齐 hp3 的 search 通道）
                searchAndDownload(taskId, raw, outputDir, options, callback)
            }
        } catch (e: Http.NetException) {
            callback.onFailed(taskId, FailReason.msg(e.kind, "HentaiPaw 连接失败（该站大陆直连被阻断，请确认系统代理已开启：${e.message}）"))
        } catch (e: Exception) {
            callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, e.message ?: "下载失败"))
        }
    }

    // ===== 按 id 下载 =====

    private fun downloadById(
        taskId: Long, id: String, outputDir: String,
        options: Map<String, String>, callback: ProgressCallback
    ) {
        callback.onProgress(taskId, 0f, 0, 0, "解析阅读页…")
        val (title, pages) = fetchImageList(id)
            ?: run {
                callback.onFailed(taskId, "无法解析该漫画（articleId=$id，可能站点结构变化）")
                return
            }
        if (pages.isEmpty()) {
            callback.onFailed(taskId, "未找到图片（articleId=$id）")
            return
        }
        saveGallery(taskId, id, title, pages, outputDir, options, callback)
    }

    // ===== 关键词搜索 =====

    private fun searchAndDownload(
        taskId: Long, keyword: String, outputDir: String,
        options: Map<String, String>, callback: ProgressCallback
    ) {
        callback.onProgress(taskId, 0f, 0, 0, "搜索「$keyword」…")
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        // 严格对齐 hp3.py：/articles/search?keyword=<kw>&page=1
        val url = "${BASE}articles/search?keyword=$encoded&page=1"
        val html = fetch(url)
        // 严格对齐 hp3.py 的 parse_search：
        // 卡片形如 <a href="/articles/3851613"><div..><img alt="标题" ...>
        // href 与 alt 间可能隔着标签，用 DOTALL 限长 400 字符跨标签匹配，
        // 并用负向前瞻避免跨卡片误配。
        val id = Regex("""href="/articles/(\d+)"(?:(?!href="/articles/).){0,400}?alt="([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            // 兜底：结构再变/缺 alt 时，直接抽任意 /articles/<id>
            ?: Regex("""/articles/(\d+)""").find(html)?.groupValues?.get(1)
        if (id == null) {
            callback.onFailed(taskId, "未找到「$keyword」的相关漫画（可能无结果或站点结构变化）")
            return
        }
        downloadById(taskId, id, outputDir, options, callback)
    }

    // ===== 保存整话 =====

    private fun saveGallery(
        taskId: Long, id: String, title: String, pages: List<String>,
        outputDir: String, options: Map<String, String>, callback: ProgressCallback
    ) {
        val safeTitle = sanitize(title.ifBlank { "hentaipaw_$id" })
        val dir = File(outputDir, safeTitle)
        dir.mkdirs()

        val total = pages.size
        callback.onProgress(taskId, 0f, 0, total, "开始下载 $total 张")
        val ok = ImagePdf.downloadImageList(
            urls = pages,
            dir = dir,
            referer = BASE,                 // 防盗链：CDN 需要站点 Referer
            headers = mapOf("User-Agent" to UA),
            prefix = "page",
            onProgress = { done, tot ->
                callback.onProgress(taskId, done.toFloat() / tot, done, tot, "")
            },
            // 图片同样优先走 Cronet（与阅读页同一 Chromium TLS 栈），失败自动回退 Http
            fetcher = if (CronetHttp.isAvailable()) { u, dest ->
                try {
                    CronetHttp.downloadToFile(u, dest, referer = BASE,
                        headers = mapOf("User-Agent" to UA), timeoutMs = 90000)
                } catch (e: Exception) {
                    DiagLog.error("HentaiPaw", "Cronet 图片失败，回退 Http：${e.javaClass.simpleName}: ${e.message}")
                    Http.downloadToFile(u, dest, BASE, mapOf("User-Agent" to UA))
                }
            } else null
        )
        if (ok == 0) {
            callback.onFailed(taskId, "图片全部下载失败（可能需代理，或 CDN 防盗链变化）")
            return
        }

        val pdfMode = options["autoPdf"] ?: "1"
        var outPath = dir.absolutePath
        // autoPdf: 0=关闭 1=自动合成（旧"询问"已移除）
        if (pdfMode != "0") {
            val pdfFile = File(dir, "$safeTitle.pdf")
            if (ImagePdf.imagesToPdf(dir, pdfFile) > 0) outPath = pdfFile.absolutePath
        }
        callback.onDone(taskId, outPath)
    }

    // ===== HTTP =====

    /**
     * 取页面 HTML。**优先走 Cronet（Chromium TLS 指纹，可过 Cloudflare 的 JA3 风控）**；
     * Cronet 不可用/失败时回退 java.net Http（保持原有兜底行为）。
     */
    private fun fetch(url: String): String {
        if (CronetHttp.isAvailable()) {
            try {
                return CronetHttp.getText(url, referer = BASE, headers = mapOf("User-Agent" to UA),
                    accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            } catch (e: Exception) {
                DiagLog.error("HentaiPaw", "Cronet 取页失败，回退 Http：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return Http.getText(url, referer = BASE, headers = mapOf("User-Agent" to UA))
    }

    // ===== 解析（严格对齐 hp3.py）=====

    /**
     * 从阅读页抠出 (标题, 图片URL列表)。
     *
     * 主路径：从 Next.js 内联 JSON 的 "slides" 数组里取每个元素的 src。
     * 兜底：用 CDN 主机正则全页扫描。
     */
    private fun fetchImageList(articleId: String, htmlOverride: String? = null): Pair<String, List<String>>? {
        val html = htmlOverride ?: run {
            val url = "${BASE}viewer?articleId=$articleId&page=1"
            DiagLog.error("HentaiPaw", "WebView 未取到 HTML，回退 Http 直连：$url")
            fetch(url)
        }
        if (html.isBlank()) return null

        val title = extractTitle(html) ?: "hentaipaw_$articleId"

        val imgs = LinkedHashSet<String>()

        // 1) 主路径：抠 "slides":[...] 数组内的 src
        extractSlides(html).forEach { imgs.add(it) }

        // 2) 兜底：直接扫 CDN 主机链接（含转义形式 \u002F）
        if (imgs.isEmpty()) {
            val normalized = html
                .replace("\\u002F", "/")
                .replace("\\/", "/")
            Regex("""https?://$CDN_HOST/[^"'\s\\]+?\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
                .findAll(normalized)
                .forEach { imgs.add(it.value) }
        }

        if (imgs.isEmpty()) return null
        DiagLog.info("HentaiPaw", "articleId=$articleId 解析到 ${imgs.size} 张图, title=$title")
        return title to imgs.toList()
    }

    /**
     * 抠 "slides":[...] 数组里的 src。
     * 先定位 "slides" 后的第一个 '['，再按括号配平截取数组体，然后逐个取 "src":"..."。
     * 这样即使页面里还有其它 JSON 也不会误抓。
     */
    private fun extractSlides(html: String): List<String> {
        val key = Regex(""""slides"\s*:\s*\[""")
        val km = key.find(html) ?: return emptyList()
        val start = html.indexOf('[', km.range.first)
        if (start < 0) return emptyList()

        // 括号配平截取数组体
        var depth = 0
        var end = -1
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (c == '[') depth++
            else if (c == ']') {
                depth--
                if (depth == 0) { end = i; break }
            }
            i++
        }
        if (end < 0) return emptyList()
        val arrayBody = html.substring(start + 1, end)

        val out = LinkedHashSet<String>()
        // 逐个对象的 src 字段（兼容转义）
        Regex(""""src"\s*:\s*"([^"]+)"""").findAll(arrayBody).forEach { m ->
            val u = m.groupValues[1]
                .replace("\\u002F", "/")
                .replace("\\/", "/")
            if (u.startsWith("http")) out.add(u)
        }
        return out.toList()
    }

    private fun extractTitle(html: String): String? {
        // Next.js 通常会带 og:title
        Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let {
                val t = decodeEntities(it.groupValues[1]).trim()
                if (t.isNotBlank()) return t
            }
        Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.let {
                val t = decodeEntities(it.groupValues[1]).trim()
                if (t.isNotBlank()) return t
            }
        return null
    }

    private fun decodeEntities(s: String): String {
        val amp = Char(38).toString()
        return s
            .replace(amp + "lt;", "<")
            .replace(amp + "gt;", ">")
            .replace(amp + "quot;", "\"")
            .replace(amp + "#39;", "'")
            .replace(amp + "apos;", "'")
            .replace(amp + "amp;", "&")
            .replace(amp + "nbsp;", " ")
    }

    private fun sanitize(s: String): String {
        var name = s.replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (name.length > 80) name = name.substring(0, 80)
        return name.ifBlank { "untitled" }
    }
}
