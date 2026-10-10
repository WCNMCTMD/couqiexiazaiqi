package com.java.myapplication.data

/**
 * 站点元数据。
 *
 * 注意（安全红线）：本文件不允许出现任何 API 密钥 / token / cookie / 作者身份信息。
 * 所有需要凭证的能力一律由 App 使用者运行时自己导入，代码一律「零密钥」。
 */
enum class SiteCategory(val label: String) {
    NOVEL("小说"),
    MANGA("漫画"),
    VIDEO("视频"),
    SOCIAL("社交")
}

data class Site(
    val id: String,           // 内部唯一标识
    val name: String,         // 显示名
    val category: SiteCategory,
    val emoji: String,        // 列表/卡片图标（无图片资源时的 fallback）
    val homeUrl: String,      // 主站跳转链接（外跳系统浏览器）
    val logoRes: Int? = null, // 站点图标 drawable 资源 id（有则优先显示）
    val needsCookie: Boolean = false,   // 该站是否「可选」使用登录 cookie（有则用、无也不拦；不强制）
    val customDomain: Boolean = false,  // 是否支持用户自定义域名（如 91 会变域名）
    val timeSensitive: Boolean = false, // 直链是否有时效（过期就下载失败，如 PornHub）
    val hint: String = ""     // 输入框提示
) {
    companion object {
        val ALL = listOf(
            Site(
                id = "cool18",
                name = "禁忌书屋",
                category = SiteCategory.NOVEL,
                emoji = "📄",
                homeUrl = "https://www.cool18.com/bbs4/index.php",
                logoRes = com.java.myapplication.R.drawable.site_cool18,
                hint = "粘贴链接 / 关键词 / tid（如 14600069）"
            ),
            Site(
                id = "91",
                name = "91视频",
                category = SiteCategory.VIDEO,
                emoji = "🎬",
                homeUrl = "https://91porny.com/",
                logoRes = com.java.myapplication.R.drawable.site_91,
                customDomain = true,
                hint = "粘贴链接 / viewkey / 视频 id（如 1245600219）"
            ),
            Site(
                id = "pornhub",
                name = "PornHub",
                category = SiteCategory.VIDEO,
                emoji = "🎞",
                homeUrl = "https://www.pornhub.com/",
                timeSensitive = true,
                hint = "粘贴视频链接 / viewkey（如 ph5f2c…）"
            ),
            Site(
                id = "twitter",
                name = "推特X",
                category = SiteCategory.SOCIAL,
                emoji = "🐦",
                homeUrl = "https://x.com/",
                logoRes = com.java.myapplication.R.drawable.site_twitter,
                hint = "粘贴推文链接 / status id（公开推文免登录；受限内容可导入 Cookie）"
            ),
            Site(
                id = "jm",
                name = "禁漫天堂",
                category = SiteCategory.MANGA,
                emoji = "🖼",
                homeUrl = "https://18comic.vip/",
                hint = "粘贴链接 / 车号 id（如 422866 或 1473668）"
            ),
            Site(
                id = "hentaipaw",
                name = "HentaiPaw",
                category = SiteCategory.MANGA,
                emoji = "📚",
                homeUrl = "https://hentaipaw.com/",
                hint = "粘贴链接 / 漫画 id（如 12345）"
            )
        )

        fun byId(id: String): Site? = ALL.firstOrNull { it.id == id }
    }
}

/** 91 站点默认域名，用户可在设置/高级选项里覆盖（域名可能变更）。 */
const val DEFAULT_91_DOMAIN = "https://91porny.com"
