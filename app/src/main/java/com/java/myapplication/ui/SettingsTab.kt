package com.java.myapplication.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.java.myapplication.data.AppSettings
import com.java.myapplication.data.CookieStore
import com.java.myapplication.data.DEFAULT_91_DOMAIN
import com.java.myapplication.util.AppPaths
import com.java.myapplication.util.PermissionHelper
import com.java.myapplication.util.Updater

/**
 * 设置页：保存目录（固定真实路径）、全文件访问权、Cookie 导入/清除、91 自定义域名、
 * 并发、命名模式、自动 PDF、webp 转码开关、重新查看引导。
 */
@Composable
fun SettingsTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 状态：cookie 状态 / 91 域名
    var cookieState by remember { mutableStateOf(context.hasCookie()) }
    var domain by remember { mutableStateOf(AppSettings.domain91(context)) }
    var concurrency by remember { mutableStateOf(AppSettings.concurrency(context).toString()) }
    var nameMode by remember { mutableStateOf(AppSettings.nameMode(context)) }
    var autoPdf by remember { mutableStateOf(AppSettings.autoPdf(context)) }
    var webpToJpg by remember { mutableStateOf(AppSettings.webpToJpg(context)) }
    var slowNet by remember { mutableStateOf(AppSettings.slowNet(context)) }
    var netSummary by remember { mutableStateOf("") }
    // 全文件访问权状态（true=已授权）与保存目录展示值
    var hasStorageAccess by remember { mutableStateOf(PermissionHelper.hasAllFilesAccess(context)) }
    // 省电策略（忽略电池优化）状态
    var batteryIgnored by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }
    val fixedOutDir = remember { AppPaths.root(context) }

    // 诊断日志状态（出口 A）
    var diagVerbose by remember { mutableStateOf(com.java.myapplication.engine.DiagLog.verbose) }
    var diagText by remember { mutableStateOf("") }
    var diagShow by remember { mutableStateOf(false) }
    var diagStatus by remember { mutableStateOf("") }

    // 应用内更新（OTA）
    var updateUrl by remember { mutableStateOf(AppSettings.updateManifestUrl(context)) }
    var autoCheck by remember { mutableStateOf(AppSettings.autoCheckUpdate(context)) }
    var updateStatus by remember { mutableStateOf("") }
    var pendingDownloadId by remember { mutableStateOf(-1L) }
    val uiScope = rememberCoroutineScope()

    // Cookie 导入选择器
    val cookieLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val n = CookieStore.import(context, uri)
            if (n > 0) {
                CookieStore.loadIntoHeader(context)
                cookieState = true
            } else {
                cookieState = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        // 保存目录（固定真实路径）+ 所有文件访问权
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("保存目录", style = MaterialTheme.typography.titleMedium)
                Text(
                    fixedOutDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "六站产物按站点分子目录存放：91 / jm / hentaipaw / pornhub / twitter / cool18，日志在 logs/。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 全文件访问权状态
                Text(
                    if (hasStorageAccess) "✅ 已获得「所有文件访问」权限"
                    else "⚠️ 未获得「所有文件访问」权限：下载将回退到应用私有目录，且可能在外部存储写入失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasStorageAccess) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                // 省电策略状态（下载需后台持续运行，避免息屏被系统限制）
                Text(
                    if (batteryIgnored) "✅ 省电策略：已设为「不限制」（忽略电池优化）"
                    else "⚠️ 省电策略：未设为「不限制」，息屏 / 后台下载可能被系统限制",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (batteryIgnored) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasStorageAccess) {
                        Button(onClick = {
                            PermissionHelper.requestAllFilesAccess(context)
                        }) {
                            Text("授予文件访问权限")
                        }
                    } else {
                        OutlinedButton(onClick = {
                            // 打开固定目录（用系统文件管理器）
                            // 【修复】旧实现用 file:// + resource/folder：Android 7+ 会抛
                            // FileUriExposedException 且被 catch 吞掉 → 表现为"点了没反应"。
                            // 现改为：优先用 SAF 文档 URI（content://），逐级兜底；全失败则复制路径+Toast。
                            openFolder(context, fixedOutDir)
                        }) {
                            Text("打开目录")
                        }
                        TextButton(onClick = {
                            // 手动刷新权限状态（用户从设置页返回后）
                            hasStorageAccess = PermissionHelper.hasAllFilesAccess(context)
                            batteryIgnored = PermissionHelper.isIgnoringBatteryOptimizations(context)
                        }) {
                            Text("刷新状态")
                        }
                    }
                }
                if (!batteryIgnored) {
                    OutlinedButton(onClick = {
                        PermissionHelper.requestIgnoreBatteryOptimizations(context)
                    }) {
                        Text("设为不限制（忽略电池优化）")
                    }
                }
                Text(
                    "提示：授予后请返回本页点「刷新状态」；若仍显示未授予，请到系统设置 → 应用 → 本 App → 权限 → 所有文件访问 手动开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Cookie
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("登录 Cookie", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (cookieState) "✅ 已导入 cookies.txt（站点可选使用，不强制）" else "未导入（可选，不强制；仅少数受限内容如 X/Twitter 需要登录态）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cookieState) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "仅从你选择的本机文件导入，App 不会内置、上传或泄露凭证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { cookieLauncher.launch(arrayOf("text/plain", "*/*")) }) {
                        Text("导入 Cookie")
                    }
                    if (cookieState) {
                        OutlinedButton(onClick = {
                            CookieStore.clear(context)
                            CookieStore.loadIntoHeader(context)
                            cookieState = false
                        }) {
                            Text("清除")
                        }
                    }
                }
            }
        }

        // 91 自定义域名
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("91 自定义域名", style = MaterialTheme.typography.titleMedium)
                Text(
                    "域名可能变更，可在此手动更新（默认 $DEFAULT_91_DOMAIN）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("域名（含 https://）") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        AppSettings.setDomain91(context, domain)
                        domain = AppSettings.domain91(context)
                    }) {
                        Text("保存")
                    }
                    TextButton(onClick = {
                        AppSettings.setDomain91(context, DEFAULT_91_DOMAIN)
                        domain = DEFAULT_91_DOMAIN
                    }) {
                        Text("恢复默认")
                    }
                }
            }
        }

        // 并发数
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("并发下载数", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = concurrency,
                    onValueChange = { concurrency = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("1 - 16") }
                )
                Button(onClick = {
                    val n = concurrency.toIntOrNull()?.coerceIn(1, 16)
                    if (n != null) {
                        AppSettings.setConcurrency(context, n)
                        concurrency = n.toString()
                    }
                }) {
                    Text("保存")
                }
            }
        }

        // 网络（弱网 / 代理自检）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("网络（弱网 / 代理）", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("弱网模式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "放宽超时（连接 30s / 读取 90s）并增大重试（4 次），弱网下更稳",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = slowNet,
                        onCheckedChange = {
                            slowNet = it
                            AppSettings.setSlowNet(context, it)
                            // 清除显式的超时/重试覆盖，让弱网默认值生效
                            AppSettings.applyToHttp(context)
                        }
                    )
                }
                Button(onClick = {
                    val r = com.java.myapplication.engine.NetCheck.inspect(context)
                    netSummary = r.summary
                }) {
                    Text("网络 / 代理自检")
                }
                if (netSummary.isNotBlank()) {
                    Text(
                        netSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "本 App 不内置、不修改任何代理；推特 / PornHub / 禁漫等站点可能需要你自行开启代理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ===== 诊断日志（排障专用，出口 A：界面查看/复制）=====
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("诊断日志（排障）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "下载失败时打开可看到请求 URL / HTTP 状态 / 响应片段，用于定位原因。" +
                        "不含任何 Cookie 值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("详细模式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "记录每次请求（成功也记），平时可关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = diagVerbose,
                        onCheckedChange = {
                            diagVerbose = it
                            com.java.myapplication.engine.DiagLog.verbose = it
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        diagText = com.java.myapplication.engine.DiagLog.snapshot()
                            .ifBlank { "（暂无日志，先跑一次下载）" }
                        diagShow = true
                    }) {
                        Text("查看日志")
                    }
                    OutlinedButton(onClick = {
                        com.java.myapplication.engine.DiagLog.clear()
                        diagText = ""
                        diagStatus = "已清空内存日志"
                    }) {
                        Text("清空")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        // 出口 B：导出到 Download/multi-downloader/logs/
                        val r = exportDiagLog(context)
                        diagStatus = r
                    }) {
                        Text("导出到文件")
                    }
                    if (diagStatus.isNotBlank()) {
                        Text(
                            diagStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 命名模式
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件命名模式", style = MaterialTheme.typography.titleMedium)
                val modes = listOf(
                    "title" to "标题（默认）",
                    "id" to "ID",
                    "cn" to "中文名"
                )
                modes.forEach { (key, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { nameMode = key; AppSettings.setNameMode(context, key) }) {
                            Text(
                                (if (nameMode == key) "● " else "○ ") + label,
                                color = if (nameMode == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 自动 PDF
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("自动合成 PDF", style = MaterialTheme.typography.titleMedium)
                Text(
                    "漫画下载完成后是否自动合成 PDF（0=关闭 1=自动）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val pdfModes = listOf(0 to "关闭", 1 to "自动")
                pdfModes.forEach { (key, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { autoPdf = key; AppSettings.setAutoPdf(context, key) }) {
                            Text(
                                (if (autoPdf == key) "● " else "○ ") + label,
                                color = if (autoPdf == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // webp 转 jpg
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("WebP 转 JPG", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "下载后将 WebP 图片转成 JPG（某些站点全是 WebP）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = webpToJpg,
                        onCheckedChange = { webpToJpg = it; AppSettings.setWebpToJpg(context, it) }
                    )
                }
            }
        }

        // 应用内更新（OTA，方案 B）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("检查更新", style = MaterialTheme.typography.titleMedium)
                Text(
                    "当前版本 v${Updater.currentVersionName(context)}（${Updater.currentVersionCode(context)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "填写你自己的公网静态 version.json 地址（App 不内置任何私人地址）。" +
                        "清单字段：versionCode / versionName / url / changelog / minSupported",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = updateUrl,
                    onValueChange = { updateUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("更新清单地址（含 https:// 或 http://）") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启动时自动检查", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "默认关闭，避免打扰",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoCheck,
                        onCheckedChange = {
                            autoCheck = it
                            AppSettings.setAutoCheckUpdate(context, it)
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        AppSettings.setUpdateManifestUrl(context, updateUrl)
                        updateStatus = "检查中…"
                        uiScope.launch {
                            val msg = withContext(Dispatchers.IO) {
                                when (val r = Updater.check(context)) {
                                    is Updater.CheckResult.UpToDate ->
                                        "已是最新版本（${r.currentCode}）"
                                    is Updater.CheckResult.Update -> {
                                        // 记录本次清单，供下载按钮使用
                                        val id = Updater.download(context, r.manifest)
                                        if (id > 0) {
                                            pendingDownloadId = id
                                            "发现新版本 v${r.manifest.versionName}，已开始下载…\n${r.manifest.changelog}"
                                        } else {
                                            "发现新版本 v${r.manifest.versionName}，但下载启动失败"
                                        }
                                    }
                                    is Updater.CheckResult.Error -> "检查失败：${r.message}"
                                }
                            }
                            updateStatus = msg
                        }
                    }) {
                        Text("检查更新")
                    }
                    if (pendingDownloadId > 0) {
                        OutlinedButton(onClick = {
                            if (!Updater.canInstallPackages(context)) {
                                Updater.openInstallPermissionSettings(context)
                                updateStatus = "请先允许「安装未知来源应用」，再点安装"
                            } else if (Updater.install(context)) {
                                updateStatus = "已拉起系统安装器"
                            } else {
                                updateStatus = "安装包不存在或无法拉起安装器"
                            }
                        }) {
                            Text("安装新版")
                        }
                    }
                }
                if (updateStatus.isNotBlank()) {
                    Text(
                        updateStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "提示：覆盖安装要求新旧版本使用同一签名。若未固定 release 签名，本功能只能下载新版，需手动卸载旧版后安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 重新查看引导
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("新手引导", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    AppSettings.setOnboarded(context, false)
                    // 重启到引导页：显式用 MainActivity（getLaunchIntentForPackage 可能返回 null，
                    // 且未必落到引导页）。NEW_TASK + CLEAR_TASK 确保清栈重进。
                    try {
                        val intent = android.content.Intent(context, com.java.myapplication.MainActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context,
                            "无法打开引导页：${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }) {
                    Text("重新查看引导")
                }
            }
        }
    }

    // 诊断日志查看对话框（出口 A）
    if (diagShow) {
        AlertDialog(
            onDismissRequest = { diagShow = false },
            title = { Text("诊断日志") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(diagText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("diag", diagText))
                    diagStatus = "已复制到剪贴板"
                    diagShow = false
                }) {
                    Text("复制")
                }
            },
            dismissButton = {
                TextButton(onClick = { diagShow = false }) { Text("关闭") }
            }
        )
    }
}

private fun Context.hasCookie(): Boolean = CookieStore.hasCookie(this)

/**
 * 打开指定文件夹（分层兜底，绝不静默失败）。
 *
 * 背景：旧实现直接用 `file://` URI 配 ACTION_VIEW，Android 7+ 会抛
 * FileUriExposedException 并被 catch 吞掉，用户看到的就是"点了没反应"。
 *
 * 兜底顺序：
 *  1) 标准 SAF 目录 URI：content://com.android.externalstorage.documents/document/primary%3A<path>
 *     —— 多数机型（含 MIUI）可用系统「文件」打开该目录。
 *  2) 通用 documents UI：ACTION_OPEN_DOCUMENT_TREE 定位到目标目录（用户可直接看到内容）。
 *  3) 全部失败：把绝对路径写入剪贴板并 Toast 提示，保证"有反馈"。
 *
 * @param dirPath 目标目录绝对路径（如 /storage/emulated/0/Download/multi-downloader）
 */
private fun openFolder(context: Context, dirPath: String) {
    val dir = java.io.File(dirPath)
    if (!dir.exists()) dir.mkdirs()

    // 把 /storage/emulated/0/... 转成外部存储根相对路径（primary:Download/multi-downloader）
    val abs = dir.absolutePath
    val extRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
    val rel = if (abs.startsWith(extRoot)) abs.substring(extRoot.length).trimStart('/') else null

    // 方案 1：externalstorage.documents 目录 URI
    if (rel != null) {
        try {
            val docId = "primary:" + rel
            val uri = android.net.Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    android.net.Uri.encode(docId)
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // 落到方案 2
        }
    }

    // 方案 2：documents UI 选择器（让用户直接定位到该目录）
    try {
        val uri = if (rel != null) {
            android.net.Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    android.net.Uri.encode("primary:" + rel)
            )
        } else {
            android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary")
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra("android.provider.extra.INITIAL_URI", uri)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return
    } catch (_: Exception) {
        // 落到方案 3
    }

    // 方案 3：复制路径 + Toast（保证有反馈）
    try {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("path", abs))
    } catch (_: Exception) {
    }
    android.widget.Toast.makeText(
        context,
        "本机无文件管理器可打开，路径已复制：\n$abs",
        android.widget.Toast.LENGTH_LONG
    ).show()
}

/**
 * 出口 B：把当前内存诊断日志导出到文件。
 * 优先写到外置存储 Download/multi-downloader/logs/，失败回退到应用私有 filesDir。
 * @return 可读结果提示（含最终路径）
 */
private fun exportDiagLog(ctx: Context): String {
    return try {
        val snapshot = com.java.myapplication.engine.DiagLog.snapshot()
        if (snapshot.isBlank()) return "（暂无日志，先跑一次下载）"
        val dir = AppPaths.logsFile(ctx)
        val ok = dir.exists() || dir.mkdirs()
        val target = if (ok) {
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            java.io.File(dir, "diag-$ts.log")
        } else {
            java.io.File(ctx.filesDir, "diag-export.log")
        }
        target.writeText(snapshot)
        "已导出：${target.absolutePath}"
    } catch (e: Exception) {
        "导出失败：${e.message}"
    }
}
