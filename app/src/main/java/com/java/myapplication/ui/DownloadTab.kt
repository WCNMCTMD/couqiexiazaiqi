package com.java.myapplication.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.java.myapplication.data.Site
import com.java.myapplication.util.PermissionHelper

/**
 * 下载页：站点卡片网格 + 输入框 + 开始下载。
 */
@Composable
fun DownloadTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: DownloadViewModel = viewModel()
    val currentSiteId by vm.currentSiteId.collectAsState()
    val inputText by vm.inputDraft.collectAsState()

    val currentSite = Site.byId(currentSiteId) ?: Site.ALL.first()

    // 通知权限（Android 13+）：在「首次开始下载」时请求。
    // 策略：授权、拒绝、系统未弹（<13）都不阻塞下载，只是授权后能显示进度通知。
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 无论用户允许还是拒绝，都继续执行被挂起的动作（下载不因权限被卡）
        val act = pendingAction
        pendingAction = null
        act?.invoke()
    }

    fun ensureNotifPermissionThen(action: () -> Unit) {
        if (PermissionHelper.hasNotificationPermission(context)) {
            action()
        } else {
            // 挂起动作 → 弹窗 → 回调里执行。期间若系统不弹（<13），回调立即触发
            pendingAction = action
            notifPermLauncher.launch(PermissionHelper.notificationPermissions())
        }
    }

    // 首次进入时从磁盘恢复草稿/历史任务（切换 Tab 回来时不会重复覆盖）
    LaunchedEffect(Unit) { vm.load(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("选择站点", style = MaterialTheme.typography.titleMedium)

        SiteGrid(
            selectedId = currentSiteId,
            onSelect = { vm.selectSite(it) },
            onOpenHome = { openHome(context, it) }
        )

        // 当前站点 + 输入框
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${currentSite.emoji} ${currentSite.name}", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    currentSite.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentSite.customDomain) {
                    Text(
                        "⚠️ 该站点域名可能变更，可在「设置」里自定义域名",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (currentSite.needsCookie) {
                    Text(
                        "🔑 可选：如需登录态（如受限内容）可在「设置 → 导入 Cookie」导入，不导入也能下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 时效站点提示条（如 PornHub：直链带过期时间，拖久了就下载失败）
                if (currentSite.timeSensitive) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "⏱ 此站链接有时效，请尽快开始下载",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                "链接复制后放久了会「过期」，下载就会失败。若提示链接过期，请回到本站重新复制最新链接再试一次。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { vm.updateDraft(context, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("链接 / 关键词 / ID") },
                    minLines = 3,
                    maxLines = 6
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                ensureNotifPermissionThen {
                                    vm.addPending(inputText)
                                    vm.startAll(context)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = inputText.isNotBlank()
                    ) {
                        Text("开始下载")
                    }
                    if (inputText.isNotBlank()) {
                        TextButton(onClick = { vm.clearDraft(context) }) {
                            Text("清空")
                        }
                    }
                }

                // 历史输入：退出也不丢，除非用户主动删除
                val history by vm.historyInputs.collectAsState()
                if (history.isNotEmpty()) {
                    Text(
                        "历史输入（点击填入）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    history.take(5).forEach { h ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                h,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { vm.updateDraft(context, h) },
                                maxLines = 1
                            )
                            Text(
                                "✕",
                                modifier = Modifier
                                    .clickable { vm.removeHistoryInput(context, h) }
                                    .padding(6.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteGrid(selectedId: String, onSelect: (String) -> Unit, onOpenHome: (Site) -> Unit) {
    // 6 个站点，3 列 = 2 行，给一个固定高度即可
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false
    ) {
        items(Site.ALL) { site ->
            val selected = site.id == selectedId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(site.id) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (site.logoRes != null) {
                        Image(
                            painter = painterResource(site.logoRes),
                            contentDescription = site.name,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Text(site.emoji, fontSize = 26.sp)
                    }
                    Text(site.name, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Text(
                        "↗",
                        modifier = Modifier
                            .clickable { onOpenHome(site) }
                            .padding(2.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun openHome(context: Context, site: Site) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.homeUrl)))
    } catch (_: Exception) {
    }
}