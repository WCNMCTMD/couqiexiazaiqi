package com.java.myapplication.site

import com.java.myapplication.engine.Downloader
import com.java.myapplication.engine.FailReason
import com.java.myapplication.engine.Http
import com.java.myapplication.engine.InputParser
import com.java.myapplication.engine.ProgressCallback
import java.io.File
import java.net.URLEncoder

/**
 * cool18（禁忌书屋）小说下载器。
 *
 * ⚠️ 本实现严格对齐已跑通的成功脚本 `cool18_dl.py`（逐行参考，非自研猜测）：
 *
 * 站点实测结论（来自 cool18_dl.py 头部注释，均为实测非猜测）：
 * - Server nginx/1.31.0，无 Cloudflare / 无验证码，首页/搜索/帖子直连均 200
 * - BASE = https://www.cool18.com/bbs4/
 * - 搜索结果区：<ul class="post-list thread-list"> 内
 *       <li class="l-m1"><a href="...act=threadview&tid=XXX"><b>标题</b></a></li>
 *   ⚠️ 侧栏另有 <ul class="post-list"> 推荐区（固定 6 条），不能误抓 —— 必须匹配 thread-list
 * - 搜索 URL（关键！之前 App 用 act=threadlist&word= 是错的）：
 *       index.php?action=search&bbsdr=bbs4&act=threadsearch&app=forum&keywords=<kw>&submit=查询
 *   翻页参数为 &p=N（&p=1 等价首页、&p=2 等价第 2 页，服务器认该明文参数）
 * - 帖子正文位于 <div class="content-section" id="content-section"> 内，正文被 <pre> 包裹，
 *   段落分隔符 <p></p>，另有 <br />；单帖无分页
 * - 正文 div 可能嵌套子 div，必须做「div 配平」提取（不能简单匹配第一个 </div>）
 * - 需带常规浏览器 UA + Referer；请求需限速避免 429/403
 *
 * 输入自动识别（对齐脚本 normalize_input）：
 *   * 传"纯数字"          -> tid
 *   * 传"含 tid= 的链接"  -> 抽出 tid
 *   * 传"其它关键词"      -> 搜索模式
 */
class Cool18Downloader : Downloader {
    override val siteId = "cool18"

    private companion object {
        const val BASE = "https://www.cool18.com/bbs4/"
        // 浏览器 UA（对齐脚本 UA，实测可直连）
        const val UA = "Mozilla/5.0 (Linux; Android 14; 2312DRA50C) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        const val REFERER = BASE + "index.php"
    }

    // 各种可能的 tid 链接形态
    private val tidPatterns = listOf(
        Regex("""[?&]tid=(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""/threadview/(\d+)""", RegexOption.IGNORE_CASE)
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
            // 先尝试解析为 tid（链接或纯数字），解析不到则视为关键词搜索
            val tid = InputParser.resolveId(
                raw,
                patterns = tidPatterns,
                allowPureDigits = true,
                allowBareToken = false
            )?.takeIf { it.all { c -> c.isDigit() } }

            val dir = File(outputDir)
            if (!dir.exists()) dir.mkdirs()

            if (tid != null) {
                downloadByTid(taskId, tid, dir, callback)
            } else if (InputParser.isUrl(raw)) {
                // 是链接但抓不到 tid → 明确告诉用户
                callback.onFailed(taskId, FailReason.msg(FailReason.INPUT, "无法从链接中识别 tid：$raw"))
            } else {
                // 关键词搜索（对齐 run_search）
                val maxPages = options["maxPages"]?.toIntOrNull() ?: 3
                searchAndDownload(taskId, raw, maxPages, dir, callback)
            }
        } catch (e: Http.NetException) {
            callback.onFailed(taskId, FailReason.msg(e.kind, e.message ?: "网络错误"))
        } catch (e: Exception) {
            callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, e.message ?: "下载失败"))
        }
    }

    /** 按 tid 下载单帖 */
    private fun downloadByTid(taskId: Long, tid: String, dir: File, callback: ProgressCallback) {
        val url = "${BASE}index.php?app=forum&act=threadview&tid=$tid"
        callback.onProgress(taskId, 0f, 0, 1, "解析帖子…")
        val html = fetch(url)
        val title = extractTitle(html) ?: "帖子_$tid"
        val content = extractContent(html)
        if (content.isNullOrBlank() || content.length < 50) {
            callback.onFailed(taskId, "未解析到正文（tid=$tid，可能页面结构变化）")
            return
        }
        val safe = sanitize(title)
        val out = File(dir, "$safe.txt")
        val body = "标题: $title\n来源: $url\n\n" + "=".repeat(40) + "\n\n" + content + "\n"
        out.writeText(body, Charsets.UTF_8)
        callback.onProgress(taskId, 1f, 1, 1, "")
        callback.onDone(taskId, out.absolutePath)
    }

    /**
     * 关键词搜索并下载全部结果。
     * 对齐 cool18_dl.py 的 run_search：逐页拼 &p=N（脚本对 start_page>1 已实测服务器认该参数）。
     */
    private fun searchAndDownload(
        taskId: Long, keyword: String, maxPages: Int, dir: File, callback: ProgressCallback
    ) {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        // 搜索首页 URL（对齐 build_search_url）
        val submit = URLEncoder.encode("查询", "UTF-8")
        val baseSearch = "${BASE}index.php?action=search&bbsdr=bbs4&act=threadsearch" +
                "&app=forum&keywords=$encoded&submit=$submit"

        // 关键词子目录（对齐脚本：自动按关键词建子目录）
        val kwDir = File(dir, sanitize(keyword))
        kwDir.mkdirs()

        val collected = LinkedHashMap<String, String>() // tid -> title（去重保序）

        for (page in 1..maxPages) {
            val url = if (page == 1) baseSearch else "$baseSearch&p=$page"
            callback.onProgress(taskId, 0f, 0, 0, "搜索第 $page 页…")
            val html = fetch(url)
            val items = extractSearchItems(html)
            if (items.isEmpty()) break // 空页提前停（对齐脚本）
            for ((tid, title) in items) collected.putIfAbsent(tid, title)
            if (page < maxPages) Thread.sleep(1500) // 翻页间隔（脚本 page_delay=3s，取保守值）
        }

        if (collected.isEmpty()) {
            callback.onFailed(taskId, "未找到「$keyword」相关结果（可能关键词无结果或站点结构变化）")
            return
        }

        val total = collected.size
        var done = 0
        for ((tid, title) in collected) {
            val url = "${BASE}index.php?app=forum&act=threadview&tid=$tid"
            try {
                val html = fetch(url)
                val content = extractContent(html)
                if (!content.isNullOrBlank() && content.length >= 50) {
                    val safe = sanitize(if (title.isNotBlank()) title else "tid_$tid")
                    val out = File(kwDir, "$safe.txt")
                    val body = "标题: $title\n来源: $url\n\n" + "=".repeat(40) + "\n\n" + content + "\n"
                    out.writeText(body, Charsets.UTF_8)
                }
            } catch (_: Exception) {
                // 单帖失败不中断
            }
            done++
            callback.onProgress(taskId, done.toFloat() / total, done, total, "")
            Thread.sleep(300) // 全局限速（脚本 rate≈0.05，取更保守值适配手机）
        }
        callback.onDone(taskId, kwDir.absolutePath)
    }

    // ===== HTTP（带 UA + Referer）=====

    private fun fetch(url: String): String =
        Http.getText(url, referer = REFERER, headers = mapOf("User-Agent" to UA))

    // ===== 解析（严格对齐 cool18_dl.py 的解析规则）=====

    private fun extractTitle(html: String): String? {
        // 优先 <h1 class="main-title">，其次 <title>
        Regex("""<h1\s+class="main-title"[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.let { m ->
                val t = decodeEntities(stripTags(m.groupValues[1])).trim()
                if (t.isNotBlank()) return t
            }
        Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.let { m ->
                val t = decodeEntities(stripTags(m.groupValues[1])).trim()
                if (t.isNotBlank()) return t
            }
        return null
    }

    /**
     * 从 <ul class="... thread-list"> 区域解析搜索结果（对齐 parse_search_page）。
     * ⚠️ 必须限定 thread-list，否则会误抓侧栏固定推荐区。
     */
    private fun extractSearchItems(html: String): List<Pair<String, String>> {
        val section = Regex(
            """<ul\s+class="[^"]*thread-list[^"]*"[^>]*>(.*?)</ul>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1) ?: return emptyList()

        val out = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        // <li ...><a href="...tid=XXX" ...>标题</a>
        val li = Regex("""<li[^>]*>\s*<a\s+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        for (m in li.findAll(section)) {
            val href = m.groupValues[1]
            val tidM = Regex("""tid=(\d+)""").find(href) ?: continue
            val tid = tidM.groupValues[1]
            if (tid in seen) continue
            val title = decodeEntities(stripTags(m.groupValues[2])).trim()
            if (title.isBlank() || href.contains("{TID}")) continue
            seen.add(tid)
            out.add(tid to title)
        }
        return out
    }

    /**
     * 精确提取 content-section 内部（对齐 get_content 的 div 配平逻辑）。
     * 简单截取第一个 </div> 会在嵌套 div 时提前截断，这里按深度配平。
     */
    private fun extractContent(html: String): String? {
        val open = Regex("""<div\s+class="content-section"[^>]*>""").find(html) ?: return null
        val start = open.range.last + 1
        var depth = 1
        var pos = start
        // 逐标签扫描 div 开合，找配平点
        val tagRe = Regex("""</?div\b""")
        while (depth > 0) {
            val nm = tagRe.find(html, pos) ?: break
            val isClose = html.startsWith("</div", nm.range.first)
            if (isClose) depth-- else depth++
            pos = nm.range.first + 4
        }
        var inner = html.substring(start, pos.coerceAtMost(html.length))
        // 优先取 <pre> 内容
        Regex("""<pre[^>]*>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.let {
            inner = it.groupValues[1]
        }
        return cleanText(inner)
    }

    /** HTML 片段转纯文本，保留段落（对齐 clean_text） */
    private fun cleanText(raw: String): String {
        var t = raw
        t = t.replace(Regex("""<script.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        t = t.replace(Regex("""<style.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        t = t.replace(Regex("""<p\s*/?>\s*(</p>)?""", RegexOption.IGNORE_CASE), "\n")
        t = t.replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n")
        t = t.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        t = stripTags(t)
        t = decodeEntities(t)
        t = t.replace("\r\n", "\n").replace("\r", "\n")
        t = t.replace(Regex("""[ \t\u3000]+"""), " ")
        t = t.replace(Regex("""\n{3,}"""), "\n\n")
        return t.trim()
    }

    /** 去掉所有 HTML 标签 */
    private fun stripTags(s: String): String = s.replace(Regex("""<[^>]*>"""), "")

    /** 解码常见 HTML 实体 */
    private fun decodeEntities(s: String): String {
        val amp = Char(38).toString() // '&'
        return s
            .replace(amp + "lt;", "<")
            .replace(amp + "gt;", ">")
            .replace(amp + "quot;", "\"")
            .replace(amp + "#39;", "'")
            .replace(amp + "apos;", "'")
            .replace(amp + "amp;", "&")
            .replace(amp + "nbsp;", " ")
    }

    /** 清洗文件名非法字符（对齐 safe_filename，maxlen=80） */
    private fun sanitize(s: String): String {
        var name = s.replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (name.length > 80) name = name.substring(0, 80)
        return name.ifBlank { "untitled" }
    }
}