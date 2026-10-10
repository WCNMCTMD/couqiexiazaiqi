package com.java.myapplication.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.java.myapplication.data.Site
import com.java.myapplication.util.PermissionHelper
import kotlinx.coroutines.launch

/**
 * 首次启动引导页（分页滑动 + 底部「开始使用 / 跳过」）。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = buildOnboardingPages()
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    // 系统返回键：非第一页时回退到上一页；已在第一页则交给系统（退出）
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            OnboardingPage(pages[page])
        }

        // 底部指示点 + 按钮
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 指示点
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(pages.size) { i ->
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (i == pagerState.currentPage)
                                    MaterialTheme.colorScheme.primary else Color.LightGray,
                                shape = RoundedCornerShape(50)
                            )
                            .padding(horizontal = if (i == pagerState.currentPage) 12.dp else 6.dp, vertical = 6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 上一步：第一页禁用（灰显）
                OutlinedButton(
                    onClick = {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }
                    },
                    enabled = pagerState.currentPage > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("上一步")
                }
                Button(
                    onClick = {
                        if (pagerState.currentPage < pages.size - 1) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            onFinish()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (pagerState.currentPage < pages.size - 1) "下一步" else "开始使用")
                }
            }

            TextButton(onClick = onFinish) {
                Text("跳过")
            }
        }
    }
}

private data class OnboardPage(
    val emoji: String,
    val title: String,
    val body: String,
    val imageRes: Int? = null,   // 有图片时优先展示（如凑企鹅）
    val isPermissionPage: Boolean = false,  // 权限页：底部渲染「授予」按钮
    val aiNotice: Boolean = false           // 是否在页面最顶部显示「本软件代码由 AI 编写」声明
)

private fun buildOnboardingPages(): List<OnboardPage> {
    val sites = Site.ALL.joinToString("、") { it.emoji + it.name }
    return listOf(
        OnboardPage(
            "🐧",
            "你好，我是凑企鹅",
            "我是这个 App 的引导小助手。\n\n" +
                "这是一个多站点下载器，帮你下载多个网站的图片、漫画、小说与视频，支持：$sites。\n\n" +
                "仅用于学习交流，请遵守当地法律与各网站条款。",
            imageRes = com.java.myapplication.R.drawable.couqi,
            aiNotice = true
        ),
        OnboardPage(
            "🚀",
            "三步快速上手",
            "① 在「下载」页选择站点\n" +
                "② 粘贴链接 / 关键词 / ID（链接会自动识别）\n" +
                "③ 点击下载（保存到固定目录，无需手动选）\n\n" +
                "下载进度与历史都在「任务」页查看——退出 App 也不会丢。"
        ),
        OnboardPage(
            "🛡️",
            "授予所需权限",
            "本 App 仅申请以下必要权限，点击下方按钮按需授予（可跳过，但部分功能将不可用）：\n\n" +
                "① 所有文件访问（核心）：将文件保存到公共目录 " +
                "/sdcard/Download/multi-downloader/，不给则回退到应用私有目录\n" +
                "② 通知：在通知栏显示下载进度（建议开启）\n" +
                "③ 省电策略（不限制）：允许后台持续下载，避免息屏后被系统限制\n\n" +
                "其余权限（唤醒锁 / 前台服务）由系统自动处理，无需手动授予。",
            isPermissionPage = true
        ),
        OnboardPage(
            "🌐",
            "记得开代理",
            "本 App 不内置任何代理，也不会替你翻墙。\n\n" +
                "很多站点（推特、PornHub、禁漫天堂等）在部分网络环境需自行开启代理 / VPN 才能访问。\n\n" +
                "若提示「网络连接失败」，先确认：网络是否通畅、是否需要开代理、站点域名是否已变更。"
        ),
        OnboardPage(
            "⏱",
            "链接有时效，尽快下",
            "部分站点（如 PornHub）的视频链接**带过期时间**：从视频页复制后放太久再下载，链接就会「失效」，表现是提示「服务器返回异常 / 链接可能已过期」。\n\n" +
                "遇到这种情况：回到该站点**重新复制一条最新链接**，然后**尽快点击下载**即可（间隔越短成功率越高）。\n\n" +
                "这不是网络问题，也无需重装 App——换条新链接重试即可。"
        ),
        OnboardPage(
            "🔑",
            "Cookie 可选，不强制",
            "Cookie 是「可选增强」：每个站点都可以选择是否使用它，但**都不强制**——不导入也能正常下载绝大多数内容。\n\n" +
                "仅少数「受限内容」（如 X/Twitter 部分推文）需要登录态才能取到。\n\n" +
                "如需使用：自行导出 cookies.txt（Netscape 格式），在「设置 → 导入 Cookie」中导入。\n\n" +
                "App 不会内置、上传或泄露你的任何凭证。"
        ),
        OnboardPage(
            "📁",
            "保存目录与 PDF",
            "下载文件统一保存到 /sdcard/Download/multi-downloader/（无需手动选择）。\n\n" +
                "漫画下载完成后可一键合成 PDF 方便阅读。\n\n" +
                "91 等站点域名可能变更，可在设置里手动更新域名。"
        )
    )
}

@Composable
private fun OnboardingPage(page: OnboardPage) {
    val context = LocalContext.current
    // 三项权限的实时状态（返回本页 / 点刷新时更新）
    var storageGranted by remember { mutableStateOf(PermissionHelper.hasAllFilesAccess(context)) }
    var notifGranted by remember { mutableStateOf(PermissionHelper.hasNotificationPermission(context)) }
    var batteryIgnored by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 最顶部：AI 声明（仅第 1 页）
        if (page.aiNotice) {
            Text(
                "本软件代码由 AI 编写",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
                textAlign = TextAlign.Center
            )
        }

        if (page.imageRes != null) {
            Image(
                painter = painterResource(page.imageRes),
                contentDescription = page.title,
                modifier = Modifier.size(140.dp)
            )
        } else {
            Text(page.emoji, fontSize = 72.sp)
        }
        Text(
            page.title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp),
            textAlign = TextAlign.Center
        )
        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )

        // 权限页：三项集中清单（每项独立入口 + 状态实时刷新）
        if (page.isPermissionPage) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ① 所有文件访问
                PermissionRow(
                    granted = storageGranted,
                    grantedText = "✅ 已获得「所有文件访问」权限",
                    deniedText = "尚未获得「所有文件访问」权限",
                    actionText = if (storageGranted) "查看/修改文件访问权限" else "授予文件访问权限",
                    onAction = { PermissionHelper.requestAllFilesAccess(context) }
                )
                // ② 通知
                PermissionRow(
                    granted = notifGranted,
                    grantedText = "✅ 已获得「通知」权限",
                    deniedText = "尚未获得「通知」权限",
                    actionText = if (notifGranted) "查看/修改通知权限" else "授予通知权限",
                    onAction = { PermissionHelper.openNotificationSettings(context) }
                )
                // ③ 省电策略（不限制）
                PermissionRow(
                    granted = batteryIgnored,
                    grantedText = "✅ 已设为「不限制」省电策略",
                    deniedText = "省电策略未设为「不限制」",
                    actionText = if (batteryIgnored) "查看/修改省电策略" else "设为不限制（忽略电池优化）",
                    onAction = { PermissionHelper.requestIgnoreBatteryOptimizations(context) }
                )

                OutlinedButton(
                    onClick = {
                        storageGranted = PermissionHelper.hasAllFilesAccess(context)
                        notifGranted = PermissionHelper.hasNotificationPermission(context)
                        batteryIgnored = PermissionHelper.isIgnoringBatteryOptimizations(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("我已授权，刷新状态")
                }
            }
        }
    }
}

/**
 * 单条权限项：状态文字 + 独立按钮。引导页三项权限每项都渲染为一行，彼此独立。
 */
@Composable
private fun PermissionRow(
    granted: Boolean,
    grantedText: String,
    deniedText: String,
    actionText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            if (granted) grantedText else deniedText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
        Button(
            onClick = onAction,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(actionText)
        }
    }
}