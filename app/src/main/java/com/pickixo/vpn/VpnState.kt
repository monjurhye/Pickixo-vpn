package com.pickixo.vpn

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

sealed class VpnStatus {
    object Disconnected : VpnStatus()
    data class Connecting(val countryCode: String, val detail: String) : VpnStatus()
    data class Connected(val countryCode: String, val countryName: String, val serverIp: String, val since: Long) : VpnStatus()
    data class Failed(val message: String) : VpnStatus()
}

/** Process-wide VPN status, observed by the UI. Listeners are called on the main thread. */
object VpnState {
    @Volatile var current: VpnStatus = VpnStatus.Disconnected
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(VpnStatus) -> Unit>()

    fun set(status: VpnStatus) {
        current = status
        main.post { listeners.forEach { it(status) } }
    }

    fun observe(listener: (VpnStatus) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun remove(listener: (VpnStatus) -> Unit) {
        listeners.remove(listener)
    }

    val isBusy: Boolean
        get() = current is VpnStatus.Connecting || current is VpnStatus.Connected
}
