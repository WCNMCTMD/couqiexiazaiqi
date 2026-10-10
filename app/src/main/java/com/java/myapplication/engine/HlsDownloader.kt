package com.java.myapplication.engine

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * HLS(m3u8) 下载公共工具（91 / PornHub / 其它站共用）。
 *
 * 能力：
 * - 拉取 m3u8 清单，解析出分片列表（跳过 `#` 注释/扩展行）
 * - 相对分片路径按 m3u8 所在目录补全绝对 URL
 * - 顺序下载分片并**依次追加**到同一文件（天然有序，无需排序）
 * - 支持嵌套 m3u8（master playlist → 取第一个 variant 的 m3u8 再拉）
 * - 用内置 ffmpeg（ffmpeg-kit-min）把拼接出的 .ts 转封装为可播放 .mp4
 *
 * 不处理加密（EXT-X-KEY）——本项目目标站点实测均为明文分片；
 * 若遇到加密清单会在日志中提示，便于真机排查。
 *
 * 依赖：`com.arthenica:ffmpeg-kit-min`（见 app/build.gradle.kts）。
 */
object HlsDownloader {

    /** 单次下载的结果 */
    data class Result(val file: File, val segments: Int)

    /**
     * 下载一个 m3u8 到 [outFile]。
     * @param m3u8Url 主 m3u8 地址
     * @param outFile 输出文件（会先删除旧内容）
     * @param referer 防盗链 Referer
     * @param headers 额外请求头
     * @param onProgress 进度回调：(已完成分片数, 总分片数, 显示名)
     */
    fun download(
        m3u8Url: String,
        outFile: File,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }
    ): Result {
        var playlist = Http.getText(m3u8Url, referer = referer, headers = headers)
        if (!playlist.contains("EXTM3U")) {
            throw Http.NetException(FailReason.PARSE, "m3u8 清单无效（可能已过期）")
        }

        // 若为 master playlist（含 EXT-X-STREAM-INF），取第一个子 m3u8 再拉一层
        var segBaseUrl = m3u8Url
        if (playlist.contains("EXT-X-STREAM-INF")) {
            val sub = firstVariantUrl(playlist)
            if (sub != null) {
                val subUrl = absolutize(sub, m3u8Url)
                DiagLog.info("HLS", "master playlist，切换到子清单 ${DiagLog.maskUrl(subUrl)}")
                playlist = Http.getText(subUrl, referer = referer, headers = headers)
                if (!playlist.contains("EXTM3U")) {
                    throw Http.NetException(FailReason.PARSE, "子 m3u8 清单无效")
                }
                segBaseUrl = subUrl
            }
        }

        if (playlist.contains("EXT-X-KEY")) {
            DiagLog.error("HLS", "清单含 EXT-X-KEY（加密分片），当前实现不支持解密")
        }

        val segs = parseSegments(playlist)
        if (segs.isEmpty()) throw Http.NetException(FailReason.PARSE, "m3u8 清单里没有分片")

        if (outFile.exists()) outFile.delete()
        val segBase = segBaseUrl.substringBeforeLast('/', "")
        val total = segs.size
        var done = 0
        for (seg in segs) {
            val url = if (seg.startsWith("http")) seg else "$segBase/$seg"
            val n = Http.appendToFile(url, outFile, referer = referer, headers = headers)
            done++
            onProgress(done, total, "分片 $done/$total")
            if (n <= 0) {
                throw Http.NetException(FailReason.EMPTY, "分片下载为空：${seg.takeLast(40)}")
            }
        }
        if (outFile.length() <= 0) {
            throw Http.NetException(FailReason.EMPTY, "下载失败（文件为空）")
        }
        return Result(outFile, total)
    }

    /** 解析分片行（跳过注释/空行），去掉 query 以便取文件名 */
    fun parseSegments(playlist: String): List<String> {
        val result = mutableListOf<String>()
        playlist.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            result.add(line)
        }
        return result
    }

    /** 从 master playlist 里取第一个 variant 地址（紧跟在 EXT-X-STREAM-INF 行之后的第一条非注释行） */
    fun firstVariantUrl(playlist: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                for (j in i + 1 until lines.size) {
                    val nxt = lines[j]
                    if (nxt.isEmpty()) continue
                    if (nxt.startsWith("#")) continue
                    return nxt
                }
            }
        }
        return null
    }

    /** 把（可能的相对）分片地址按 m3u8 基地址补全为绝对 URL */
    fun absolutize(ref: String, base: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        val origin = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: ""
        if (ref.startsWith("/")) return origin + ref
        val dir = base.substringBeforeLast('/', "")
        return if (dir.isEmpty()) ref else "$dir/$ref"
    }

    /**
     * 用内置 ffmpeg（ffmpeg-kit-min）把拼接好的 TS 转封装为 MP4。
     *
     * 采用 `-c copy`（流拷贝，不重新编码）：视频/音频码流原样搬运，仅改容器，
     * 因此速度快、画质无损，且不依赖任何编码器。若源流含 ffmpeg 不认识的封装，
     * 退一步不加 `-c copy`（走默认转码）再试一次。
     *
     * @return true 表示成功产出非空 mp4；false 表示 ffmpeg 不可用或失败（调用方应降级保留 .ts）
     */
    fun muxTsToMp4(tsFile: File, outMp4: File): Boolean {
        if (!tsFile.exists() || tsFile.length() <= 0) {
            DiagLog.error("HLS", "ts 源文件不存在或为空，跳过转封装")
            return false
        }
        if (outMp4.exists()) outMp4.delete()
        // 路径含空格/中文需加引号（ffmpeg-kit 按空格切参数，必须转义）
        val inPath = tsFile.absolutePath
        val outPath = outMp4.absolutePath
        return try {
            // 先试无损流拷贝
            var session = FFmpegKit.execute(
                "-hide_banner -y -i \"$inPath\" -c copy -bsf:a aac_adtstoasc \"$outPath\""
            )
            if (ReturnCode.isSuccess(session.returnCode) && outMp4.exists() && outMp4.length() > 0) {
                DiagLog.info("HLS", "ffmpeg 转封装成功(copy)：${outMp4.name} ${outMp4.length()}B")
                return true
            }
            DiagLog.error("HLS", "ffmpeg copy 失败 rc=${session.returnCode}，尝试重编码：${session.output?.takeLast(400)}")
            // 兜底：不加 -c copy，让 ffmpeg 自动重编码（应对非常规码流）
            if (outMp4.exists()) outMp4.delete()
            session = FFmpegKit.execute(
                "-hide_banner -y -i \"$inPath\" -c:v libx264 -c:a aac -movflags +faststart \"$outPath\""
            )
            val ok = ReturnCode.isSuccess(session.returnCode) && outMp4.exists() && outMp4.length() > 0
            if (ok) {
                DiagLog.info("HLS", "ffmpeg 转封装成功(重编码)：${outMp4.name} ${outMp4.length()}B")
            } else {
                DiagLog.error("HLS", "ffmpeg 重编码亦失败 rc=${session.returnCode}：${session.output?.takeLast(400)}")
            }
            ok
        } catch (e: Throwable) {
            // ffmpeg-kit 未加载/加载失败等
            DiagLog.error("HLS", "ffmpeg 调用异常：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}