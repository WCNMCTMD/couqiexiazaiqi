package com.java.myapplication.site

import com.java.myapplication.engine.DiagLog
import com.java.myapplication.engine.Downloader
import com.java.myapplication.engine.FailReason
import com.java.myapplication.engine.Http
import com.java.myapplication.engine.ImagePdf
import com.java.myapplication.engine.InputParser
import com.java.myapplication.engine.JmCrypto
import com.java.myapplication.engine.ProgressCallback
import java.io.File

/**
 * 禁漫天堂（18comic）漫画下载器 —— 走 APP API（免 cookie / 免登录 / 无人机验证）。
 *
 * ===== 完整链路（对齐开源库 jmcomic 2.7.8 的 APP API）=====
 *  1) 输入归一化：/album/{id}、/chapter/{id}、/photos/{id}、纯数字。
 *  2) API 域名候选（www.cdnhjk.net 等）逐个尝试。
 *  3) 鉴权头：token = md5hex("{ts}{APP_TOKEN_SECRET}")、tokenparam = "{ts},{APP_VERSION}"。
 *  4) GET /album?id=<albumId> → 响应 {"code":200,"data":"<base64密文>"}
 *     → base64 decode → AES-ECB 解密(key = md5hex("{ts}{APP_DATA_SECRET}")) → 去 PKCS7 → JSON。
 *  5) album JSON 含 series（章节列表 [{"id","name","sort"}]）；无 series 则 id 本身是 photo。
 *  6) 对每个 chapterId：GET /chapter?id=<photoId> → {"id"(photo_id),"name","images":[文件名],"series_id"(album_id)}。
 *  7) scramble_id：GET /chapter_view_template?id=<photoId>&mode=vertical&page=0&app_img_shunt=1&express=off&v=<ts>
 *     （此接口密钥用 APP_TOKEN_SECRET_2='18comicAPPContent'，否则 403）
 *     响应文本正则抠 `var scramble_id = (\d+);`，失败回退常量 220980。
 *  8) 图片 URL = https://{图片域}/media/photos/{photoId}/{imgName}
 *     请求头带 X-Requested-With: com.JMComic3.app + Referer。
 *  9) 每张图下载到临时文件 → JmCrypto.scrambleNum 计算条带数 → ImagePdf.saveRemixed 重排落盘。
 *
 * ===== 安全 =====
 * 所有 secret 均为 jmcomic 开源库公开常量（非用户凭证），不内置任何用户 cookie/token。
 *
 * ===== 零依赖 =====
 * JSON 解析用本文件内手写的极简提取器（支持嵌套数组/对象），与 Updater.kt 一致，
 * 不引入 org.json / kotlinx.serialization，便于审阅（符合"可控、逆向不暴露"要求）。
 *
 * ===== 风险（已诚实标注）=====
 *  - API 域名 / 图片域名会变动；若全部候选域名失效，给出 NETWORK/PARSE 类错误并写诊断日志。
 *  - 若站点升级接口结构（字段名/加密方式变更），解析可能失败；诊断日志可捕捉响应片段定位。
 */
class JmDownloader : Downloader {
    override val siteId = "jm"

    private val apiDomains = listOf(
        "https://www.cdnhjk.net",
        "https://www.cdngwc.cc",
        "https://www.cdngwc.net",
        "https://www.cdngwc.club"
    )

    /**
     * 图片（CDN）域名候选。
     *
     * 【重要】逐字对齐 jmcomic 2.7.8 的 JmModuleConfig.DOMAIN_IMAGE_LIST（jm_config.py:189）。
     * 库里是 `random.choice(DOMAIN_IMAGE_LIST)` 随机选一个；它同时含 .cc 与 .net 两组域。
     * 实测（用户成功日志）：图片实际走的是 cdn-msp.jmapinodeudzn.net，
     * 而此处若只留旧域（且只用 [0]）必然下载失败 —— 故本次补全为 6 域并改为逐域重试。
     */
    private val imgDomains = listOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net"
    )

    private val idPatterns = listOf(
        Regex("""/album/(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""/chapter/(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""/photos?/(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""[/=](\d{3,})(?:[/?#]|$)""")
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
                raw,
                patterns = idPatterns,
                allowPureDigits = true,
                allowBareToken = false
            )
            if (id == null || !id.all { it.isDigit() }) {
                callback.onFailed(
                    taskId,
                    FailReason.msg(FailReason.INPUT, "无法识别漫画 id，请粘贴 /album/数字 或 /chapter/数字 链接，或纯数字车号")
                )
                return
            }

            callback.onProgress(taskId, 0f, 0, 1, "连接禁漫 API…")
            val api = pickApiDomain() ?: run {
                callback.onFailed(taskId, FailReason.msg(FailReason.NETWORK, "禁漫 API 域名全部不可用（可能需代理）"))
                return
            }
            DiagLog.info(siteId, "使用 API 域名：${DiagLog.maskUrl(api)}")

            // 1) 拉取 album 详情
            val albumText = fetchDecoded(api, "/album", id)
            if (albumText == null) {
                callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, "album 接口解密/解析失败（id=$id）"))
                return
            }

            val title = JmJson.str(albumText, "name").ifBlank { "jm_$id" }
            val seriesRaw = JmJson.arrayObjects(albumText, "series")

            val chapters = ArrayList<Pair<String, String>>()
            for (s in seriesRaw) {
                val cid = JmJson.str(s, "id")
                if (cid.isNotBlank()) {
                    chapters.add(cid to JmJson.str(s, "name").ifBlank { cid })
                }
            }
            if (chapters.isNotEmpty()) {
                DiagLog.info(siteId, "album 含 ${chapters.size} 个章节")
            }
            if (chapters.isEmpty()) {
                chapters.add(id to title)
                DiagLog.info(siteId, "无 series，按单章节处理（photo id=$id）")
            }

            val safeTitle = InputParser.sanitizeFileName(title, 60)
            val rootDir = File(outputDir, safeTitle)
            rootDir.mkdirs()

            var totalSaved = 0
            var totalBytes = 0L
            val multiChapter = chapters.size > 1

            for ((ci, ch) in chapters.withIndex()) {
                val (chapterId, chapterName) = ch
                callback.onProgress(taskId, ci.toFloat() / chapters.size, ci, chapters.size, "解析章节：$chapterName")

                val chapterText = fetchDecoded(api, "/chapter", chapterId)
                if (chapterText == null) {
                    DiagLog.error(siteId, "chapter 接口失败，跳过：$chapterId")
                    continue
                }

                val photoId = JmJson.str(chapterText, "id").ifBlank { chapterId }
                val albumId = JmJson.str(chapterText, "series_id").ifBlank { id }
                val images = JmJson.stringArray(chapterText, "images")
                if (images.isEmpty()) continue

                val scrambleId = fetchScrambleId(api, photoId)
                DiagLog.info(siteId, "章节 $photoId：${images.size} 张图，scramble_id=$scrambleId, aid=$photoId（num 用 photoId，非 series_id=$albumId）")

                val chDir = if (multiChapter) File(rootDir, InputParser.sanitizeFileName(chapterName, 50)) else rootDir
                chDir.mkdirs()
                val tmpDir = File(chDir, ".tmp").apply { mkdirs() }

                for ((pi, imgName) in images.withIndex()) {
                    val tmp = File(tmpDir, "raw_${pi + 1}")
                    try {
                        // 逐域重试：对齐 jmcomic 的 random.choice(DOMAIN_IMAGE_LIST) 容错思想，
                        // 哪个域能取到图就用哪个（不再死磕 imgDomains[0]）。
                        var n = -1L
                        var usedDomain = ""
                        for (dom in imgDomains) {
                            val imgUrl = "https://$dom/media/photos/$photoId/$imgName"
                            try {
                                val got = Http.downloadToFile(imgUrl, tmp, referer = api + "/", headers = imageHeaders(api))
                                if (got > 0 && ImagePdf.isImageFile(tmp)) {
                                    n = got; usedDomain = dom; break
                                }
                            } catch (e: Exception) {
                                // 该域失败，继续下一个
                            }
                            tmp.delete()
                        }
                        if (n <= 0 || !ImagePdf.isImageFile(tmp)) {
                            DiagLog.error(siteId, "图片非法或全部图片域下载失败：$imgName（试过 ${imgDomains.size} 个域）")
                            tmp.delete()
                            continue
                        }
                        if (usedDomain.isNotEmpty() && usedDomain != imgDomains[0]) {
                            DiagLog.info(siteId, "图片域名命中备用域：$usedDomain（$imgName）")
                        }
                        // 【关键】对齐 jmcomic：num = get_num(scramble_id, aid, filename)
                        //   - aid       = photo_id（jm_entity.py:276 aid=photo_id；NOT album/series_id！）
                        //   - filename  = 不含后缀的文件名（jm_entity.py:225 img_file_name # without suffix）
                        // 之前误用 albumId（series_id，实测常为空→0），导致 aid<scramble_id → num=0 →
                        // 不重排直接输出原始乱序图，表现为"画面排布错乱"。
                        val num = JmCrypto.scrambleNum(
                            scrambleId,
                            photoId.toLongOrNull() ?: 0L,
                            imgName.substringBeforeLast('.')
                        )
                        val dest = File(chDir, String.format("page_%03d.jpg", pi + 1))
                        if (ImagePdf.saveRemixed(tmp, dest, num)) {
                            totalSaved++
                            totalBytes += dest.length()
                        } else {
                            DiagLog.error(siteId, "图片重排失败：$imgName (num=$num)")
                        }
                        tmp.delete()
                    } catch (e: Exception) {
                        DiagLog.error(siteId, "单图异常：${e.message}")
                        tmp.delete()
                    }
                    val base = ci * 100 + pi
                    callback.onProgress(taskId, base.toFloat() / (chapters.size * 100), pi + 1, images.size, chapterName)
                }
                tmpDir.delete()
            }

            if (totalSaved == 0) {
                callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, "未成功下载任何图片（域名失效/结构变更/需代理）"))
                return
            }

            val pdfMode = options["autoPdf"] ?: "1"
            var outPath = rootDir.absolutePath
            // autoPdf: 0=关闭 1=自动合成（旧"询问"已移除）
            if (!multiChapter && pdfMode != "0") {
                val pdfFile = File(rootDir, "$safeTitle.pdf")
                if (ImagePdf.imagesToPdf(rootDir, pdfFile) > 0) outPath = pdfFile.absolutePath
            }
            DiagLog.info(siteId, "完成：$totalSaved 张图，共 $totalBytes 字节（pdfMode=$pdfMode）")
            callback.onDone(taskId, outPath)
        } catch (e: Http.NetException) {
            callback.onFailed(taskId, FailReason.msg(e.kind, "禁漫连接失败（可能需代理）：${e.message}"))
        } catch (e: Exception) {
            callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, e.message ?: "下载失败"))
        }
    }

    /**
     * 逐个尝试 API 域名，返回第一个可用的（能成功 GET /setting）。
     *
     * 【重要】对齐 jmcomic setting()：一次 /setting 请求同时完成两件事——
     *   ① 探测域名是否可用；② 读 jm3_version 动态更新 APP_VERSION（若服务端较新）。
     * /setting 的响应是**明文 JSON**（不是加密 data），故直接 JmJson 取值即可。
     */
    private fun pickApiDomain(): String? {
        for (d in apiDomains) {
            try {
                val text = Http.getText("$d/setting", referer = "$d/", headers = appHeaders())
                // 顺手尝试更新 APP_VERSION（对齐 jmcomic FLAG_USE_VERSION_NEWER_IF_BEHIND）
                val ver = JmJson.str(text, "jm3_version")
                if (ver.isNotBlank()) {
                    if (JmCrypto.updateAppVersionIfNewer(ver)) {
                        DiagLog.info(siteId, "APP_VERSION 已更新为 $ver（原 2.1.7）")
                    } else {
                        DiagLog.info(siteId, "/setting 版本=$ver（无需更新）")
                    }
                } else {
                    DiagLog.info(siteId, "/setting 未取到 jm3_version（body 前120字：${text.take(120)}）")
                }
                DiagLog.info(siteId, "API 域名可用：${DiagLog.maskUrl(d)}")
                return d
            } catch (e: Exception) {
                DiagLog.error(siteId, "API 域名不可用 ${DiagLog.maskUrl(d)}：${e.message}")
            }
        }
        return null
    }

    /**
     * 请求 path?id=id，解密并返回明文 JSON 文本（返回 null 表示失败）。
     * ts 与解密 key 必须用同一次请求的时间戳。
     */
    private fun fetchDecoded(api: String, path: String, id: String): String? {
        val ts = System.currentTimeMillis() / 1000
        val url = "$api$path?id=$id"
        return try {
            val headers = appHeaders() + JmCrypto.authHeaders(ts)
            val text = Http.getText(url, referer = "$api/", headers = headers)
            val code = JmJson.int(text, "code")
            if (code != null && code != 200) {
                DiagLog.error(siteId, "$path 返回 code=$code")
                return null
            }
            val data = JmJson.str(text, "data")
            if (data.isBlank()) {
                DiagLog.error(siteId, "$path 响应无 data 字段（code=$code，body 前200字：${text.take(200)}）")
                return null
            }
            JmCrypto.decodeRespData(data, ts)
        } catch (e: Exception) {
            DiagLog.error(siteId, "$path 请求/解密失败：${e.message}")
            null
        }
    }

    /** 获取 scramble_id；失败回退常量 220980 */
    private fun fetchScrambleId(api: String, photoId: String): Long {
        return try {
            val ts = System.currentTimeMillis() / 1000
            val url = "$api/chapter_view_template?id=$photoId&mode=vertical&page=0&app_img_shunt=1&express=off&v=$ts"
            val headers = appHeaders() + JmCrypto.authHeaders(ts, JmCrypto.APP_TOKEN_SECRET_2)
            val text = Http.getText(url, referer = "$api/", headers = headers)
            Regex("""var scramble_id = (\d+);""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: JmCrypto.SCRAMBLE_220980.toLong()
        } catch (e: Exception) {
            DiagLog.error(siteId, "scramble_id 获取失败，回退 220980：${e.message}")
            JmCrypto.SCRAMBLE_220980.toLong()
        }
    }

    private fun appHeaders(): Map<String, String> = mapOf(
        "Accept-Encoding" to "gzip, deflate",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"
    )

    private fun imageHeaders(api: String): Map<String, String> = mapOf(
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        "X-Requested-With" to "com.JMComic3.app",
        "Referer" to "$api/",
        "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7"
    )
}

/**
 * 极简 JSON 字段提取器（零依赖）。
 * 支持嵌套对象 / 数组边界扫描、字符串转义还原。
 * 仅满足本下载器的取值需求，不做完整 JSON 解析。
 */
private object JmJson {

    /** 取字符串字段值（自动跳过嵌套层级误匹配：从第一个 key 开始找其后首个冒号后的引号串） */
    fun str(json: String, key: String): String {
        val idx = findKey(json, key) ?: return ""
        var i = idx
        while (i < json.length && json[i] != ':') i++
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') {
            // 非字符串（数字等），返回原始 token
            val start = i
            while (i < json.length && json[i] != ',' && json[i] != '}' && json[i] != ']') i++
            return json.substring(start, i).trim().trim('"')
        }
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    when (val n = json[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        else -> sb.append(n)
                    }
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** 取数字字段值（int） */
    fun int(json: String, key: String): Int? {
        val idx = findKey(json, key) ?: return null
        var i = idx
        while (i < json.length && json[i] != ':') i++
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '-' || json[i] == '+')) i++
        if (i == start) return null
        return json.substring(start, i).toIntOrNull()
    }

    /** 取字符串数组字段（如 "images":["00047.webp",...]） */
    fun stringArray(json: String, key: String): List<String> {
        val raw = arrayRaw(json, key) ?: return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < raw.length && raw[i] != '"') {
                    if (raw[i] == '\\' && i + 1 < raw.length) {
                        sb.append(raw[i + 1]); i += 2
                    } else {
                        sb.append(raw[i]); i++
                    }
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }

    /** 取对象数组字段（如 "series":[{"id":..,"name":..}]），返回每个对象的原始子串 */
    fun arrayObjects(json: String, key: String): List<String> {
        val raw = arrayRaw(json, key) ?: return emptyList()
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in raw.indices) {
            val c = raw[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { out.add(raw.substring(start, i + 1)); start = -1 } }
            }
        }
        return out
    }

    /** 返回 key 对应的数组原始文本（含方括号），供进一步解析 */
    private fun arrayRaw(json: String, key: String): String? {
        val idx = findKey(json, key) ?: return null
        var i = idx
        while (i < json.length && json[i] != ':') i++
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '[') return null
        var depth = 0
        var inStr = false
        var esc = false
        val start = i
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return json.substring(start, i + 1) }
                }
            }
            i++
        }
        return null
    }

    /** 找到 "key" 后紧跟冒号的位置（返回冒号的下一位） */
    private fun findKey(json: String, key: String): Int? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val p = json.indexOf(needle, from)
            if (p < 0) return null
            var i = p + needle.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') return i
            from = p + needle.length
        }
    }
}