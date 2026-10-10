package com.java.myapplication.site

import com.java.myapplication.engine.DiagLog
import com.java.myapplication.engine.Downloader
import com.java.myapplication.engine.FailReason
import com.java.myapplication.engine.HlsDownloader
import com.java.myapplication.engine.Http
import com.java.myapplication.engine.InputParser
import com.java.myapplication.engine.ProgressCallback
import java.io.File

/**
 * PornHub 下载器（源 phdl_termux.sh）。
 *
 * 站点逆向结论：
 * - 原脚本完全封装 yt-dlp（`yt-dlp -f bv*+ba/b -S res:720 --referer https://www.pornhub.com/`），
 *   无自研直链逻辑，因此无法照抄其算法。
 * - 脚本明确警告：裸 curl 直接探视频分片会被 CDN 返回 410（需要浏览器指纹 / Referer 校验）。
 * - yt-dlp 的 PH extractor 已从本地 wheel 移除，无权威源码可参照。
 *
 * 本实现务实路线（页面 flashvars 通道）：
 *   1. GET 观看页（带 Referer）。
 *   2. 从页面 `<script>` 里定位 `flashvars_*` 对象，抓 `mediaDefinitions` 数组。
 *   3. 解析每个元素里的 `videoUrl`（可能为 mp4 或 m3u8）与 `quality`/`format`。
 *   4. 优先选最高画质的 mp4；若只有 m3u8 则走公共 [HlsDownloader] 分片下载。
 *   5. 下载分片/直链一律带 **页面地址** 作 Referer（PH 防盗链关键）。
 *
 * 风险（诚实标注）：若 PH 已把直链下发改成动态 JS 混淆 + base64 校验（历史上
 * `linkProxyUrl` 即对 Referer 做分段 base64 校验），本路由仍可能在下载阶段被 410。
 * 届时真机诊断日志会记录失败分片与 HTTP 状态，便于定位。
 *
 * 输入兼容：完整链接（含 viewkey 参数亦可）、/view_video.php?viewkey=xxx。
 * 零密钥、零 cookie（公开内容无需登录）。
 */
class PornhubDownloader : Downloader {
    override val siteId = "pornhub"

    private val vkPatterns = listOf(
        Regex("""viewkey=([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE),
        Regex("""/view_video\.php\?viewkey=([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
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
            if (!InputParser.isUrl(raw)) {
                callback.onFailed(taskId, FailReason.msg(FailReason.INPUT, "请粘贴完整的 PornHub 视频链接"))
                return
            }
            val viewKey = InputParser.extractFirst(raw, vkPatterns)
            val pageUrl = if (viewKey != null) {
                "https://www.pornhub.com/view_video.php?viewkey=$viewKey"
            } else {
                InputParser.normalizeUrl(raw)
            }

            callback.onProgress(taskId, 0f, 0, 1, "解析视频地址…")
            DiagLog.info("PH", "页面：${DiagLog.maskUrl(pageUrl)}")
            val html = Http.getText(pageUrl, referer = "https://www.pornhub.com/")

            val media = extractMediaDefinitions(html)
            if (media == null || media.urls.isEmpty()) {
                callback.onFailed(
                    taskId,
                    FailReason.msg(FailReason.PARSE, "未从页面 flashvars 解析到 mediaDefinitions（站点结构可能已变化）")
                )
                return
            }
            DiagLog.info("PH", "发现 ${media.urls.size} 条媒体定义：${media.urls.map { it.take(60) }}")

            val title = InputParser.sanitizeFileName(extractTitle(html) ?: "pornhub_video", 80)
            val dir = File(outputDir)
            if (!dir.exists()) dir.mkdirs()

            // 优先 mp4 直链；否则退到 m3u8
            val mp4 = pickBestMp4(media.urls)
            if (mp4 != null) {
                val out = File(dir, "$title.mp4")
                callback.onProgress(taskId, 0f, 0, 1, "下载中")
                DiagLog.info("PH", "直链下载 mp4：${DiagLog.maskUrl(mp4)}")
                Http.downloadToFile(mp4, out, referer = pageUrl)
                if (out.length() <= 0) {
                    callback.onFailed(taskId, FailReason.msg(FailReason.EMPTY, "下载失败（文件为空，可能触发防盗链）"))
                    return
                }
                callback.onProgress(taskId, 1f, 1, 1, "")
                callback.onDone(taskId, out.absolutePath)
                return
            }

            val m3u8 = media.urls.firstOrNull { it.contains(".m3u8") }
            if (m3u8 != null) {
                // 先下到临时 .ts，再用 ffmpeg 转封装成可播放的 .mp4
                val tmpTs = File(dir, "$title.ts")
                val outMp4 = File(dir, "$title.mp4")
                callback.onProgress(taskId, 0f, 0, 1, "下载分片中…")
                DiagLog.info("PH", "HLS 下载：${DiagLog.maskUrl(m3u8)}")
                val res = HlsDownloader.download(m3u8, tmpTs, referer = pageUrl) { done, total, name ->
                    callback.onProgress(taskId, done.toFloat() / total, done, total, name)
                }
                callback.onProgress(taskId, 0f, 0, 1, "封装为 MP4…")
                val muxed = HlsDownloader.muxTsToMp4(tmpTs, outMp4)
                val finalFile = if (muxed) {
                    tmpTs.delete()
                    outMp4
                } else {
                    // ffmpeg 不可用时降级：保留原始 .ts（多数播放器仍可播放）
                    DiagLog.error("PH", "ffmpeg 转封装失败，保留原始 TS")
                    tmpTs
                }
                callback.onProgress(taskId, 1f, res.segments, res.segments, "")
                callback.onDone(taskId, finalFile.absolutePath)
                return
            }

            callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, "mediaDefinitions 中未找到可下载的 mp4/m3u8 地址"))
        } catch (e: Http.NetException) {
            // 时效站点兜底提示：410 Gone / 403 Forbidden 通常是直链已过期（PH 直链带 validfrom/expire 时间戳）
            val msg = e.message ?: ""
            val expired = e.kind == FailReason.HTTP && (msg.contains("410") || msg.contains("403"))
            val tip = if (expired) {
                "⏱ 链接可能已过期——PornHub 直链有时效，请回到视频页重新复制最新链接后尽快重试（间隔越短成功率越高）。"
            } else {
                "PornHub 连接失败（很可能需代理：$msg）"
            }
            callback.onFailed(taskId, FailReason.msg(e.kind, tip))
        } catch (e: Exception) {
            callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, e.message ?: "下载失败"))
        }
    }

    /** mediaDefinitions 解析结果 */
    private data class Media(val urls: List<String>)

    /**
     * 从页面里提取 flashvars 中 mediaDefinitions 数组的所有 videoUrl。
     * 思路：先把 http 转义（\/、\u002F）还原，再定位 "mediaDefinitions" 后的数组，
     * 用括号配平截出数组文本，然后逐个抓 "videoUrl"\s*:\s*"..."。
     */
    private fun extractMediaDefinitions(html: String): Media? {
        val decoded = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
        val keyIdx = decoded.indexOf("mediaDefinitions")
        if (keyIdx < 0) return null
        val arrStart = decoded.indexOf('[', keyIdx)
        if (arrStart < 0) return null
        val arrText = sliceBalancedArray(decoded, arrStart) ?: return null
        val urls = Regex(""""videoUrl"\s*:\s*"([^"]+)"""")
            .findAll(arrText)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("http") }
            .toList()
        return Media(urls)
    }

    /** 从 open 位置（'[' 处）截取到配平闭合的 ']'，返回数组文本（含方括号）。 */
    private fun sliceBalancedArray(s: String, openIdx: Int): String? {
        var depth = 0
        var i = openIdx
        var inStr = false
        var strCh = ' '
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == strCh) inStr = false
            } else {
                when (c) {
                    '"', '\'' -> { inStr = true; strCh = c }
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return s.substring(openIdx, i + 1) }
                }
            }
            i++
        }
        return null
    }

    /**
     * 选最高画质 mp4：优先含 "quality":"1080"… 的 videoUrl；退化到任意 mp4。
     *
     * 关键修正：PH 的 HLS 直链形如 `.../1080P_xxx.mp4/master.m3u8?...`，
     * 路径里既含 `.mp4` 又含 `.m3u8`（清单本身）。若用 `contains(".mp4")` 判定，
     * 会把「m3u8 清单地址」误当成 mp4 直链下载 → 只下到几百字节的清单文本（即"损坏视频"）。
     * 因此这里必须：
     *   1) 排除含 `.m3u8` 的地址；
     *   2) 仅当 URL 路径确实以 `.mp4` 结尾（可带 query）才算真·mp4 直链。
     */
    private fun pickBestMp4(urls: List<String>): String? {
        val mp4Re = Regex("""\.mp4(\?|#|$)""", RegexOption.IGNORE_CASE)
        val mp4s = urls.filter { url ->
            url.contains(".m3u8").not() &&
                url.substringBefore('?').substringBefore('#')
                    .let { !it.contains(".m3u8") } &&
                mp4Re.containsMatchIn(url)
        }
        if (mp4s.isEmpty()) return null
        val ranked = listOf("1080", "720", "480", "240")
        for (q in ranked) {
            val hit = mp4s.firstOrNull { it.contains(q) }
            if (hit != null) return hit
        }
        return mp4s.first()
    }

    private fun extractTitle(html: String): String? {
        val m = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return m.groupValues[1].trim()
    }
}
