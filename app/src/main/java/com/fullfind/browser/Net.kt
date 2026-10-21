package com.fullfind.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Wear OS normally routes app traffic through the phone's Bluetooth proxy, which can be slow or
 * unavailable. We request a high-bandwidth (Wi-Fi / cellular) network and use it when granted,
 * falling back to the default network otherwise.
 */
object Net {
    @Volatile var fast: Network? = null; private set
    @Volatile var status: String = "default network"; private set
    private var cb: ConnectivityManager.NetworkCallback? = null

    fun start(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        if (cb != null) return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { fast = n; status = "Wi-Fi/cellular"; try { cm.bindProcessToNetwork(n) } catch (_: Exception) {} }
            override fun onLost(n: Network) { if (fast == n) { fast = null; status = "default network"; try { cm.bindProcessToNetwork(null) } catch (_: Exception) {} } }
            override fun onUnavailable() { status = "default network (Wi-Fi/cellular unavailable)" }
        }
        try { cm.requestNetwork(req, c); cb = c } catch (e: Exception) { status = "default network (${e.javaClass.simpleName})" }
    }

    fun stop(ctx: Context) {
        val c = cb ?: return
        try { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(c) } catch (_: Exception) {}
        cb = null; fast = null
    }

    fun describeDefault(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "no ConnectivityManager"
        val n = cm.activeNetwork ?: return "no active network"
        val caps = cm.getNetworkCapabilities(n) ?: return "active network, no capabilities"
        val t = listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn").filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }
        return "active=$t validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    /** Open a connection, preferring the fast network; if that fails to connect, retry on default. */
    fun open(u: URL, setup: (HttpURLConnection) -> Unit): Pair<HttpURLConnection, String> {
        val f = fast
        if (f != null) {
            try {
                val c = f.openConnection(u) as HttpURLConnection; setup(c); c.responseCode
                return c to "Wi-Fi/cellular"
            } catch (_: Exception) { /* fall through to default */ }
        }
        val c = u.openConnection() as HttpURLConnection; setup(c); c.responseCode
        return c to "default"
    }

    fun body(c: HttpURLConnection): InputStream? {
        val s = (if (c.responseCode >= 400) c.errorStream else c.inputStream) ?: return null
        return when (c.contentEncoding?.lowercase()) {
            "gzip" -> GZIPInputStream(s)
            "deflate" -> InflaterInputStream(s)
            else -> s
        }
    }
}
