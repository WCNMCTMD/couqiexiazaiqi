package com.java.myapplication.site

import com.java.myapplication.data.CookieStore
import com.java.myapplication.engine.DiagLog
import com.java.myapplication.engine.Downloader
import com.java.myapplication.engine.FailReason
import com.java.myapplication.engine.Http
import com.java.myapplication.engine.InputParser
import com.java.myapplication.engine.ProgressCallback
import java.io.File
import java.net.URLEncoder

/**
 * 推特 / X 视频下载器（权威链路对齐 yt-dlp 的 twitter.py）。
 *
 * ## 为什么不能抓网页
 * x.com 的推文页是 JS 渲染的 SPA，`GET https://x.com/.../status/<id>` 返回的 HTML
 * **不包含视频直链**（video.twimg.com/...mp4）。所以"抓网页正则找 mp4"是错误路线。
 *
 * ## 权威链路（三级降级，2026-10 实测定案）
 * 主链路 = **X Syndication API**（官方嵌入式推文接口，公开、免登录、无 queryId）：
 * 1. GET https://cdn.syndication.twimg.com/tweet-result?id=<id>&lang=en&token=0
 *      header: 普通浏览器 UA 即可（本 App 的 Http 默认 UA 可用）
 *      -> 响应 JSON：{"__typename":"Tweet",...,"mediaDetails":[...]}
 *         视频直链在 mediaDetails[*].video_info.variants[*].url
 *         结构（video_info.variants）与旧 statuses/show 完全一致，
 *         故 collectVariants/pickBestMp4 **无需改动**即可复用。
 * 2. 下载 variants 里 bitrate 最高的 mp4（跳过 .m3u8）。
 *
 * 降级链（主链路失败时依次尝试）：
 *   B. 登录态 GraphQL `TweetResultByRestId`（需导入 auth_token+ct0，queryId 会变）
 *   C. 旧游客模式（guest_token + statuses/show，2026 起多已 404，仅兜底）
 *
 * 为何以 Syndication 为主：实测对同一条推文可稳定返回全部 mp4 变体（含 1080P），
 * 且**不需要用户任何 cookie**——安全性最好，也不受 queryId 漂移影响。
 *
 * ## 安全红线
 * 本类内置的 Bearer 是 **X 网页版对所有匿名访客公开的 web Bearer**（yt-dlp 源码
 * twitter.py::TwitterBaseIE._AUTH 原样），**不包含任何用户私人凭证**。
 * 若使用 cookie 模式，cookie 一律由使用者在运行时导入（Http.cookieHeader），
 * 本类不内置、不硬编码任何用户 token / cookie / 密钥。
 */
class TwitterDownloader : Downloader {
    override val siteId = "twitter"

    private companion object {
        const val API_BASE = "https://api.x.com/1.1/"

        /** X 官方嵌入式推文接口（公开、免登录、无 queryId） */
        const val SYNDICATION_BASE = "https://cdn.syndication.twimg.com/tweet-result"

        /** X 网页版公开的匿名 web Bearer（源自 yt-dlp twitter.py::_AUTH，非用户私人凭证） */
        const val PUBLIC_WEB_BEARER =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
    }

    /** 统一输入解析：识别 /status/{id} 链接（x.com / twitter.com）、/i/web/status/{id}、纯数字 id */
    private val statusPatterns = listOf(
        Regex("""(?:twitter\.com|x\.com)/[^/]+/status(?:es)?/(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""/i/web/status/(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""status(?:es)?/(\d+)""", RegexOption.IGNORE_CASE)
    )

    override fun download(
        taskId: Long,
        input: String,
        outputDir: String,
        options: Map<String, String>,
        callback: ProgressCallback
    ) {
        try {
            val statusId = InputParser.resolveId(
                input = input,
                patterns = statusPatterns,
                allowPureDigits = true,
                allowBareToken = false
            )
            if (statusId == null) {
                callback.onFailed(taskId, FailReason.msg(FailReason.INPUT, "请粘贴推文链接（x.com/…/status/…）或推文 id"))
                return
            }

            // ② 取推文 JSON：三级降级
            //    A. Syndication API（主链路，公开免登录，2026-10 实测稳定）
            //    B. 登录态 GraphQL（需已导入 auth_token+ct0）
            //    C. 旧游客模式（兜底）
            callback.onProgress(taskId, 0f, 0, 1, "解析推文…")
            var json: String? = null
            var lastErr: Exception? = null

            // A. 主链路：Syndication
            try {
                DiagLog.req("TWITTER", "主链路 Syndication API")
                json = fetchStatusJsonSyndication(statusId)
            } catch (e: Exception) {
                lastErr = e
                DiagLog.req("TWITTER", "Syndication 失败：${e.message}")
            }

            // B. 降级：登录态 GraphQL（仅当已导入登录 cookie）
            if (json.isNullOrBlank() && !CookieStore.valueOf("auth_token").isNullOrBlank()) {
                try {
                    DiagLog.req("TWITTER", "降级 GraphQL 登录态")
                    json = fetchStatusJsonLoggedIn(statusId)
                } catch (e: Exception) {
                    lastErr = e
                    DiagLog.req("TWITTER", "GraphQL 失败：${e.message}")
                }
            }

            // C. 兜底：旧游客模式
            if (json.isNullOrBlank()) {
                try {
                    DiagLog.req("TWITTER", "兜底 游客态 statuses/show")
                    val guestToken = fetchGuestToken()
                    json = fetchStatusJson(statusId, guestToken)
                } catch (e: Exception) {
                    lastErr = e
                    DiagLog.req("TWITTER", "游客态失败：${e.message}")
                }
            }

            if (json.isNullOrBlank()) {
                throw lastErr ?: Http.NetException(FailReason.NETWORK, "所有取推文接口均失败")
            }

            // ③ 选最高 bitrate 的 mp4 直链
            val videoUrl = pickBestMp4(json)
                ?: run {
                    callback.onFailed(
                        taskId,
                        FailReason.msg(FailReason.PARSE, "未解析到视频直链（该推文可能不含视频/含受限内容）")
                    )
                    return
                }
            DiagLog.info("TWITTER", "选中直链 bitrate=${extractBitrateBest(json) ?: -1}")

            val dir = File(outputDir)
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "x_$statusId.mp4")

            callback.onProgress(taskId, 0f, 0, 1, "下载中")
            Http.downloadToFile(videoUrl, out, referer = "https://x.com/")
            if (out.length() <= 0) {
                callback.onFailed(taskId, FailReason.msg(FailReason.EMPTY, "下载失败（文件为空）"))
                return
            }
            callback.onProgress(taskId, 1f, 1, 1, "")
            callback.onDone(taskId, out.absolutePath)
        } catch (e: Http.NetException) {
            callback.onFailed(taskId, FailReason.msg(e.kind, "推特连接失败（大陆直连被阻断，请确认系统代理已开启：${e.message}）"))
        } catch (e: Exception) {
            callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, e.message ?: "下载失败"))
        }
    }

    /**
     * 【主链路】X Syndication API -> 推文 JSON。
     * GET https://cdn.syndication.twimg.com/tweet-result?id=<id>&lang=en&token=0
     * 公开、免登录、无 queryId；视频直链在 mediaDetails[*].video_info.variants[*]。
     * 实测（2026-10）对含视频推文可稳定返回全部 mp4 变体。
     */
    private fun fetchStatusJsonSyndication(statusId: String): String {
        val url = "$SYNDICATION_BASE?id=$statusId&lang=en&token=0"
        val body = Http.getText(url, referer = "https://x.com/")
        DiagLog.req("TWITTER", "Syndication 响应长度=${body.length}")
        // 判空/错误响应：syn 对已删除/受限推文会返回错误或空
        if (body.isBlank() || !body.contains("\"id_str\"")) {
            throw Http.NetException(FailReason.PARSE, "Syndication 无有效推文数据（推文可能已删除/受限）")
        }
        return body
    }

    /** guest/activate.json -> guest_token */
    private fun fetchGuestToken(): String {
        val body = Http.postText(
            url = "${API_BASE}guest/activate.json",
            body = "",
            headers = mapOf("Authorization" to "Bearer $PUBLIC_WEB_BEARER")
        )
        val token = Regex(""""guest_token"\s*:\s*"(\d+)"""").find(body)?.groupValues?.get(1)
        DiagLog.req("TWITTER", "guest_token=${if (token.isNullOrBlank()) "空" else "已获取(len=${token.length})"}")
        return token ?: throw Http.NetException(FailReason.PARSE, "guest_token 解析失败（响应片段：${DiagLog.snippet(body.toByteArray())}）")
    }

    /** statuses/show/<id>.json -> 原始 JSON 字符串 */
    private fun fetchStatusJson(statusId: String, guestToken: String): String {
        val url = "${API_BASE}statuses/show/$statusId.json" +
            "?tweet_mode=extended&include_cards=1&include_reply_count=1&include_user_entities=0" +
            "&cards_platform=Web-12"
        val headers = mutableMapOf(
            "Authorization" to "Bearer $PUBLIC_WEB_BEARER",
            "x-guest-token" to guestToken,
            "x-twitter-active-user" to "yes",
            "x-twitter-client-language" to "en"
        )
        // 若有导入 cookie，附带 OAuth2Session 提示头，提高成功率
        if (!Http.cookieHeader.isNullOrBlank()) {
            headers["x-twitter-auth-type"] = "OAuth2Session"
        }
        return Http.getText(url, referer = "https://x.com/", headers = headers)
    }

    /**
     * 登录态：调用 GraphQL `TweetResultByRestId` 拉推文 JSON。
     *
     * 背景：v1.1 `statuses/show` 对游客令牌已收紧（返回 404），登录态也常不稳定。
     * GraphQL 的 TweetResultByRestId 是网页端当前实际使用的取单条推文接口，
     * 要求：
     *  - Cookie 带 auth_token（登录凭证）；
     *  - 请求头带 `x-csrf-token: <ct0>`（ct0 与 auth_token 同属同一登录会话）；
     *  - 请求头带公开 web Bearer（Authorization）。
     *
     * ⚠️ queryId 会随 X 前端发版变化，本处 queryId **未经联网核验**，仅为降级兜底；
     * 若失效接口会返回 404/400，日志会打印。**主链路已改为 Syndication API**，
     * 正常情况下不会走到这里，因此本方法的 queryId 时效性不构成主流程风险。
     */
    private fun fetchStatusJsonLoggedIn(statusId: String): String {
        val ct0 = CookieStore.valueOf("ct0")
            ?: throw Http.NetException(FailReason.NEED_COOKIE, "已登录但缺少 ct0（CSRF token），请重新导出完整 cookie")

        val variables = URLEncoder.encode(
            """{"tweetId":"$statusId","withCommunity":false,"includePromotedContent":false,"withVoice":false}""",
            "UTF-8"
        )
        val features = URLEncoder.encode(
            """{"creator_subscriptions_tweet_preview_api_enabled":true,"tweetypie_unmention_optimization_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":false,"tweet_awards_web_tipping_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"responsive_web_graphql_exclude_directive_enabled":true,"verified_phone_label_enabled":false,"responsive_web_media_download_video_enabled":false,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_enhance_cards_enabled":false}""",
            "UTF-8"
        )
        val url = "https://api.x.com/graphql/2ICDjqPd81tulZcYrtpTuQ/TweetResultByRestId" +
            "?variables=$variables&features=$features"

        val headers = mapOf(
            "Authorization" to "Bearer $PUBLIC_WEB_BEARER",
            "x-csrf-token" to ct0,
            "x-twitter-active-user" to "yes",
            "x-twitter-auth-type" to "OAuth2Session",
            "x-twitter-client-language" to "en"
        )
        val body = Http.getText(url, referer = "https://x.com/", headers = headers)
        DiagLog.req("TWITTER", "GraphQL 响应长度=${body.length}")
        return body
    }

    /**
     * 从 statuses/show 的 JSON 里选最高 bitrate 的 mp4 直链。
     * 结构：extended_entities.media[*].video_info.variants[*] = {bitrate, content_type, url}
     * 规则（对齐 yt-dlp）：跳过 .m3u8，取 content_type=video/mp4 且 bitrate 最大者；
     * 若无 bitrate 字段则取第一个 mp4。
     */
    private fun pickBestMp4(json: String): String? {
        val variants = collectVariants(json)
        if (variants.isEmpty()) return null
        // 过滤出 mp4（url 含 .mp4 且不含 .m3u8）
        val mp4s = variants.filter { it.url.contains(".mp4", ignoreCase = true) && !it.url.contains(".m3u8", ignoreCase = true) }
        val pool = mp4s.ifEmpty { variants.filter { it.url.contains(".mp4", ignoreCase = true) } }
        if (pool.isEmpty()) return null
        return pool.maxByOrNull { it.bitrate ?: -1 }?.url
    }

    private fun extractBitrateBest(json: String): Int? =
        collectVariants(json).filter { it.url.contains(".mp4", ignoreCase = true) }.maxOfOrNull { it.bitrate ?: -1 }

    private data class Variant(val url: String, val bitrate: Int?)

    /**
     * 定向从 JSON 文本里抽取所有 video variants。
     * 不引入 JSON 库：利用 variants 数组对象的固定字段顺序（url/bitrate/content_type），
     * 逐个花括号块解析。容错：字段顺序不保证时分别用独立正则兜底。
     */
    private fun collectVariants(json: String): List<Variant> {
        val result = mutableListOf<Variant>()
        val seen = HashSet<String>()
        // 扫描 **所有** "variants":[ ... ] 段（Syndication 的 JSON 里可能出现多处，
        // 如 mediaDetails[*].video_info.variants 与顶层 video.variants），逐一解析取并集。
        var searchFrom = 0
        while (true) {
            val vIdx = json.indexOf("\"variants\"", searchFrom)
            if (vIdx < 0) break
            val arrStart = json.indexOf('[', vIdx)
            if (arrStart < 0) break
            // 从 arrStart 开始做括号配平，取出整个数组文本
            var depth = 0
            var end = -1
            var i = arrStart
            while (i < json.length) {
                when (json[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) { end = i; break }
                    }
                }
                i++
            }
            if (end < 0) { searchFrom = arrStart + 1; continue }
            val arrText = json.substring(arrStart + 1, end.coerceAtLeast(arrStart + 1))

            // 按对象切分（每个 variant 是一个 {...}）
            var j = 0
            while (j < arrText.length) {
                val ob = arrText.indexOf('{', j)
                if (ob < 0) break
                var d = 0
                var cb = -1
                var k = ob
                while (k < arrText.length) {
                    when (arrText[k]) {
                        '{' -> d++
                        '}' -> {
                            d--
                            if (d == 0) { cb = k; break }
                        }
                    }
                    k++
                }
                if (cb < 0) break
                val obj = arrText.substring(ob + 1, cb)
                val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                if (!url.isNullOrBlank()) {
                    val br = Regex(""""bitrate"\s*:\s*(\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull()
                    val clean = url.replace("\\/", "/").replace("\\u002F", "/")
                    if (seen.add(clean)) result.add(Variant(clean, br))
                }
                j = cb + 1
            }
            searchFrom = end + 1
        }
        return result
    }
}
