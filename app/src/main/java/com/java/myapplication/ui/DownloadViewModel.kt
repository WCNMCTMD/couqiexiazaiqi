package com.java.myapplication.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.java.myapplication.data.AppSettings
import com.java.myapplication.data.Site
import com.java.myapplication.data.TaskStore
import com.java.myapplication.util.AppPaths
import com.java.myapplication.util.PermissionHelper
import com.java.myapplication.engine.DownloadBus
import com.java.myapplication.engine.DownloadManager
import com.java.myapplication.engine.DownloadService
import com.java.myapplication.engine.DownloadTask
import com.java.myapplication.engine.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 下载主 ViewModel：**只负责 UI 状态**（任务列表 / 草稿 / 历史 / 站点选择）。
 *
 * 架构（重构后）：
 *  - 实际执行已迁移到 [DownloadService]（前台服务），保证息屏/切走后台仍可下载；
 *  - 本 VM 通过 [DownloadBus] 订阅执行进度来更新内存任务列表；
 *  - 任务列表落盘到 [TaskStore]，因此即使 Activity 销毁、进程被回收后重进，也能看到最新状态。
 */
class DownloadViewModel : ViewModel() {

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    /** 待下载输入（下载页里的临时列表，逐条可删） */
    private val _pendingInputs = MutableStateFlow<List<String>>(emptyList())
    val pendingInputs: StateFlow<List<String>> = _pendingInputs

    private val _currentSiteId = MutableStateFlow("cool18")
    val currentSiteId: StateFlow<String> = _currentSiteId

    /** 输入框草稿（持久化：切换 Tab / 退出后仍保留，用户主动删才清空） */
    private val _inputDraft = MutableStateFlow("")
    val inputDraft: StateFlow<String> = _inputDraft

    /** 历史输入（成功提交过的，用户主动删才消失） */
    private val _historyInputs = MutableStateFlow<List<String>>(emptyList())
    val historyInputs: StateFlow<List<String>> = _historyInputs

    /** 是否已从磁盘加载过（避免重复加载） */
    private var loaded = false

    /** 应用级 Context（用于任务持久化），在 [load] 时记录 */
    private var appCtx: Context? = null

    /**
     * 从磁盘恢复：历史任务 / 输入草稿 / 历史输入。App 启动时调用一次。
     * 同时订阅 [DownloadBus]，接收 Service 回传的进度并更新任务列表。
     */
    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        if (loaded) return
        loaded = true
        _tasks.value = TaskStore.loadTasks(ctx).also { list ->
            // 用已落盘的最大 id 校准 idGen 水位，避免重启后新任务 id 撞上历史任务 id
            DownloadManager.ensureIdAbove(list.maxOfOrNull { it.id } ?: 0L)
        }
        _inputDraft.value = TaskStore.loadDraft(ctx)
        _historyInputs.value = TaskStore.loadHistoryInputs(ctx)
        observeServiceEvents()
    }

    /** 订阅服务事件：把 Service 的执行结果同步进内存任务列表 + 落盘 */
    private fun observeServiceEvents() {
        viewModelScope.launch {
            DownloadBus.events.collect { e ->
                when (e) {
                    is DownloadBus.Event.Progress -> updateTask(e.taskId, allowFrom = setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
                        it.copy(
                            status = TaskStatus.RUNNING,
                            progress = e.progress,
                            doneCount = e.done,
                            totalCount = e.total,
                            speedText = e.speed
                        )
                    }

                    is DownloadBus.Event.Done -> updateTask(e.taskId, allowFrom = setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
                        it.copy(status = TaskStatus.DONE, progress = 1f, outputPath = e.outputPath)
                    }

                    is DownloadBus.Event.Failed -> updateTask(e.taskId, allowFrom = setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
                        it.copy(status = TaskStatus.FAILED, errorMsg = e.error)
                    }

                    is DownloadBus.Event.ServiceState -> {
                        // 服务整体状态：若服务已停止但仍有 RUNNING 任务，标记为中断
                        if (!e.running) {
                            _tasks.update { list ->
                                list.map {
                                    if (it.status == TaskStatus.RUNNING)
                                        it.copy(status = TaskStatus.PAUSED, speedText = "")
                                    else it
                                }
                            }
                            persist()
                        }
                    }
                }
            }
        }
    }

    /** 把当前任务列表落盘（退出不丢） */
    private fun persist(ctx: Context? = appCtx) {
        ctx?.let { TaskStore.saveTasks(it, _tasks.value) }
    }

    fun selectSite(id: String) { _currentSiteId.value = id }

    /** 更新输入框草稿并立即落盘（防止退出丢失） */
    fun updateDraft(ctx: Context, text: String) {
        _inputDraft.value = text
        TaskStore.saveDraft(ctx, text)
    }

    /** 主动清空草稿（用户点「清空」时调用） */
    fun clearDraft(ctx: Context) {
        _inputDraft.value = ""
        TaskStore.saveDraft(ctx, "")
    }

    fun removeHistoryInput(ctx: Context, input: String) {
        TaskStore.removeHistoryInput(ctx, input)
        _historyInputs.value = TaskStore.loadHistoryInputs(ctx)
    }

    fun addPending(input: String) {
        val line = input.trim()
        if (line.isNotEmpty()) {
            _pendingInputs.update { list -> list + input.trim().split('\n').filter { it.isNotBlank() } }
        }
    }

    fun removePendingAt(index: Int) {
        _pendingInputs.update { list -> list.filterIndexed { i, _ -> i != index } }
    }

    fun clearPending() { _pendingInputs.value = emptyList() }

    /**
     * 把待下载列表全部转成任务，并**交给前台服务执行**。
     * @param ctx 用于读取设置（目录/并发/命名等）与启动 Service
     */
    fun startAll(ctx: Context) {
        val siteId = _currentSiteId.value
        val site = Site.byId(siteId) ?: return
        val outDir = resolveOutDir(ctx, siteId)
        val inputs = _pendingInputs.value.toList()
        if (inputs.isEmpty()) return

        _pendingInputs.value = emptyList()
        // 提交即记为历史输入（用户主动删才消失），并清空草稿
        inputs.forEach { TaskStore.addHistoryInput(ctx, it) }
        _historyInputs.value = TaskStore.loadHistoryInputs(ctx)
        clearDraft(ctx)

        val options = buildOptions(ctx, siteId)

        inputs.forEach { raw ->
            val task = DownloadManager.newTask(site, raw).copy(status = TaskStatus.QUEUED)
            _tasks.update { it + task }
            persist(ctx)
            // 交给前台服务执行（Activity 销毁也不中断）
            DownloadService.startTask(
                ctx = ctx.applicationContext,
                taskId = task.id,
                siteId = siteId,
                input = task.input,
                outDir = outDir,
                options = options
            )
        }
    }

    /**
     * 解析某站点的输出目录（**真实文件系统路径**，供 java.io.File 使用）。
     *
     * 病根 A 修复要点：
     *  - 旧逻辑会直接把设置里存的 SAF content:// URI 原样返回 → 引擎 new File() 崩（ENOENT）；
     *  - 新逻辑：若已获得「所有文件访问权」，统一用固定真实路径 <root>/<siteId>；
     *    否则退回应用私有目录（同样真实路径，无需权限，可正常落盘，只是用户不易找到）。
     *  - 历史遗留的 content:// 值一律丢弃（isRealPath 判断）。
     */
    private fun resolveOutDir(ctx: Context, siteId: String): String {
        return if (PermissionHelper.hasAllFilesAccess(ctx)) {
            // 固定：/sdcard/Download/multi-downloader/<siteId>（并预先建目录）
            AppPaths.siteDir(ctx, siteId)
        } else {
            // 兜底：应用外部私有目录（真实路径，无需权限）
            val base = ctx.getExternalFilesDir(null)?.absolutePath ?: ctx.filesDir.absolutePath
            "$base/downloads/$siteId"
        }
    }

    private fun buildOptions(ctx: Context, siteId: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        m["concurrency"] = AppSettings.concurrency(ctx).toString()
        m["nameMode"] = AppSettings.nameMode(ctx)
        m["autoPdf"] = AppSettings.autoPdf(ctx).toString()
        m["webpToJpg"] = AppSettings.webpToJpg(ctx).toString()
        if (siteId == "91") {
            m["domain"] = AppSettings.domain91(ctx)
        }
        return m
    }

    /**
     * 更新一条任务。
     * @param allowFrom 若非空，则仅当该任务**当前状态**在集合内时才更新——
     *   用于拦截「已结束的历史任务」被同 id 的迟到事件误激活（如重启后 id 撞号）。
     */
    private fun updateTask(
        id: Long,
        allowFrom: Set<TaskStatus>? = null,
        transform: (DownloadTask) -> DownloadTask
    ) {
        _tasks.update { list ->
            list.map {
                if (it.id == id && (allowFrom == null || it.status in allowFrom)) transform(it) else it
            }
        }
        persist()
    }

    fun pause(id: Long) { updateTask(id) { it.copy(status = TaskStatus.PAUSED) } }
    fun cancel(id: Long) { updateTask(id) { it.copy(status = TaskStatus.CANCELLED) } }

    /** 删除单条历史任务记录 */
    fun removeTask(id: Long) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    /** 清空全部历史任务 */
    fun clearAllTasks() {
        _tasks.value = emptyList()
        persist()
    }

    /** 重试一条失败/中断的任务：重新交给前台服务执行 */
    fun retry(ctx: Context, id: Long) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        val outDir = resolveOutDir(ctx, task.site.id)
        updateTask(id) {
            it.copy(status = TaskStatus.QUEUED, progress = 0f, errorMsg = "", doneCount = 0, speedText = "")
        }
        DownloadService.startTask(
            ctx = ctx.applicationContext,
            taskId = task.id,
            siteId = task.site.id,
            input = task.input,
            outDir = outDir,
            options = buildOptions(ctx, task.site.id)
        )
    }
}