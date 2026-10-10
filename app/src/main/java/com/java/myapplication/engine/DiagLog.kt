package com.java.myapplication.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志（真机排障专用）。
 *
 * 目的：AI 无法真机联网，唯一能拿到"失败真相"的途径是用户机器上跑出来的日志。
 * 因此本模块记录每次网络请求/解析的关键证据，供：
 *   - 出口 A：App 内「诊断日志」页直接查看/复制（[snapshot]）；
 *   - 出口 B：落盘到 <outDir>/../logs/ 或 Download/multi-downloader/logs/ 供打包发送。
 *
 * 记录内容（**安全红线**）：
 *   - 只记 URL、HTTP 状态码、异常类型、异常 message、响应体前 N 字节、是否带 cookie（布尔）；
 *   - **绝不记录 cookie / token 的实际值**；URL 里的敏感 query（如 cookie=）做打码。
 *
 * 线程安全：所有写入口加锁；内存环形缓冲上限 [MAX_LINES] 行，超出丢最旧。
 */
object DiagLog {

    /** 详细模式：开启后记录每次请求（含成功）；关闭时只记失败与关键节点。 */
    @Volatile
    var verbose: Boolean = false

    /** 单条记录截断长度（响应体片段等） */
    private const val SNIPPET_MAX = 400

    /** 内存环形缓冲上限 */
    private const val MAX_LINES = 2000

    private val lock = Any()
    private val buf = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 落盘文件（首次 setLogFile 时生效），失败时静默不影响下载 */
    @Volatile
    private var logFile: File? = null

    /** 设置落盘目标（一般由 DownloadService 在任务开始时按输出目录设定） */
    fun setLogFile(f: File?) {
        logFile = f?.also { runCatching { it.parentFile?.mkdirs() } }
    }

    /** 记录一条普通信息（关键节点：开始/解析到 N 图/落盘路径等） */
    fun info(tag: String, msg: String) = append("I", tag, msg)

    /** 记录一条失败/异常（总是记录，不受 verbose 影响） */
    fun error(tag: String, msg: String) = append("E", tag, msg)

    /** 记录一条请求明细（仅在 verbose 开启时记录成功请求，失败请求由 error 记） */
    fun req(tag: String, msg: String) {
        if (verbose) append("R", tag, msg)
    }

    private fun append(level: String, tag: String, msg: String) {
        val line = "${timeFmt.format(Date())} [$level] [$tag] ${clip(msg)}"
        synchronized(lock) {
            buf.addLast(line)
            while (buf.size > MAX_LINES) buf.removeFirst()
        }
        // 落盘：失败静默（下载本身优先）
        logFile?.let { f ->
            runCatching {
                synchronized(lock) { f.appendText(line + "\n") }
            }
        }
    }

    private fun clip(s: String): String {
        val one = s.replace("\n", "\\n").replace("\r", "")
        return if (one.length > SNIPPET_MAX * 4) one.substring(0, SNIPPET_MAX * 4) + "…(截断)" else one
    }

    /** 读取当前内存缓冲的快照（供 UI 展示 / 复制导出） */
    fun snapshot(): String = synchronized(lock) { buf.joinToString("\n") }

    /** 清空内存缓冲（不动已落盘文件） */
    fun clear() = synchronized(lock) { buf.clear() }

    /**
     * 对 URL 做脱敏：把明显含凭证的 query 值打码，避免日志泄露用户信息。
     * 例：?cookie=xxx&token=yyy -> ?cookie=***&token=***
     */
    fun maskUrl(url: String): String {
        return Regex("""(?i)(cookie|token|auth|passwd|password|session|key)=([^&\s]+)""")
            .replace(url) { m -> "${m.groupValues[1]}=***" }
            .take(600)
    }

    /** 取二进制响应体的可读片段（前 [SNIPPET_MAX] 字节），用于判断是否 CF 拦截页 */
    fun snippet(bytes: ByteArray, len: Int = SNIPPET_MAX): String {
        val n = minOf(bytes.size, len)
        return String(bytes, 0, n, Charsets.UTF_8).replace("\u0000", "")
    }
}
