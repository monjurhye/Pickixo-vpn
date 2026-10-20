package com.pickixo.vpn

import android.util.Log
import com.pickixo.vpn.sstp.SstpClient
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Small in-memory connection log the user can view and copy from the menu (no personal data, no browsing). */
object ConnLog {
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun install() {
        if (SstpClient.debug == null) SstpClient.debug = java.util.function.Consumer { add(it) }
    }

    @Synchronized
    fun add(line: String) {
        Log.d("PickixoVpn", line)
        lines.addLast(fmt.format(Date()) + " " + line)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")
}
