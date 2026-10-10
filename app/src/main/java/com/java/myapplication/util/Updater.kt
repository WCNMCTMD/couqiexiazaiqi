package com.java.myapplication.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.java.myapplication.data.AppSettings
import com.java.myapplication.engine.Http
import java.io.File

/**
 * 应用内更新（OTA，方案 B）。
 *
 * 流程：
 *   1. 拉取静态清单 version.json（用户自行配置公网地址，App 不硬编码任何私人地址）
 *   2. 比对清单 versionCode 与本地 versionCode
 *   3. 有新版本 → 系统 DownloadManager 下载 APK 到「应用外部私有目录 /update」
 *   4. 下载完成 → FileProvider 暴露 content:// → 拉起系统安装器（覆盖安装同签名包）
 *
 * 设计取舍：
 *   - **零第三方依赖**：清单解析用手写的极简扁平 JSON 字段提取器（只认字符串/数字字段），
 *     不引入 org.json / kotlinx.serialization，便于日后审阅（符合"可控、逆向不暴露"要求）。
 *   - **安全**：不内置任何 token / cookie / 私人 URL；清单地址由用户在设置里填写。
 *   - **权限**：覆盖安装需同签名 + 用户授予「安装未知来源应用」（REQUEST_INSTALL_PACKAGES）。
 *     若 release 签名未固定，只能下载新版，无法覆盖安装——这是已知前置条件。
 */
object Updater {

    /** 更新清单解析结果 */
    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val changelog: String,
        val minSupported: Int
    )

    /** 检查结果 */
    sealed class CheckResult {
        /** 已是最新 */
        data class UpToDate(val currentCode: Int) : CheckResult()
        /** 发现新版本 */
        data class Update(val manifest: Manifest, val currentCode: Int) : CheckResult()
        /** 失败（未配置、网络错误、清单格式错误等） */
        data class Error(val message: String) : CheckResult()
    }

    private const val UPDATE_DIR = "update"
    private const val APK_NAME = "update.apk"

    /** 本地 versionCode（0 表示读取失败） */
    fun currentVersionCode(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
            else it.versionCode
        }
    } catch (_: Exception) {
        0
    }

    /** 本地 versionName（"?" 表示读取失败） */
    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /**
     * 检查更新。**必须在非主线程调用**（内部是阻塞网络请求）。
     * @param manifestUrl 留空时读取 AppSettings 里用户配置的地址
     */
    fun check(ctx: Context, manifestUrl: String? = null): CheckResult {
        val url = (manifestUrl ?: AppSettings.updateManifestUrl(ctx)).trim()
        if (url.isEmpty()) {
            return CheckResult.Error("未配置更新清单地址（设置 → 检查更新 里填写）")
        }
        val text = try {
            Http.getText(url)
        } catch (e: Exception) {
            return CheckResult.Error("拉取更新清单失败：${e.message}")
        }
        val m = try {
            parseManifest(text)
        } catch (e: Exception) {
            return CheckResult.Error("更新清单格式错误：${e.message}")
        }
        AppSettings.setLastUpdateCheck(ctx, System.currentTimeMillis())
        val cur = currentVersionCode(ctx)
        return if (m.versionCode > cur) CheckResult.Update(m, cur) else CheckResult.UpToDate(cur)
    }

    /**
     * 用系统 DownloadManager 下载新版 APK 到外部私有 update 目录。
     * @return 下载任务 id（用于订阅完成广播），-1 表示失败
     */
    fun download(ctx: Context, manifest: Manifest): Long {
        return try {
            val dir = updateDir(ctx).apply { if (!exists()) mkdirs() }
            // 清理旧包，避免残留
            try {
                File(dir, APK_NAME).takeIf { it.exists() }?.delete()
            } catch (_: Exception) {
            }
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(manifest.url))
                .setTitle("正在下载新版本 v${manifest.versionName}")
                .setDescription(manifest.changelog.ifBlank { "应用更新" })
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 下载到应用外部私有目录 /Android/data/<pkg>/files/update/update.apk
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "$UPDATE_DIR/$APK_NAME")
            dm.enqueue(req)
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 下载完成后调用：用 FileProvider 把 APK 交给系统安装器。
     *
     * 注意：首次需要在系统里授予本 App「安装未知来源应用」；覆盖安装要求新旧包同签名。
     */
    fun install(ctx: Context): Boolean {
        val apk = File(updateDir(ctx), APK_NAME)
        if (!apk.exists()) return false
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            // 常见原因：该 ROM 不认 ACTION_VIEW，改用 ACTION_INSTALL_PACKAGE 兜底
            try {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** 是否已授予「安装未知来源应用」（Android 8+；低于 8 恒为 true） */
    fun canInstallPackages(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true

    /** 跳转到「安装未知来源应用」授权页（仅 Android 8+） */
    fun openInstallPermissionSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /**
     * 注册一个「下载完成后自动拉起安装器」的一次性广播接收器。
     * 调用方应在 onDestroy / 完成后注销。
     */
    fun registerInstallOnComplete(ctx: Context, downloadId: Long, onDone: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                try {
                    c?.unregisterReceiver(this)
                } catch (_: Exception) {
                }
                onDone()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        return receiver
    }

    private fun updateDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, UPDATE_DIR)

    // ---------------------------------------------------------------------
    // 极简扁平 JSON 字段提取器（零依赖）
    // 只支持形如 {"versionCode":2,"versionName":"1.1","url":"..."} 的扁平对象，
    // 足够 OTA 清单使用；不处理嵌套/数组（清单也不需要）。
    // ---------------------------------------------------------------------

    private fun parseManifest(json: String): Manifest {
        val versionCode = jsonInt(json, "versionCode")
            ?: jsonString(json, "versionName")?.let { 0 } // 兜底：至少要求 versionCode
            ?: throw IllegalArgumentException("缺少 versionCode")
        val versionName = jsonString(json, "versionName") ?: versionCode.toString()
        val url = jsonString(json, "url") ?: throw IllegalArgumentException("缺少 url")
        val changelog = jsonString(json, "changelog") ?: ""
        val minSupported = jsonInt(json, "minSupported") ?: 0
        return Manifest(versionCode, versionName, url, changelog, minSupported)
    }

    /** 提取字符串字段值（支持 \" \\ \n \t 转义） */
    fun jsonString(json: String, key: String): String? {
        val idx = findKey(json, key) ?: return null
        // 跳过冒号与空白，定位到值的起始引号
        var i = idx
        while (i < json.length && json[i] != ':') i++
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    when (val n = json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        else -> sb.append(n)
                    }
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> {
                    sb.append(c); i++
                }
            }
        }
        return sb.toString()
    }

    /** 提取数字字段值（int） */
    fun jsonInt(json: String, key: String): Int? {
        val idx = findKey(json, key) ?: return null
        var i = idx
        while (i < json.length && json[i] != ':') i++
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '-' || json[i] == '+')) i++
        if (i == start) return null
        return json.substring(start, i).toIntOrNull()
    }

    /** 找到 "key" 后面紧跟冒号的位置索引（返回 key 结束引号的下一位） */
    private fun findKey(json: String, key: String): Int? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val p = json.indexOf(needle, from)
            if (p < 0) return null
            var i = p + needle.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') return i
            from = p + needle.length
        }
    }
}
