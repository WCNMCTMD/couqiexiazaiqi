package com.java.myapplication.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.java.myapplication.engine.DownloadTask
import com.java.myapplication.engine.TaskStatus

/**
 * 任务页：列出所有下载任务，显示进度 / 状态 / 速度，可取消 / 重试 / 删除。
 * 任务列表持久化，退出再进仍在。
 */
@Composable
fun TasksTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: DownloadViewModel = viewModel()
    val tasks by vm.tasks.collectAsState()

    LaunchedEffect(Unit) { vm.load(context) }

    if (tasks.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("暂无任务", style = MaterialTheme.typography.titleMedium)
            Text(
                "去「下载」页添加一个任务吧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("共 ${tasks.size} 条（已保存）", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clearAllTasks() }) { Text("清空全部") }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
        ) {
            items(tasks) { task ->
                TaskRow(
                    task = task,
                    onCancel = { vm.cancel(task.id) },
                    onRetry = { vm.retry(context, task.id) },
                    onDelete = { vm.removeTask(task.id) }
                )
            }
        }
    }
}

@Composable
private fun TaskRow(task: DownloadTask, onCancel: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 标题行：站点 + 输入摘要 + 状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${task.site.emoji} ${task.site.name}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(statusLabel(task.status), style = MaterialTheme.typography.bodySmall)
            }

            // 输入摘要
            Text(
                task.input,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            // 进度条
            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.DONE) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (task.totalCount > 0) "${task.doneCount}/${task.totalCount}  ${task.speedText}"
                    else task.speedText,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 错误信息（三层：① 人话主文案 ② 可折叠详情 ③ 复制日志）
            if (task.errorMsg.isNotBlank()) {
                ErrorBlock(task.errorMsg)
            }

            // 操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.QUEUED) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                if (task.status == TaskStatus.FAILED || task.status == TaskStatus.PAUSED || task.status == TaskStatus.CANCELLED) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.QUEUED -> "排队中"
    TaskStatus.RUNNING -> "下载中"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.DONE -> "完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELLED -> "已取消"
}

/**
 * 失败信息三层展示：
 * ① 主文案：把引擎带出的 [kind] 前缀翻译成一句人话（避免一坨长串吓人）；
 * ② 详情：默认折叠，点「查看详情」展开原始完整错误；
 * ③ 复制日志：一键把原始错误复制到剪贴板，方便反馈。
 *
 * 兼容：老数据若无 [kind] 前缀，主文案直接显示原文、且不显示详情入口。
 */
@Composable
private fun ErrorBlock(raw: String) {
    val context = LocalContext.current
    val parsed = remember(raw) { parseError(raw) }
    var expanded by remember(raw) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // ① 人话主文案
        Text(
            parsed.friendly,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            // ② 详情折叠入口（仅有 kind 前缀、即存在额外细节时提供）
            if (parsed.hasDetail) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(if (expanded) "收起详情" else "查看详情", style = MaterialTheme.typography.bodySmall)
                }
            }
            // ③ 复制日志
            TextButton(
                onClick = {
                    runCatching {
                        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("download-error", raw))
                        android.widget.Toast.makeText(context, "错误日志已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("复制日志", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (expanded && parsed.hasDetail) {
            Text(
                parsed.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 解析结果 */
private data class ParsedError(val friendly: String, val detail: String, val hasDetail: Boolean)

/**
 * 把 "[kind] detail" 解析为 (人话主文案, 原始详情)。
 * kind 命中内置映射时翻译；否则把 kind 本身当作主文案。
 */
private fun parseError(raw: String): ParsedError {
    val t = raw.trim()
    if (!t.startsWith("[")) return ParsedError(t, "", false)
    val end = t.indexOf(']')
    if (end <= 1) return ParsedError(t, "", false)
    val kind = t.substring(1, end).trim()
    val detail = t.substring(end + 1).trim()
    val friendly = friendlyFor(kind)
    return if (detail.isBlank()) {
        ParsedError(friendly, "", false)
    } else {
        ParsedError(friendly, detail, true)
    }
}

/** kind → 一句人话（对齐 engine/InputParser.kt 的 FailReason 常量值） */
private fun friendlyFor(kind: String): String = when {
    kind.startsWith("输入") -> "🔗 链接/ID 没认出来，请检查后重试"
    kind.startsWith("网络") -> "📡 网络不稳定，请检查代理/网络后重试"
    kind.startsWith("服务器") -> "🚧 服务器返回异常，可能被拦截或需登录"
    kind.startsWith("页面") -> "🧩 页面结构可能已更新，稍后可重试"
    kind.startsWith("需要登录") -> "🔑 该内容需要登录 Cookie（设置 → 导入 Cookie）"
    kind.startsWith("内容为空") -> "📭 内容为空，可能资源已删除"
    kind.startsWith("文件写入") -> "💾 文件写入失败，请检查存储权限/空间"
    else -> "❌ $kind"
}