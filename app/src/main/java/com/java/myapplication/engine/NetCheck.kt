package com.java.myapplication.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI

/**
 * 网络与代理自检（零第三方依赖）。
 *
 * 目标：
 *  1. 告知用户当前是否有可用网络、是否为按流量计费（弱网提示）；
 *  2. 尽力探测「系统代理 / VPN」是否存在，从而在「连接失败」时给出更准确的提示
 *     —— 很多站点（推特、PornHub、禁漫天堂等）需要用户自备代理；
 *  3. 提供一个"网络连通性自检"，供设置在点「一键检测」时调用。
 *
 * 注意：App 不内置、不修改、不绕过任何代理。这里只做「读取与提示」。
 */
object NetCheck {

    data class Result(
        val online: Boolean,
        val meter: Boolean,          // 按流量计费（可能弱网）
        val vpnActive: Boolean,      // 系统 VPN 处于活动状态
        val systemProxy: String?,    // 形如 "host:port"，无则为 null
        val summary: String          // 一句人类可读的结论
    )

    /** 读取当前网络 / 代理状态（不发网络请求，纯本地读取，安全快速） */
    fun inspect(ctx: Context): Result {
        var online = false
        var meter = false
        var vpn = false
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm?.getNetworkCapabilities(net) else null
            if (caps != null) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                meter = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } catch (_: Exception) {
        }

        val proxy = systemProxy()

        val summary = buildString {
            append(if (online) "网络已连接" else "⚠️ 未检测到互联网连接")
            if (vpn) append("；检测到系统 VPN 已开启")
            if (proxy != null) append("；系统代理 $proxy")
            if (!vpn && proxy == null) append("；未检测到 VPN / 系统代理（部分站点可能需要自行开启）")
            if (meter) append("；当前为按流量计费网络")
        }
        return Result(online, meter, vpn, proxy, summary)
    }

    /** 通过 ProxySelector 尽力探测系统代理（VPN 全局代理通常此处为空，故需结合 TRANSPORT_VPN 判断） */
    fun systemProxy(): String? {
        return try {
            // 优先读 Java 系统属性（部分 ROM / 代理 App 会设置）
            val host = System.getProperty("http.proxyHost")
            val port = System.getProperty("http.proxyPort")
            if (!host.isNullOrBlank()) {
                "${host}:${port ?: "80"}"
            } else {
                val ps = ProxySelector.getDefault() ?: return null
                val list = ps.select(URI("https://example.com"))
                val p = list.firstOrNull { it.type() == Proxy.Type.HTTP }
                val addr = p?.address() as? InetSocketAddress
                if (addr != null && addr.hostString.isNotBlank()) "${addr.hostString}:${addr.port}" else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 连通性自检：对给定 host:port 做一次 TCP 连接测试（默认超时 5s）。
     * 仅用于判断「本机能否建立到目标站点的 TCP 连接」，不做 TLS / 不下发任何用户数据。
     */
    fun canConnect(host: String, port: Int = 443, timeoutMs: Int = 5000): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 从 URL 里取 host（容错） */
    fun hostOf(url: String): String? = try {
        URI(url.trim()).host
    } catch (_: Exception) {
        null
    }
}
