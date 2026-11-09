package com.haokai.addemo.speech

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

data class NetworkProbeItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
)

data class NetworkProbeReport(
    val running: Boolean = false,
    val items: List<NetworkProbeItem> = emptyList(),
    val summary: String = "",
)

/**
 * 检测设备网络及火山引擎语音相关域名连通性。
 */
object NetworkProbe {

    private val executor = Executors.newSingleThreadExecutor()

    private val targets = listOf(
        "openspeech.bytedance.com" to 443,
        "www.volcengine.com" to 443,
        "www.baidu.com" to 443,
    )

    fun runAsync(context: Context, onUpdate: (NetworkProbeReport) -> Unit) {
        onUpdate(NetworkProbeReport(running = true, summary = "检测中…"))
        executor.execute {
            val items = mutableListOf<NetworkProbeItem>()
            items += checkLocalLink(context)
            for ((host, port) in targets) {
                items += checkDns(host)
                items += checkTcp(host, port)
            }
            val allOk = items.all { it.ok }
            val summary = if (allOk) {
                "网络正常，可访问火山语音相关域名"
            } else {
                "存在失败项：请检查 Wi‑Fi/以太网、DNS 或防火墙"
            }
            onUpdate(
                NetworkProbeReport(
                    running = false,
                    items = items,
                    summary = summary,
                ),
            )
        }
    }

    private fun checkLocalLink(context: Context): NetworkProbeItem {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network == null) {
                return NetworkProbeItem("本机链路", false, "无活动网络")
            }
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null) {
                return NetworkProbeItem("本机链路", false, "无法读取网络能力")
            }
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = if (Build.VERSION.SDK_INT >= 23) {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                true
            }
            val transports = buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WiFi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("以太网")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("蜂窝")
                if (Build.VERSION.SDK_INT >= 31 &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                ) {
                    add("USB")
                }
            }.joinToString("/").ifEmpty { "未知" }
            val ok = hasInternet
            NetworkProbeItem(
                name = "本机链路",
                ok = ok,
                detail = "$transports · internet=$hasInternet · validated=$validated",
            )
        } catch (e: Exception) {
            NetworkProbeItem("本机链路", false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun checkDns(host: String): NetworkProbeItem {
        val t0 = System.currentTimeMillis()
        return try {
            val addrs = InetAddress.getAllByName(host)
            val ms = System.currentTimeMillis() - t0
            if (addrs.isEmpty()) {
                NetworkProbeItem("DNS $host", false, "无解析结果 · ${ms}ms")
            } else {
                val ips = addrs.take(3).joinToString(", ") { it.hostAddress ?: "?" }
                NetworkProbeItem("DNS $host", true, "$ips · ${ms}ms")
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            NetworkProbeItem("DNS $host", false, "${e.message} · ${ms}ms")
        }
    }

    private fun checkTcp(host: String, port: Int): NetworkProbeItem {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.soTimeout = 5_000
                socket.connect(InetSocketAddress(host, port), 5_000)
            }
            val ms = System.currentTimeMillis() - t0
            NetworkProbeItem("TCP $host:$port", true, "连通 · ${ms}ms")
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            NetworkProbeItem("TCP $host:$port", false, "${e.message} · ${ms}ms")
        }
    }
}
