package com.java.myapplication.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * 图片序列下载 + 合成 PDF 的通用工具。
 *
 * 通用图片下载器：[downloadImageList] 按顺序下载一组图片 URL 到目标目录，
 * 支持 referer、逐张进度回传、可选 webp→jpg 说明（实际不做转码，仅保留扩展名）。
 * PDF 合成：[imagesToPdf] 把目录下按文件名排序的图片合成单个 PDF。
 */
object ImagePdf {

    /**
     * 顺序下载图片列表到 dir。
     * @param urls 图片 URL 列表
     * @param dir 目标目录
     * @param referer 下载时带的 referer（部分站点防盗链需要）
     * @param prefix 文件名前缀（如 "page"），最终为 prefix_001.jpg
     * @param onProgress (done, total) 进度回调
     * @return 下载成功写出的文件数量
     */
    fun downloadImageList(
        urls: List<String>,
        dir: File,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        prefix: String = "page",
        onProgress: ((Int, Int) -> Unit)? = null,
        // 可选的下载器：把 URL 下载到目标文件并返回字节数。
        // 默认走 java.net Http；需要 Cronet（Chromium TLS 指纹过 CF）的站点可覆盖。
        fetcher: ((url: String, dest: File) -> Long)? = null
    ): Int {
        if (!dir.exists()) dir.mkdirs()
        var success = 0
        val total = urls.size
        for ((i, url) in urls.withIndex()) {
            val ext = inferExt(url)
            val name = String.format("%s_%03d.%s", prefix, i + 1, ext)
            val dest = File(dir, name)
            try {
                val n = if (fetcher != null) {
                    fetcher.invoke(url, dest)
                } else {
                    Http.downloadToFile(url, dest, referer, headers)
                }
                // 文件头校验：防 CF 拦截页/错误页被当作图片存成"假成功"
                if (n > 0 && isImageFile(dest)) {
                    success++
                } else {
                    dest.delete() // 非图片内容一律丢弃，不计成功
                }
            } catch (_: Exception) {
                // 单张失败不中断，继续下一张
            }
            onProgress?.invoke(i + 1, total)
        }
        return success
    }

    /**
     * 校验文件头，确认真的是图片（防 CF 拦截页/错误页被当图片保存）。
     * 严格对齐 hp3.py 的 _is_image：RIFF/WEBP、JPEG、PNG、GIF。
     */
    fun isImageFile(file: File): Boolean {
        return try {
            val head = ByteArray(12)
            val read = file.inputStream().use { it.read(head) }
            if (read < 12) return false
            isImageBytes(head)
        } catch (_: Exception) {
            false
        }
    }

    /** 对内存中的头部字节做同样的图片魔数判定（至少 12 字节）。 */
    fun isImageBytes(data: ByteArray): Boolean {
        if (data.size < 12) return false
        fun u(i: Int) = data[i].toInt() and 0xFF
        // RIFF....WEBP
        if (u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
            u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50
        ) return true
        // JPEG \xff\xd8\xff
        if (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return true
        // PNG \x89PNG\r\n\x1a\n
        if (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 &&
            u(4) == 0x0D && u(5) == 0x0A && u(6) == 0x1A && u(7) == 0x0A
        ) return true
        // GIF
        if (u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46) return true
        return false
    }

    /** 从 URL 推断扩展名，无法判断时默认 jpg */
    private fun inferExt(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('.', "")
        return when (last.lowercase()) {
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            "bmp" -> "bmp"
            else -> "jpg"
        }
    }

    /**
     * 把目录下（按文件名自然排序）的图片合成单个 PDF，写到 outFile。
     * 图片自动缩放至 A4 宽度、保持纵横比，按页写入。
     * @return 成功写入的页数；目录无图片时返回 0
     */
    fun imagesToPdf(dir: File, outFile: File, onProgress: ((Int, Int) -> Unit)? = null): Int {
        val imgs = dir.listFiles { f ->
            f.isFile && f.name.lowercase().matches(Regex(""".*\.(jpg|jpeg|png|webp|bmp)$"""))
        }?.sortedBy { it.name } ?: return 0
        if (imgs.isEmpty()) return 0

        val doc = PdfDocument()
        var pages = 0
        try {
            for ((i, f) in imgs.withIndex()) {
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                val pageInfo = PdfDocument.PageInfo.Builder(
                    bmp.width, bmp.height, i + 1
                ).create()
                val page = doc.startPage(pageInfo)
                val canvas: Canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
                bmp.recycle()
                pages++
                onProgress?.invoke(i + 1, imgs.size)
            }
            doc.writeTo(outFile.outputStream())
        } finally {
            doc.close()
        }
        return pages
    }

    /**
     * 解码源图 → 按 num 条带重排（禁漫 scramble）→ 以 JPEG 重新编码写出到 dest。
     *
     * 对齐 jmcomic JmImageTool.decode_and_save：把图片在水平方向切成 num 条，
     * 从底部往上重排。num <= 1 时视为无需处理，直接按原字节拷贝（保留原格式）。
     *
     * @return true 表示已成功写出目标文件（解密成功或直接拷贝）
     */
    fun saveRemixed(src: File, dest: File, num: Int, quality: Int = 90): Boolean {
        if (num <= 1) {
            // 不处理：直接拷贝原始字节，保留原格式（webp/jpg 等）
            return try {
                src.copyTo(dest, overwrite = true)
                true
            } catch (_: Exception) {
                false
            }
        }
        val bmp = decodeSampled(src.absolutePath, maxDim = 8192) ?: return false
        return try {
            val w = bmp.width
            val h = bmp.height
            val over = h % num
            val move = h / num
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            for (i in 0 until num) {
                // 严格对齐 jmcomic JmImageTool.decode_and_save：
                //   y_src = h - move*(i+1) - over
                //   y_dst = move*i ；i==0 时高度额外 +over（补余数），否则 y_dst 再 +over
                val ySrc = h - (move * (i + 1)) - over
                var bandH = move
                var yDst = move * i
                if (i == 0) {
                    bandH += over
                } else {
                    yDst += over
                }
                val srcBmp = Bitmap.createBitmap(bmp, 0, ySrc, w, bandH)
                canvas.drawBitmap(srcBmp, 0f, yDst.toFloat(), null)
                srcBmp.recycle()
            }
            dest.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            out.recycle()
            bmp.recycle()
            true
        } catch (_: Exception) {
            try { bmp.recycle() } catch (_: Exception) {}
            false
        }
    }

    /** 把单个 Bitmap 解码受限采样，避免超大图 OOM */
    fun decodeSampled(path: String, maxDim: Int = 4096): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts2)
    }
}