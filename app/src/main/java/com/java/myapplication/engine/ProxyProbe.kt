package com.java.myapplication.engine

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * 本地代理自动探测。
 *
 * 背景：真机实测确认 App 的流量默认**不经过**用户开启的"全局代理"
 * （Wall 里直连 hentaipaw 超时、Http 直连 403），而 Termux 里脚本经
 * 127.0.0.1:10809 却能正常 200。因此需要 App 主动发现并使用本机 HTTP 代理。
 *
 * 探测策略：
 *  1. 依次对这些常见本地代理端口做 TCP 连通性测试（快，200ms 超时）：
 *       10809(v2ray HTTP) / 7890(Clash) / 10808(v2ray SOCKS, 顺带试) / 7891 / 8080 / 1080
 *  2. 对第一个 TCP 通的端口，再用**真实 HTTP 请求**（经该代理访问一个探测目标）
 *     验证"代理真能出网"；验通即选定。
 *
 * 安全：只连 127.0.0.1，不硬编码任何外部节点/密钥。
 */
object ProxyProbe {

    /** 常见本地 HTTP 代理端口（按可能性排序） */
    private val COMMON_PORTS = intArrayOf(10809, 7890, 10808, 7891, 8080, 1080)

    /** 探测结果 */
    data class Result(val host: String, val port: Int, val ok: Boolean)

    /** 本机代理主机（固定 loopback，与 v2ray/Clash 同机） */
    private const val HOST = "127.0.0.1"

    /**
     * 探测并返回第一个"可用"的代理端口；找不到返回 null。
     * @param testUrl 用于验证代理确实能出网的 URL（默认为一个轻量站点）
     * @param timeoutMs TCP 连通超时
     */
    fun detect(testUrl: String = "https://hentaipaw.com/", timeoutMs: Int = 400): Result? {
        DiagLog.error("PROXY", "开始探测本地代理端口: ${COMMON_PORTS.joinToString()}")
        for (port in COMMON_PORTS) {
            if (!tcpOpen(HOST, port, timeoutMs)) {
                DiagLog.error("PROXY", "端口 $port TCP 不通")
                continue
            }
            DiagLog.error("PROXY", "端口 $port TCP 通，验证能否出网…")
            val ok = verifyProxy(HOST, port, testUrl)
            DiagLog.error("PROXY", "端口 $port 出网验证: ${if (ok) "成功✅" else "失败❌"}")
            if (ok) return Result(HOST, port, true)
        }
        DiagLog.error("PROXY", "未找到可用本地代理")
        return null
    }

    /** TCP 三次握手测试 */
    private fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 经该 HTTP 代理发一次真实请求，看是否拿到 < 400 的响应（能出网即可） */
    private fun verifyProxy(host: String, port: Int, url: String): Boolean {
        return try {
            val p = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
            val conn = URL(url).openConnection(p) as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", Http.UA)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            DiagLog.error("PROXY", "经 $host:$port 请求 $url 异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}