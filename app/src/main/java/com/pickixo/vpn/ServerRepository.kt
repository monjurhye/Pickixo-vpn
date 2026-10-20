package com.pickixo.vpn

import android.content.Context
import com.pickixo.vpn.sstp.VpnGateParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

typealias Server = VpnGateParser.Server

data class Country(val code: String, val name: String, val servers: List<Server>) {
    val flag: String get() = flagEmoji(code)
    val bestPing: Int get() = servers.filter { it.ping > 0 }.minOfOrNull { it.ping } ?: 0
}

/** VPN Gate server list: downloaded, cached on disk, grouped by country. */
object ServerRepository {
    const val AUTO = "AUTO"
    private const val API = "http://www.vpngate.net/api/iphone/"
    private const val CACHE = "vpngate_servers.csv"
    private const val HTML_URL = "https://www.vpngate.net/en/"
    private const val HTML_CACHE = "vpngate_list.html"
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    @Volatile var servers: List<Server> = emptyList()
        private set
    @Volatile var updatedAt: Long = 0
        private set

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, CACHE)

    /** Loads the last downloaded list from disk. Returns true if any servers were found. */
    @Synchronized
    fun loadCached(ctx: Context): Boolean {
        if (servers.isNotEmpty()) return true
        val f = cacheFile(ctx)
        if (!f.exists()) return false
        return try {
            val html = File(ctx.filesDir, HTML_CACHE).takeIf { it.exists() }?.readText()
            val list = withSstp(VpnGateParser.parse(f.readText()), html)
            if (list.isNotEmpty()) {
                servers = list
                updatedAt = f.lastModified()
            }
            list.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun isStale(): Boolean = servers.isEmpty() || System.currentTimeMillis() - updatedAt > MAX_AGE_MS

    private fun withSstp(list: List<Server>, html: String?): List<Server> =
        if (html.isNullOrEmpty()) list
        else VpnGateParser.applySstpPorts(list, VpnGateParser.sstpPortsFromHtml(html)).ifEmpty { list }

    private fun download(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "PickixoVPN/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads a fresh list. Blocking: call from a background thread. */
    fun refresh(ctx: Context): Boolean {
        val text = download(API) ?: return false
        val parsed = VpnGateParser.parse(text)
        if (parsed.isEmpty()) return false
        // The HTML table says which servers really offer SSTP, and on which port. Optional.
        val html = download(HTML_URL)
        val list = withSstp(parsed, html)
        synchronized(this) {
            cacheFile(ctx).writeText(text)
            if (html != null) File(ctx.filesDir, HTML_CACHE).writeText(html)
            servers = list
            updatedAt = System.currentTimeMillis()
        }
        ConnLog.add("Server list: ${parsed.size} servers, ${list.size} with SSTP" + if (html == null) " (HTML list unavailable)" else "")
        return true
    }

    fun countries(): List<Country> =
        servers.groupBy { it.countryCode }
            .map { (code, list) -> Country(code, list.first().countryName, list.sortedByDescending { it.score }) }
            .sortedWith(compareByDescending<Country> { it.servers.size }.thenBy { it.name })

    fun country(code: String): Country? = countries().firstOrNull { it.code == code }

    /** Servers to try for a location, best first. */
    fun candidates(code: String): List<Server> {
        val pool = if (code == AUTO) servers else servers.filter { it.countryCode == code }
        return pool.sortedByDescending { it.score }
    }
}

fun flagEmoji(code: String): String {
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐" // globe
    val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
    val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
    return String(first) + String(second)
}
