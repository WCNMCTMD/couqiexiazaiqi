package com.java.myapplication.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.java.myapplication.MainActivity
import com.java.myapplication.R
import com.java.myapplication.data.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载前台服务：让下载在后台可靠运行（息屏/切走不被系统回收）。
 *
 * 兼容性要点：
 *  - Android 14+（targetSdk≥34）：前台服务必须声明 foregroundServiceType，
 *    本服务用 dataSync，配合 Manifest 的 FOREGROUND_SERVICE_DATA_SYNC 权限；
 *  - 通过原生 startForeground(id, notif, 类型) 启动（不依赖 ServiceCompat，避免 core 版本差异）；
 *  - Android 13+ 通知需 POST_NOTIFICATIONS 运行时权限（UI 层申请，未授权则退化为无通知但不崩）。
 *
 * 架构：执行者。UI（DownloadViewModel）只发起任务与展示状态；
 * 本服务负责真正下载，并在开始/结束时把状态落盘（TaskStore），
 * 因此 Activity 销毁/进程回收后重进仍能看到最新任务状态。
 * 事件回传通过进程内 [DownloadBus]。
 *
 * 安全：不持有任何凭证；cookie 由 Http.cookieHeader 在进程内统一提供。
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.java.myapplication.action.START_TASK"
        const val ACTION_STOP = "com.java.myapplication.action.STOP_ALL"

        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SITE_ID = "site_id"
        const val EXTRA_INPUT = "input"
        const val EXTRA_OUT_DIR = "out_dir"
        const val EXTRA_OPTIONS = "options"   // 以 "k=v\nk=v" 编码

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIF_ID = 1001

        /** 启动一次下载任务 */
        fun startTask(
            ctx: Context,
            taskId: Long,
            siteId: String,
            input: String,
            outDir: String,
            options: Map<String, String>
        ) {
            val i = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_SITE_ID, siteId)
                putExtra(EXTRA_INPUT, input)
                putExtra(EXTRA_OUT_DIR, outDir)
                putExtra(EXTRA_OPTIONS, options.entries.joinToString("\n") { "${it.key}=${it.value}" })
            }
            // Android 8+ 用 startForegroundService；本服务需尽快 startForeground 否则 ANR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(2)
    private val activeCount = AtomicInteger(0)

    /** taskId -> 标题（用于通知展示当前任务） */
    private val titles = ConcurrentHashMap<Long, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 立刻进入前台（Android 14+ 带 dataSync 类型）
                startAsForeground("准备下载…")
                handleTask(intent)
            }
            ACTION_STOP -> {
                stopSelfSafely()
            }
            else -> {
                startAsForeground("下载中…")
            }
        }
        return START_NOT_STICKY
    }

    private fun handleTask(intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val siteId = intent.getStringExtra(EXTRA_SITE_ID) ?: return
        val input = intent.getStringExtra(EXTRA_INPUT) ?: return
        val outDir = intent.getStringExtra(EXTRA_OUT_DIR) ?: return
        val options = parseOptions(intent.getStringExtra(EXTRA_OPTIONS))

        val downloader = DownloadManager.get(siteId)
        if (downloader == null) {
            DownloadBus.emit(DownloadBus.Event.Failed(taskId, "该站点下载引擎尚未接入"))
            maybeStop()
            return
        }

        activeCount.incrementAndGet()
        emitState()
        updateNotification("下载中（${activeCount.get()} 项）")

        // ===== 本地代理自动探测（App 流量默认不走系统"全局代理"，需主动发现本地 HTTP 代理）=====
        runCatching {
            if (!Http.proxyEnabled()) {
                val pr = ProxyProbe.detect()
                if (pr != null) {
                    Http.proxyHost = pr.host
                    Http.proxyPort = pr.port
                    DiagLog.error("PROXY", "已启用本地代理 ${pr.host}:${pr.port}")
                } else {
                    DiagLog.error("PROXY", "未探测到本地代理，保持直连")
                }
            }
        }

        // 诊断日志：按任务输出目录设定落盘文件（出口 B），便于用户打包回传
        runCatching {
            val logsDir = File(outDir, "logs")
            DiagLog.setLogFile(File(logsDir, "diag-${siteId}-${taskId}.log"))
            DiagLog.info("TASK", "开始 site=$siteId input=${DiagLog.maskUrl(input)} out=$outDir")
        }

        // 落盘：任务开始执行（Service 独立于 ViewModel 生命周期，直接写磁盘保证状态不丢）
        runCatching { TaskStore.upsertTaskStatus(this, taskId, TaskStatus.RUNNING, speedText = "") }

        scope.launch {
            semaphore.withPermit {
                val cb = object : ProgressCallback {
                    override fun onProgress(taskId: Long, progress: Float, done: Int, total: Int, speed: String) {
                        DownloadBus.emit(DownloadBus.Event.Progress(taskId, progress, done, total, speed))
                        if (done == 1 || done % 5 == 0) {
                            updateNotification("下载中 ${(progress * 100).toInt()}% · $speed")
                        }
                    }

                    override fun onDone(taskId: Long, outputPath: String) {
                        DownloadBus.emit(DownloadBus.Event.Done(taskId, outputPath))
                        runCatching {
                            TaskStore.upsertTaskStatus(
                                this@DownloadService, taskId, TaskStatus.DONE,
                                progress = 1f, outputPath = outputPath, speedText = ""
                            )
                        }
                    }

                    override fun onFailed(taskId: Long, error: String) {
                        DownloadBus.emit(DownloadBus.Event.Failed(taskId, error))
                        DiagLog.error("TASK", "任务失败 task=$taskId | $error")
                        runCatching {
                            TaskStore.upsertTaskStatus(
                                this@DownloadService, taskId, TaskStatus.FAILED,
                                errorMsg = error, speedText = ""
                            )
                        }
                    }
                }
                try {
                    downloader.download(taskId, input, outDir, options, cb)
                } catch (e: Exception) {
                    val msg = FailReason.msg(FailReason.NETWORK, e.message ?: "异常")
                    DownloadBus.emit(DownloadBus.Event.Failed(taskId, msg))
                    runCatching {
                        TaskStore.upsertTaskStatus(
                            this@DownloadService, taskId, TaskStatus.FAILED,
                            errorMsg = msg, speedText = ""
                        )
                    }
                } finally {
                    activeCount.decrementAndGet()
                    emitState()
                    if (activeCount.get() <= 0) {
                        maybeStop()
                    } else {
                        updateNotification("下载中（${activeCount.get()} 项）")
                    }
                }
            }
        }
    }

    private fun parseOptions(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()
        return s.split('\n').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
    }

    private fun emitState() {
        DownloadBus.emit(DownloadBus.Event.ServiceState(activeCount.get() > 0, activeCount.get()))
    }

    private fun maybeStop() {
        DownloadBus.emit(DownloadBus.Event.ServiceState(false, 0))
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        try {
            // minSdk=24，STOP_FOREGROUND_REMOVE 自 API 24 起可用，直接原生调用，
            // 避免依赖 ServiceCompat（--offline 恢复出的 core 版本可能缺该类）。
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    // ---- 通知 ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "下载",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "显示下载进度" }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("多站点下载器")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startAsForeground(text: String) {
        val notif = buildNotification(text)
        try {
            // 不依赖 ServiceCompat 的重载版本（不同 core 版本可能缺失，--offline 下尤为明显）。
            // 直接按 SDK 分支调用原生 API：
            //  - Android Q(29)+        ：startForeground(id, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            //  - Android O(26)–P(28)   ：startForeground(id, notif)（带渠道即可）
            //  - 更低                   ：同样 startForeground(id, notif)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
            // 极端情况下（如平台强校验/通知不可用），退化为普通 service，不崩
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}