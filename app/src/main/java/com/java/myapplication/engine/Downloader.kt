package com.java.myapplication.engine

import com.java.myapplication.data.Site

/** 任务状态 */
enum class TaskStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

/** 单条下载任务 */
data class DownloadTask(
    val id: Long,
    val site: Site,
    val input: String,          // 原始输入（链接 / 关键词 / id）
    var title: String = "",     // 解析出的标题/名称
    var status: TaskStatus = TaskStatus.QUEUED,
    var progress: Float = 0f,   // 0..1
    var doneCount: Int = 0,     // 已下载页/分片数
    var totalCount: Int = 0,    // 总页/分片数
    var speedText: String = "", // 实时速度
    var errorMsg: String = "",  // 失败原因
    var outputPath: String = "" // 最终落地路径/目录
)

/** 进度回调（引擎 → UI） */
interface ProgressCallback {
    fun onProgress(taskId: Long, progress: Float, done: Int, total: Int, speed: String)
    fun onDone(taskId: Long, outputPath: String)
    fun onFailed(taskId: Long, error: String)
}

/**
 * 下载引擎统一接口。每个站点一个实现。
 * 全部在后台线程（协程 IO）执行，不要在引擎里自己开主线程阻塞。
 */
interface Downloader {
    /** 该引擎处理的站点 id */
    val siteId: String

    /**
     * 执行下载。
     * @param taskId 任务 id（用于回调回传，与 UI 任务列表关联）
     * @param input 用户原始输入（链接/关键词/id）
     * @param outputDir 用户选择的目标目录（绝对路径）
     * @param options 该站点的可选参数（如域名、并发、翻页、命名等）
     */
    fun download(
        taskId: Long,
        input: String,
        outputDir: String,
        options: Map<String, String>,
        callback: ProgressCallback
    )
}
