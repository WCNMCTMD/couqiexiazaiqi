package com.java.myapplication.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 运行时权限工具（统一入口）。
 *
 * 权限全景（本 App 实际需要）：
 *  - 存储：走「所有文件访问权」MANAGE_EXTERNAL_STORAGE（Android 11+），
 *    换取 /sdcard 真实路径读写能力 → 下载引擎可直接用 java.io.File 落盘；
 *    Android 10 及以下用 READ/WRITE_EXTERNAL_STORAGE（同为真实路径）。
 *  - 通知（POST_NOTIFICATIONS，Android 13+）：唯一需要普通弹窗申请的权限。
 *  - 相机/位置/通讯录：不使用。
 */
object PermissionHelper {

    /** 是否需要请求通知权限（Android 13+ 才需要） */
    fun needNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 是否已获得通知权限（低于 13 视为已获得） */
    fun hasNotificationPermission(ctx: Context): Boolean {
        if (!needNotificationPermission()) return true
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 供 ActivityResult 契约使用的权限数组 */
    fun notificationPermissions(): Array<String> =
        if (needNotificationPermission())
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray()

    // ---------------- 所有文件访问权（MANAGE_EXTERNAL_STORAGE） ----------------

    /**
     * 是否已获得「所有文件访问权」。
     * Android 11+：查 Environment.isExternalStorageManager()
     * Android 10-：退化为 READ/WRITE_EXTERNAL_STORAGE 检查
     */
    fun hasAllFilesAccess(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Throwable) {
                false
            }
        } else {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 跳转到系统「所有文件访问」设置页让用户手动开启。
     * 注意：这不是普通弹窗，必须用户手动操作；部分机型可能无此页，需兜底到应用详情页。
     */
    fun requestAllFilesAccess(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return
            } catch (_: Throwable) {
                // 部分 ROM 无该 action，退回应用详情页
            }
        }
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    /** Android 10 及以下，存储权限的运行时申请数组（11+ 为空，走设置页） */
    fun legacyStoragePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        else emptyArray()

    // ---------------- 通知权限（跳系统设置页，引导页无需 ActivityResult 回调） ----------------

    /**
     * 跳转到本 App 的通知设置页，让用户手动开启/关闭通知。
     * 引导页用「跳系统设置页」策略：不需要 ActivityResult 回调，返回后自行刷新状态即可。
     */
    fun openNotificationSettings(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            return
        } catch (_: Throwable) {
            // 部分 ROM 无该 action，退回应用详情页
        }
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    // ---------------- 省电策略（忽略电池优化） ----------------

    /** 是否已忽略电池优化（即已对本 App 关闭省电限制） */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE)
                    as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 跳转到系统「电池优化」页，请求忽略电池优化。
     * 优先直接拉起「忽略电池优化」确认弹窗（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）；
     * 失败（部分 ROM 禁用该 action）则退回电池优化设置列表页，由用户手动设为不优化。
     */
    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return
            }
        } catch (_: Throwable) {
            // 退回列表页
        }
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}