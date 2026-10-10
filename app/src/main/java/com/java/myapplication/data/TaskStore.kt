package com.java.myapplication.data

import android.content.Context
import com.java.myapplication.engine.DownloadTask
import com.java.myapplication.engine.TaskStatus

/**
 * 任务 / 输入的轻量持久化（SharedPreferences）。
 *
 * 目的（用户需求）：
 *  - 下载任务退出后不丢（历史记录持久化）；
 *  - 输入框内容退出后保留，除非用户主动删除。
 *
 * 安全：只存用户自己输入的链接/关键词与任务元数据，绝不存 cookie / 密钥。
 * 编码：极简「竖线 + 换行」分隔，避免引入 JSON 依赖；字段内自身用 \n 会被转义。
 */
object TaskStore {
    private const val PREFS = "task_store"
    private const val KEY_TASKS = "tasks"
    private const val KEY_DRAFT = "input_draft"          // 当前站点输入框草稿
    private const val KEY_HISTORY = "history_inputs"     // 历史输入（用户删过才移除）

    /** 字段分隔符（极少出现在链接/标题里，仍做转义） */
    private const val FS = "\u0001"
    private const val RS = "\u0002"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 任务列表 ----------------

    fun saveTasks(ctx: Context, tasks: List<DownloadTask>) {
        val sb = StringBuilder()
        tasks.forEachIndexed { i, t ->
            if (i > 0) sb.append(RS)
            sb.append(esc(t.id.toString())).append(FS)
                .append(esc(t.site.id)).append(FS)
                .append(esc(t.input)).append(FS)
                .append(esc(t.title)).append(FS)
                .append(esc(t.status.name)).append(FS)
                .append(esc(t.progress.toString())).append(FS)
                .append(esc(t.doneCount.toString())).append(FS)
                .append(esc(t.totalCount.toString())).append(FS)
                .append(esc(t.speedText)).append(FS)
                .append(esc(t.errorMsg)).append(FS)
                .append(esc(t.outputPath))
        }
        sp(ctx).edit().putString(KEY_TASKS, sb.toString()).apply()
    }

    fun loadTasks(ctx: Context): List<DownloadTask> {
        val raw = sp(ctx).getString(KEY_TASKS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(RS).mapNotNull { row ->
            val p = row.split(FS)
            if (p.size < 11) return@mapNotNull null
            val site = Site.byId(unesc(p[1])) ?: return@mapNotNull null
            val status = runCatching { TaskStatus.valueOf(unesc(p[4])) }.getOrDefault(TaskStatus.QUEUED)
            // 重启后，未完成的任务标记为「已中断」，交由用户手动重试，避免自动重下
            val restored = when (status) {
                TaskStatus.RUNNING, TaskStatus.QUEUED -> TaskStatus.PAUSED
                else -> status
            }
            DownloadTask(
                id = unesc(p[0]).toLongOrNull() ?: return@mapNotNull null,
                site = site,
                input = unesc(p[2]),
                title = unesc(p[3]),
                status = restored,
                progress = unesc(p[5]).toFloatOrNull() ?: 0f,
                doneCount = unesc(p[6]).toIntOrNull() ?: 0,
                totalCount = unesc(p[7]).toIntOrNull() ?: 0,
                speedText = "",
                errorMsg = unesc(p[9]),
                outputPath = unesc(p[10])
            )
        }
    }

    /** 清空历史任务 */
    fun clearTasks(ctx: Context) = sp(ctx).edit().remove(KEY_TASKS).apply()

    /**
     * 按 id 更新单条任务并落盘（供前台服务使用：Service 与 ViewModel 是不同生命周期，
     * 服务在任务开始/结束时直接更新磁盘，保证 Activity 销毁后状态不丢）。
     * 找不到该 id 时不做任何事。
     */
    fun upsertTaskStatus(
        ctx: Context,
        taskId: Long,
        status: TaskStatus,
        progress: Float? = null,
        errorMsg: String? = null,
        outputPath: String? = null,
        speedText: String? = null
    ) {
        val list = loadTasks(ctx)
        var changed = false
        val updated = list.map { t ->
            if (t.id == taskId) {
                changed = true
                t.copy(
                    status = status,
                    progress = progress ?: t.progress,
                    errorMsg = errorMsg ?: t.errorMsg,
                    outputPath = outputPath ?: t.outputPath,
                    speedText = speedText ?: t.speedText
                )
            } else t
        }
        if (changed) saveTasks(ctx, updated)
    }

    // ---------------- 输入框草稿 / 历史输入 ----------------

    fun saveDraft(ctx: Context, text: String) =
        sp(ctx).edit().putString(KEY_DRAFT, text).apply()

    fun loadDraft(ctx: Context): String =
        sp(ctx).getString(KEY_DRAFT, "") ?: ""

    /** 历史输入（成功提交过的），用户主动删除才消失 */
    fun loadHistoryInputs(ctx: Context): List<String> {
        val raw = sp(ctx).getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun addHistoryInput(ctx: Context, input: String) {
        val line = input.trim()
        if (line.isEmpty()) return
        val cur = loadHistoryInputs(ctx).toMutableList()
        cur.remove(line)
        cur.add(0, line)
        val trimmed = cur.take(100) // 最多保留 100 条
        sp(ctx).edit().putString(KEY_HISTORY, trimmed.joinToString("\n")).apply()
    }

    fun removeHistoryInput(ctx: Context, input: String) {
        val cur = loadHistoryInputs(ctx).filter { it != input }
        sp(ctx).edit().putString(KEY_HISTORY, cur.joinToString("\n")).apply()
    }

    fun clearHistoryInputs(ctx: Context) = sp(ctx).edit().remove(KEY_HISTORY).apply()

    // ---------------- 转义 ----------------
    private fun esc(s: String): String =
        s.replace("\u0001", " ").replace("\u0002", " ").replace("\n", " ").replace("\r", " ")

    private fun unesc(s: String): String = s
}
