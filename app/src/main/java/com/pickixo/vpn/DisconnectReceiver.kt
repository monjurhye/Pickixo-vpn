package com.pickixo.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Disconnect" button in the notification. */
class DisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PickixoVpnService.instance?.disconnect()
    }
}
