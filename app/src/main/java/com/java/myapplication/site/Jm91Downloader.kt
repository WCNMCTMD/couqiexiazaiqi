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
 * 91 视频下载器（权威链路对齐 /sdcard/91视频批量处理/视频下载/一键下载.sh）。
 *
 * ## 真值链路（关键：不是单一直链，而是 HLS 分片）
 * 1. 候选域名探活（GET {base}/ 判 200/3xx），取第一个可用的 base
 * 2. GET {base}/video/view/{id} 拿详情页 HTML
 * 3. 从 HTML 正则抠 m3u8 地址（`https?://...\.m3u8...`，需把 `&amp;` 还原为 `&`）
 * 4. GET m3u8 清单，校验含 `EXTM3U`
 * 5. 解析清单里的分片行（跳过 `#` 行），相对路径以 m3u8 所在目录为基准补全
 * 6. 按清单顺序逐个下载分片，**依次追加**到同一文件（天然有序，无需排序）
 * 7. 输出 `.ts`（MPEG-TS 流，可直接播放；App 内无 ffmpeg，采用真值脚本的 cat 兜底方案）
 *
 * ## 输入兼容
 * 纯 id / 哈希 id / `{base}/video/view/{id}` 链接（带参数亦可）。
 *
 * ## 安全
 * 零 cookie、零密钥。域名可由用户在设置里以 `domain` 覆盖（逗号分隔多个候选）。
 */
class Jm91Downloader : Downloader {
    override val siteId = "91"

    private companion object {
        /** 无外置域名配置时的内置候选（对齐真值脚本，可自行增删） */
        val DEFAULT_DOMAINS = listOf(
            "https://91porny.com",
            "https://www.91tanhua1.sbs",
            "https://www.91tanhua26.sbs"
        )
    }

    private val idPatterns = listOf(
        Regex("""/video/view/([0-9a-zA-Z]+)""", RegexOption.IGNORE_CASE),
        Regex("""viewkey=([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE),
        Regex("""/view(?:_video)?/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
    )

    override fun download(
        taskId: Long,
        input: String,
        outputDir: String,
        options: Map<String, String>,
        callback: ProgressCallback
    ) {
        try {
            val id = resolveId(input)
            if (id == null) {
                callback.onFailed(taskId, FailReason.msg(FailReason.INPUT, "无法识别视频 id，请粘贴 /video/view/… 链接或视频 id"))
                return
            }

            // ① 选定可用域名
            val candidates = optionDomains(options) + DEFAULT_DOMAINS
            val base = pickBase(candidates)
            if (base == null) {
                callback.onFailed(
                    taskId,
                    FailReason.msg(FailReason.NETWORK, "所有候选域名都不可用。请在「设置」填入最新域名（逗号分隔），或确认系统代理已开启")
                )
                return
            }
            DiagLog.info("91", "使用域名=$base id=$id")

            // ② 详情页
            callback.onProgress(taskId, 0f, 0, 1, "解析视频地址…")
            val detailUrl = "$base/video/view/$id"
            val html = Http.getText(detailUrl, referer = "$base/")

            // ③ 抠 m3u8
            val m3u8 = extractM3u8(html)
            if (m3u8 == null) {
                callback.onFailed(
                    taskId,
                    FailReason.msg(FailReason.PARSE, "未在详情页找到 m3u8（id 可能无效，或站点结构已变更）")
                )
                return
            }
            DiagLog.info("91", "m3u8=${DiagLog.maskUrl(m3u8)}")

            // ④ 拉清单
            val playlist = Http.getText(m3u8, referer = "$base/")
            if (!playlist.contains("EXTM3U")) {
                callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, "m3u8 清单无效（可能已过期，请重试）"))
                return
            }
            val segs = HlsDownloader.parseSegments(playlist)
            if (segs.isEmpty()) {
                callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, "m3u8 清单里没有分片"))
                return
            }

            // ⑤ 交给公共 HLS 工具下载并拼接
            val dir = File(outputDir)
            if (!dir.exists()) dir.mkdirs()
            val title = InputParser.sanitizeFileName(extractTitle(html) ?: "91_$id", 80)
            val out = File(dir, "$title.ts")

            val result = HlsDownloader.download(
                m3u8Url = m3u8,
                outFile = out,
                referer = "$base/"
            ) { done, total, name ->
                callback.onProgress(taskId, done.toFloat() / total, done, total, name)
            }
            DiagLog.info("91", "完成 分片数=${result.segments} 大小=${out.length()}B")
            callback.onProgress(taskId, 1f, result.segments, result.segments, "")
            callback.onDone(taskId, out.absolutePath)
        } catch (e: Http.NetException) {
            callback.onFailed(taskId, FailReason.msg(e.kind, "91 站点连接失败（域名可能变更或需代理：${e.message}）"))
        } catch (e: Exception) {
            callback.onFailed(taskId, FailReason.msg(FailReason.PARSE, e.message ?: "下载失败"))
        }
    }

    /** 统一清洗：纯 id / 哈希 id / 整条链接 → 干净 id（对齐真值 normalize_id） */
    private fun resolveId(input: String): String? {
        val t = input.trim()
        // 优先匹配 /video/view/{id}
        Regex("""/video/view/([0-9a-zA-Z]+)""", RegexOption.IGNORE_CASE).find(t)?.let {
            return it.groupValues[1]
        }
        // 其它链接正则
        InputParser.extractFirst(t, idPatterns)?.let { if (it.isNotBlank()) return it }
        // 去 URL 参数后只留字母数字
        val onlyAlnum = t.substringBefore('?').substringBefore('#').replace(Regex("""[^0-9a-zA-Z]"""), "")
        if (onlyAlnum.length >= 4) return onlyAlnum
        return null
    }

    /** 从 options["domain"] 读取用户自定义候选域名（逗号/分号/换行分隔） */
    private fun optionDomains(options: Map<String, String>): List<String> {
        val raw = options["domain"]?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(',', ';', '\n', ' ')
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http") }
    }

    /** 依次探活候选域名，返回第一个可用的 base（不带尾斜杠） */
    private fun pickBase(candidates: List<String>): String? {
        for (raw in candidates) {
            val d = raw.trim().trimEnd('/')
            if (d.isEmpty()) continue
            try {
                // 探测根路径是否可达（Http.getText 内部对 >=400 会抛 HTTP 异常，即可达但非 2xx）
                Http.getText("$d/", headers = mapOf("Accept" to "text/html"))
                DiagLog.req("91", "域名可用：$d")
                return d
            } catch (e: Http.NetException) {
                // 3xx 跟随、200 正常都会走到这里；只有真正连不上/4xx+ 才视为不可用
                DiagLog.error("91", "域名不可用：$d | kind=${e.kind} ${e.message}")
            } catch (e: Exception) {
                DiagLog.error("91", "域名探测异常：$d | ${e.message}")
            }
        }
        return null
    }

    /** 从详情页 HTML 抠 m3u8 地址（对齐真值：正则 + HTML 实体还原） */
    private fun extractM3u8(html: String): String? {
        val m = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        return m.value.replace("&amp;", "&").replace("\\/", "/")
    }

    /** 提取标题：优先 <title> 去站点后缀，兜底 og:title */
    private fun extractTitle(html: String): String? {
        Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            var t = it.groupValues[1].trim()
            // 去掉 " - xxx91xxx" / " - 91Porn" 之类的站点后缀
            t = t.replace(Regex("""\s*[-|]\s*[0-9A-Za-z.]*91[A-Za-z.]*\s*$""", RegexOption.IGNORE_CASE), "").trim()
            if (t.isNotBlank()) return t
        }
        Regex("""property=["']og:title["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.let {
            val t = it.groupValues[1].trim()
            if (t.isNotBlank()) return t
        }
        return null
    }
}