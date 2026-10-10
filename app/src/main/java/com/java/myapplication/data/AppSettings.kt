package com.java.myapplication.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局设置存储（SharedPreferences）。
 *
 * 安全提醒：这里只存「非敏感」的用户偏好（目录、并发、开关、自定义域名）。
 * 绝不存 cookie / 密钥 / 令牌。cookie 见 CookieStore（运行时用户导入，隔离存储）。
 */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_OUTPUT_DIR = "output_dir"
    private const val KEY_CONCURRENCY = "concurrency"
    private const val KEY_AUTO_PDF = "auto_pdf"
    private const val KEY_WEBP_TO_JPG = "webp_to_jpg"
    private const val KEY_NAME_MODE = "name_mode"
    private const val KEY_91_DOMAIN = "domain_91"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_SLOW_NET = "slow_net"          // 弱网模式：放宽超时、增大重试
    private const val KEY_CONNECT_TIMEOUT = "connect_timeout_ms"
    private const val KEY_READ_TIMEOUT = "read_timeout_ms"
    private const val KEY_MAX_RETRIES = "max_retries"
    // ---- OTA（应用内更新，方案 B）----
    private const val KEY_UPDATE_URL = "update_manifest_url"
    private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun outputDir(ctx: Context): String =
        sp(ctx).getString(KEY_OUTPUT_DIR, "") ?: ""

    fun setOutputDir(ctx: Context, dir: String) =
        sp(ctx).edit().putString(KEY_OUTPUT_DIR, dir).apply()

    fun concurrency(ctx: Context): Int =
        sp(ctx).getInt(KEY_CONCURRENCY, 8)

    fun setConcurrency(ctx: Context, n: Int) =
        sp(ctx).edit().putInt(KEY_CONCURRENCY, n).apply()

    // 0=关闭 1=自动合成（旧三态 0=关闭 1=询问 2=自动 已收敛为两态）
    // 旧值迁移（选项 A）：历史「询问」(1) 与「自动」(2) 归一为「自动」(1)。
    fun autoPdf(ctx: Context): Int {
        val raw = sp(ctx).getInt(KEY_AUTO_PDF, 1)
        return when (raw) {
            0 -> 0
            1 -> 1
            2 -> 1
            else -> 1
        }
    }

    fun setAutoPdf(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_AUTO_PDF, if (v == 0) 0 else 1).apply()

    fun webpToJpg(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_WEBP_TO_JPG, false)

    fun setWebpToJpg(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_WEBP_TO_JPG, v).apply()

    // 命名模式：title / id / cn
    fun nameMode(ctx: Context): String =
        sp(ctx).getString(KEY_NAME_MODE, "title") ?: "title"

    fun setNameMode(ctx: Context, v: String) =
        sp(ctx).edit().putString(KEY_NAME_MODE, v).apply()

    /** 91 自定义域名（域名可能变更，用户可覆盖） */
    fun domain91(ctx: Context): String =
        sp(ctx).getString(KEY_91_DOMAIN, DEFAULT_91_DOMAIN) ?: DEFAULT_91_DOMAIN

    fun setDomain91(ctx: Context, domain: String) {
        val d = domain.trim().removeSuffix("/")
        sp(ctx).edit().putString(KEY_91_DOMAIN, d).apply()
    }

    fun onboarded(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ONBOARDED, v).apply()

    // ---- 网络（弱网 / 超时 / 重试）----

    /** 弱网模式：放宽超时、增大重试，避免弱网下误判失败 */
    fun slowNet(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_SLOW_NET, false)

    fun setSlowNet(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SLOW_NET, v).apply()

    /** 连接超时（毫秒）。未显式设置时按弱网模式取默认值 */
    fun connectTimeoutMs(ctx: Context): Int =
        sp(ctx).getInt(KEY_CONNECT_TIMEOUT, if (slowNet(ctx)) 30000 else 15000)

    fun setConnectTimeoutMs(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_CONNECT_TIMEOUT, v.coerceIn(3000, 120000)).apply()

    /** 读取超时（毫秒）。未显式设置时按弱网模式取默认值 */
    fun readTimeoutMs(ctx: Context): Int =
        sp(ctx).getInt(KEY_READ_TIMEOUT, if (slowNet(ctx)) 90000 else 30000)

    fun setReadTimeoutMs(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_READ_TIMEOUT, v.coerceIn(3000, 300000)).apply()

    /** 失败重试次数。未显式设置时按弱网模式取默认值 */
    fun maxRetries(ctx: Context): Int =
        sp(ctx).getInt(KEY_MAX_RETRIES, if (slowNet(ctx)) 4 else 2)

    fun setMaxRetries(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_MAX_RETRIES, v.coerceIn(0, 8)).apply()

    /**
     * 把当前网络设置应用到 Http（启动 / 修改设置后调用）
     */
    fun applyToHttp(ctx: Context) {
        com.java.myapplication.engine.Http.connectTimeoutMs = connectTimeoutMs(ctx)
        com.java.myapplication.engine.Http.readTimeoutMs = readTimeoutMs(ctx)
        com.java.myapplication.engine.Http.maxRetries = maxRetries(ctx)
    }

    // ---- 应用内更新（OTA，方案 B）----

    /** 更新清单 version.json 地址（可留空禁用；支持用户自行换成任意公网静态地址） */
    fun updateManifestUrl(ctx: Context): String =
        sp(ctx).getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL

    fun setUpdateManifestUrl(ctx: Context, url: String) =
        sp(ctx).edit().putString(KEY_UPDATE_URL, url.trim()).apply()

    /** 启动时自动检查更新（默认关，避免打扰；用户可开） */
    fun autoCheckUpdate(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_AUTO_CHECK_UPDATE, false)

    fun setAutoCheckUpdate(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_AUTO_CHECK_UPDATE, v).apply()

    /** 记录上次检查更新时间（毫秒），用于节流（如 24h 内不重复自动检查） */
    fun lastUpdateCheck(ctx: Context): Long =
        sp(ctx).getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(ctx: Context, t: Long) =
        sp(ctx).edit().putLong(KEY_LAST_UPDATE_CHECK, t).apply()

    /**
     * 默认更新清单地址：**留空 = 未配置**。
     * 不硬编码任何私人地址/密钥（安全红线）；由用户在「设置 → 检查更新」里填入自己的
     * 公网静态 version.json 地址（如 GitHub Raw / 对象存储）。
     */
    const val DEFAULT_UPDATE_URL = ""
}