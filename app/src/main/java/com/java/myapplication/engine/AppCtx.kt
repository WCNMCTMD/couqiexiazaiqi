package com.java.myapplication.engine

import android.annotation.SuppressLint
import android.content.Context

/**
 * 全局 Application Context 持有者（极简）。
 *
 * 用途：下载引擎（如 HentaiPawDownloader 的 WebView 取页）需要 Context，
 * 但 [Downloader.download] 接口不传 Context；在 MainActivity 启动时注入一次即可。
 * 只持有 applicationContext（不泄漏 Activity）。
 */
@SuppressLint("StaticFieldLeak")
object AppCtx {
    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun get(): Context = app ?: throw IllegalStateException("AppCtx 未初始化")
}