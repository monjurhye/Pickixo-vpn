package com.pickixo.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.pickixo.vpn.sstp.SstpClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PickixoVpnService : VpnService() {

    companion object {
        const val EXTRA_COUNTRY = "country"
        private const val TAG = "PickixoVpn"
        private const val CHANNEL = "vpn_status"
        private const val NOTIFICATION_ID = 7
        private const val VPN_USER = "vpn"       // VPN Gate's public credentials
        private const val VPN_PASSWORD = "vpn"
        private const val MAX_SERVERS_TO_TRY = 12
        private const val PARALLEL = 4
        private const val MAX_RECONNECTS = 3

        @Volatile var instance: PickixoVpnService? = null
            private set
    }

    @Volatile private var stopRequested = false
    @Volatile private var destroyed = false
    @Volatile private var client: SstpClient? = null
    private val attempts: MutableSet<SstpClient> = ConcurrentHashMap.newKeySet()
    @Volatile private var tunOut: FileOutputStream? = null
    @Volatile private var pendingCountry: String? = null
    private var currentCountry: String? = null
    private var worker: Thread? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        ConnLog.install()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // From the app the country is in the intent; for Always-on VPN the system starts us without it.
        val country = intent?.getStringExtra(EXTRA_COUNTRY) ?: Prefs.country(this)
        if (worker?.isAlive == true) {
            if (country != currentCountry) switchTo(country)
        } else {
            start(country)
        }
        return START_NOT_STICKY
    }

    private fun start(country: String) {
        currentCountry = country
        stopRequested = false
        VpnState.set(VpnStatus.Connecting(country, getString(R.string.status_finding_server)))
        worker = thread(name = "pickixo-vpn") { runVpn(country) }
    }

    /** Changes location: the current tunnel stops and a new one starts for [country]. */
    fun switchTo(country: String) {
        if (worker?.isAlive != true) { start(country); return }
        pendingCountry = country
        stopRequested = true
        client?.close()
        attempts.forEach { it.close() }
    }

    fun disconnect() {
        pendingCountry = null
        stopRequested = true
        client?.close()
        attempts.forEach { it.close() }
        if (worker?.isAlive != true) stopSelf()
    }

    override fun onRevoke() { // another VPN app took over, or the user turned us off in Settings
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        pendingCountry = null
        stopRequested = true
        client?.close()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------

    private fun runVpn(country: String) {
        var error: String? = null
        var tun: ParcelFileDescriptor? = null
        try {
            if (!ServerRepository.loadCached(this) || ServerRepository.isStale()) ServerRepository.refresh(this)
            if (ServerRepository.servers.isEmpty()) throw IOException(getString(R.string.error_no_list))
            ConnLog.add("Server list: ${ServerRepository.servers.size} servers, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

            var reconnects = 0
            while (!stopRequested) {
                val (c, result, server) = connectAny(country)
                    ?: throw IOException(getString(R.string.error_no_server))
                client = c

                // Establishing the new interface before closing the old one keeps traffic inside the
                // VPN during a reconnect instead of leaking it to the normal network.
                val newTun = establish(result, server)
                val old = tun
                tun = newTun
                tunOut = FileOutputStream(newTun.fileDescriptor)
                try { old?.close() } catch (ignored: IOException) {}

                VpnState.set(VpnStatus.Connected(server.countryCode, server.countryName, server.ip, System.currentTimeMillis()))
                showNotification(server.countryName)
                if (stopRequested) break
                pump(c, newTun)
                c.close()
                client = null
                if (stopRequested) break

                if (++reconnects > MAX_RECONNECTS) throw IOException(getString(R.string.error_lost))
                Log.i(TAG, "Tunnel dropped (${c.closeReason()}), reconnecting")
                VpnState.set(VpnStatus.Connecting(country, getString(R.string.status_reconnecting)))
                Thread.sleep(1500)
            }
        } catch (e: InterruptedException) {
            // shutting down
        } catch (e: Exception) {
            if (!stopRequested) error = e.message ?: e.javaClass.simpleName
            ConnLog.add("VPN stopped: ${e.message}")
            Log.w(TAG, "VPN stopped", e)
        } finally {
            client?.close()
            client = null
            tunOut = null
            try { tun?.close() } catch (ignored: IOException) {}
            val next = pendingCountry
            pendingCountry = null
            if (next != null && !destroyed) {
                main.post { if (!destroyed) start(next) } // location switch
            } else {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                VpnState.set(if (error != null) VpnStatus.Failed(error) else VpnStatus.Disconnected)
                stopSelf()
            }
        }
    }

    /** Tries the best servers [PARALLEL] at a time; the first one that finishes the handshake wins. */
    private fun connectAny(country: String): Triple<SstpClient, SstpClient.Result, Server>? {
        val candidates = ServerRepository.candidates(country).take(MAX_SERVERS_TO_TRY)
        if (candidates.isEmpty()) return null
        ConnLog.add("Connecting to ${candidates.size} candidate servers for $country")
        val winner = AtomicReference<Triple<SstpClient, SstpClient.Result, Server>?>(null)
        val tried = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        val done = CountDownLatch(candidates.size)
        val listener = object : SstpClient.Listener {
            override fun onIpPacket(buf: ByteArray, off: Int, len: Int) {
                try { tunOut?.write(buf, off, len) } catch (ignored: IOException) {}
            }
            override fun onClosed(reason: String?) {}
        }
        for (server in candidates) pool.execute {
            try {
                val n = tried.incrementAndGet()
                if (!stopRequested && winner.get() == null) {
                    VpnState.set(VpnStatus.Connecting(country,
                        getString(R.string.status_trying, server.countryName, n, candidates.size)))
                }
                for (port in server.ports) {
                    if (stopRequested || winner.get() != null) break
                    val c = SstpClient(server.ip, port, VPN_USER, VPN_PASSWORD)
                    attempts.add(c) // lets disconnect() abort attempts in progress
                    try {
                        // Our own app is excluded from the VPN, so protect() is only a safeguard; a false
                        // result must not abort the attempt.
                        val r = c.connect({ sock -> if (!protect(sock)) ConnLog.add("protect() returned false"); true },
                            listener, 5000, 20000)
                        if (winner.compareAndSet(null, Triple(c, r, server))) {
                            ConnLog.add("Connected to ${server.ip}:$port (${server.countryName}), auth ${r.auth}")
                            break
                        }
                        c.close()
                    } catch (e: Exception) {
                        ConnLog.add("${server.ip}:$port ${server.countryName} failed: ${e.message}")
                        c.close()
                    } finally {
                        attempts.remove(c)
                    }
                }
            } finally {
                done.countDown()
            }
        }
        pool.shutdown()
        // Wait until someone wins, everything has failed, or we are told to stop.
        while (winner.get() == null && !stopRequested && !done.await(200, TimeUnit.MILLISECONDS)) { /* wait */ }
        val w = winner.get()
        if (w == null || stopRequested) {
            w?.first?.close()
            attempts.forEach { it.close() }
            return null
        }
        attempts.forEach { if (it !== w.first) it.close() }
        return w
    }

    private fun establish(r: SstpClient.Result, server: Server): ParcelFileDescriptor {
        val b = Builder()
            .setSession("Pickixo VPN · ${server.countryName}")
            .setMtu(r.mtu)
            .addAddress(InetAddress.getByAddress(r.address), 32)
            .addRoute("0.0.0.0", 0)
            // Capture IPv6 as well and drop it (the tunnel is IPv4-only) so it cannot leak.
            .addAddress("fd70:6963:6b78::1", 128)
            .addRoute("::", 0)
            .setConfigureIntent(mainIntent())
        val dns = listOfNotNull(r.dns1, r.dns2).map { InetAddress.getByAddress(it) }
        if (dns.isEmpty()) b.addDnsServer("1.1.1.1").addDnsServer("8.8.8.8") else dns.forEach { b.addDnsServer(it) }
        // Our own traffic (ads, server list) uses the normal network, so ads are never routed
        // through another country - Google Play forbids manipulating ad traffic via VPN.
        try { b.addDisallowedApplication(packageName) } catch (ignored: PackageManager.NameNotFoundException) {}
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        return b.establish() ?: throw IOException(getString(R.string.error_permission))
    }

    /** Moves packets from the TUN interface into the tunnel until it dies or we are stopped. */
    private fun pump(c: SstpClient, tun: ParcelFileDescriptor) {
        val input = FileInputStream(tun.fileDescriptor)
        val packet = ByteArray(32767)
        val fds = arrayOf(StructPollfd().apply { fd = tun.fileDescriptor; events = OsConstants.POLLIN.toShort() })
        var lastTick = 0L
        while (!stopRequested && c.isAlive) {
            fds[0].revents = 0
            val ready = try { Os.poll(fds, 1000) } catch (e: ErrnoException) { 0 }
            if (ready > 0) {
                val bad = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
                if (fds[0].revents.toInt() and bad != 0) break // interface closed
                // The TUN fd is non-blocking, so a read can fail with EAGAIN; just poll again.
                val len = try { input.read(packet) } catch (e: IOException) { 0 }
                if (len > 0 && (packet[0].toInt() ushr 4) == 4) {
                    try { c.sendIp(packet, 0, len) } catch (e: IOException) { break }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastTick >= 1000) {
                lastTick = now
                if (!c.tick()) break
            }
        }
    }

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)

    private fun showNotification(country: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(this, 1, Intent(this, DisconnectReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, country))
            .setContentIntent(mainIntent())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
            .build()
        try { nm.notify(NOTIFICATION_ID, n) } catch (ignored: SecurityException) {}
    }
}
