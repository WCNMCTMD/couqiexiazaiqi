package com.java.myapplication.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 统一的下载保存路径管理（真实文件系统路径）。
 *
 * 设计背景（病根 A 修复）：
 *  - 旧方案用 SAF（OpenDocumentTree）选目录，拿到的是 content:// URI；
 *  - 而下载引擎（Http/HlsDownloader/各站点）全部基于 java.io.File，
 *    java.io.File 无法识别 content:// → 写盘报 ENOENT（用户实测 91 即死于此）；
 *  - 本 App 走 MANAGE_EXTERNAL_STORAGE（全文件访问权）路线：拿到真实路径，
 *    固定根目录 = /sdcard/Download/multi-downloader/，引擎继续用 java.io.File 即可。
 *
 * 目录结构：
 *  /sdcard/Download/multi-downloader/
 *    ├── 91/ jm/ hentaipaw/ pornhub/ twitter/ cool18/   （按站点分子目录）
 *    └── logs/                                            （诊断日志）
 */
object AppPaths {

    /** 应用根目录名（公共 Download 下） */
    const val ROOT_DIR_NAME = "multi-downloader"

    /**
     * 根目录：优先用公共 Download 目录，异常时退回外部存储根。
     * 返回绝对路径字符串（真实路径，非 content://）。
     */
    fun root(context: Context): String {
        val base = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Throwable) {
            null
        }
        val safeBase = base
            ?: File(Environment.getExternalStorageDirectory(), "Download")
        return File(safeBase, ROOT_DIR_NAME).absolutePath
    }

    /** 某站点专属子目录：<root>/<siteId> */
    fun siteDir(context: Context, siteId: String): String =
        File(root(context), siteId).absolutePath

    /** 诊断日志目录：<root>/logs */
    fun logsDir(context: Context): String =
        File(root(context), "logs").absolutePath

    /**
     * 确保目录存在。返回其绝对路径。
     * 注意：仅在已获得 MANAGE_EXTERNAL_STORAGE 时才能成功建在公共目录。
     */
    fun ensureDir(path: String): File {
        val dir = File(path)
        if (!dir.exists()) {
            try {
                dir.mkdirs()
            } catch (_: Throwable) {
            }
        }
        return dir
    }

    /** 建好并返回站点子目录的 File */
    fun siteFile(context: Context, siteId: String): File =
        ensureDir(siteDir(context, siteId))

    /** 建好并返回日志目录的 File */
    fun logsFile(context: Context): File =
        ensureDir(logsDir(context))

    /**
     * 判断字符串是否为「真实文件系统路径」（可被 java.io.File 使用）。
     * SAF 的 content:// URI 会返回 false —— 用于兜底丢弃历史遗留值。
     */
    fun isRealPath(path: String?): Boolean =
        !path.isNullOrBlank() && !path.startsWith("content://")
}
