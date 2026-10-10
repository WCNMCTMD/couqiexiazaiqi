package com.java.myapplication.engine

import com.java.myapplication.data.Site
import com.java.myapplication.site.Cool18Downloader
import com.java.myapplication.site.HentaiPawDownloader
import com.java.myapplication.site.Jm91Downloader
import com.java.myapplication.site.JmDownloader
import com.java.myapplication.site.PornhubDownloader
import com.java.myapplication.site.TwitterDownloader
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载引擎注册与分发。
 * 每个站点一个 Downloader 实现，这里按 siteId 路由。
 *
 * 安全：仅注册引擎，不携带任何凭证。
 */
object DownloadManager {
    private val idGen = AtomicLong(1)

    private val downloaders: Map<String, Downloader> = mapOf(
        "cool18" to Cool18Downloader(),
        "91" to Jm91Downloader(),
        "pornhub" to PornhubDownloader(),
        "twitter" to TwitterDownloader(),
        "jm" to JmDownloader(),
        "hentaipaw" to HentaiPawDownloader()
    )

    fun isSupported(siteId: String): Boolean = downloaders.containsKey(siteId)

    fun nextId(): Long = idGen.getAndIncrement()

    /**
     * 用已落盘任务的最大 id 校准 id 水位，避免 App 重启后 idGen 归零、
     * 新任务 id 与历史任务「撞号」，从而导致历史已完成任务被误激活重下。
     *
     * 用法：在从 [TaskStore] 恢复任务列表之后调用一次。
     */
    fun ensureIdAbove(maxExistingId: Long) {
        idGen.updateAndGet { cur -> if (cur <= maxExistingId) maxExistingId + 1 else cur }
    }

    fun get(siteId: String): Downloader? = downloaders[siteId]

    /** 生成一个待执行的任务（带唯一 id） */
    fun newTask(site: Site, input: String): DownloadTask =
        DownloadTask(id = nextId(), site = site, input = input)
}