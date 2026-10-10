package com.java.myapplication.engine

/**
 * 统一输入规范化工具（所有站点引擎共用）。
 *
 * 设计目标（架构级，解决"只认数字/id、贴链接就秒失败"的通病）：
 * 用户在输入框里可能粘贴：完整链接 / 带参数的链接 / 短链 / 纯 id / 纯数字车号 /
 * 关键词。各站点引擎应先调用本工具把"输入"归一化成"关键标识"，再去请求。
 *
 * 零密钥、零副作用，纯字符串处理。
 */
object InputParser {

    /** 是否为 http/https 链接 */
    fun isUrl(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http://", true) || t.startsWith("https://", true)
    }

    /** 是否纯数字 */
    fun isDigits(s: String): Boolean = s.trim().isNotEmpty() && s.trim().all { it.isDigit() }

    /** 去掉首尾空白、去锚点(#)、保留 query（有些站点标识在 query 里） */
    fun normalizeUrl(s: String): String {
        var t = s.trim()
        val hash = t.indexOf('#')
        if (hash >= 0) t = t.substring(0, hash)
        return t
    }

    /**
     * 通用提取：按给定正则列表依次匹配，返回第一个捕获组。
     * @param patterns 每个正则至少含一个捕获组
     * @return 首个命中的捕获组，或 null
     */
    fun extractFirst(input: String, patterns: List<Regex>): String? {
        for (p in patterns) {
            val m = p.find(input) ?: continue
            if (m.groupValues.size > 1) {
                val g = m.groupValues[1]
                if (g.isNotBlank()) return g
            }
        }
        return null
    }

    /**
     * 提取路径中的某段：如 /album/1473668/xxx -> 1473668
     * @param seg 目标段的前一个关键字，如 "album"
     * @param digitsOnly 是否要求结果是纯数字
     */
    fun extractPathSegment(input: String, seg: String, digitsOnly: Boolean = true): String? {
        val re = Regex("""/[^/]*${Regex.escape(seg)}[^/]*/([^/?#]+)""", RegexOption.IGNORE_CASE)
        val g = re.find(input)?.groupValues?.get(1) ?: return null
        if (digitsOnly && !g.all { it.isDigit() }) return null
        return g
    }

    /** 从任意文本里取第一个数字串（兜底用，谨慎使用） */
    fun firstNumber(input: String): String? =
        Regex("""(\d+)""").find(input)?.groupValues?.get(1)

    /**
     * 统一入口：把用户输入解析成"关键标识"。
     * @param input 原始输入
     * @param patterns 站点自定义的链接/文本正则（至少一个捕获组），按顺序匹配
     * @param allowPureDigits 是否允许"纯数字"直接作为标识
     * @param allowBareToken 是否允许"无分隔的字母数字 token"直接作为标识
     * @return 归一化后的标识；无法识别返回 null
     */
    fun resolveId(
        input: String,
        patterns: List<Regex> = emptyList(),
        allowPureDigits: Boolean = true,
        allowBareToken: Boolean = false
    ): String? {
        val t = input.trim()
        if (t.isEmpty()) return null

        // 1. 自定义正则优先
        extractFirst(t, patterns)?.let { return it }

        // 2. 纯数字
        if (allowPureDigits && isDigits(t)) return t

        // 3. 裸 token（如 viewkey 之类的定长字母数字）
        if (allowBareToken && Regex("""^[A-Za-z0-9_-]{4,}$""").matches(t)) return t

        // 4. 链接里取最后一段非空路径作为兜底
        if (isUrl(t)) {
            val cleaned = normalizeUrl(t)
            val last = cleaned.substringAfterLast("?", cleaned)
                .trimEnd('/')
                .substringAfterLast('/')
            if (last.isNotBlank() && !last.contains(".")) return last
        }

        return null
    }

    /** 清洗文件名非法字符 */
    fun sanitizeFileName(s: String, max: Int = 80): String =
        s.replace(Regex("""[/\\:*?"<>|\n\r\t]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(max)
            .ifBlank { "untitled" }
}

/**
 * 失败原因分类：让 UI 能区分"是输入没识别 / 网络问题 / 站点改版"，
 * 而不是无脑一句"下载失败"。配合各引擎在 onFailed 里带前缀给出。
 */
object FailReason {
    const val INPUT = "输入无法识别"        // 链接/标识解析失败
    const val NETWORK = "网络连接失败（可能需要开启代理 / VPN）"      // DNS/超时/被墙
    const val HTTP = "服务器返回异常"       // 4xx/5xx
    const val PARSE = "页面解析失败（站点可能改版）"        // 站点结构变化/接口变更
    const val NEED_COOKIE = "需要登录Cookie"
    const val EMPTY = "内容为空"
    const val IO = "文件写入失败"

    /** 组装可读错误串 */
    fun msg(kind: String, detail: String): String = "[$kind] $detail"

    /** 额外建议（如网络失败时的排查提示），无则为空串 */
    fun advice(kind: String): String = when (kind) {
        NETWORK -> "（排查：确认网络可用、是否需要开代理；弱网可在「设置 → 网络」开启弱网模式）"
        PARSE -> "（排查：站点可能改版或域名变更，可在「设置」更新域名；也可能是被反爬拦截）"
        else -> ""
    }
}
