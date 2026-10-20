package com.pickixo.vpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var detail: TextView
    private lateinit var button: TextView
    private lateinit var ring: View
    private lateinit var flag: TextView
    private lateinit var countryName: TextView
    private lateinit var countryInfo: TextView
    private lateinit var adContainer: FrameLayout

    private val main = Handler(Looper.getMainLooper())
    private var lastStatus: VpnStatus = VpnStatus.Disconnected
    private var resumed = false
    private var waitingForInterstitial = false

    private val statusListener: (VpnStatus) -> Unit = { render(it) }
    private val ticker = object : Runnable {
        override fun run() {
            val s = VpnState.current
            if (s is VpnStatus.Connected) {
                val secs = (System.currentTimeMillis() - s.since) / 1000
                timer.text = String.format("%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.consentAccepted(this)) {
            startActivity(Intent(this, ConsentActivity::class.java))
            finish()
            return
        }
        ConnLog.install()
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.root))
        status = findViewById(R.id.status)
        timer = findViewById(R.id.timer)
        detail = findViewById(R.id.detail)
        button = findViewById(R.id.connectButton)
        ring = findViewById(R.id.ring)
        flag = findViewById(R.id.flag)
        countryName = findViewById(R.id.countryName)
        countryInfo = findViewById(R.id.countryInfo)
        adContainer = findViewById(R.id.adContainer)

        button.setOnClickListener { onConnectTapped() }
        findViewById<View>(R.id.locationCard).setOnClickListener { showCountryPicker() }
        findViewById<ImageButton>(R.id.menu).setOnClickListener { showMenu(it) }

        Ads.gatherConsent(this)
        adContainer.post { Ads.showBanner(this, adContainer) }
        loadServers(force = false)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        VpnState.observe(statusListener)
        main.post(ticker)
        renderLocation()
    }

    override fun onPause() {
        resumed = false
        VpnState.remove(statusListener)
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        if (::adContainer.isInitialized) (adContainer.getChildAt(0) as? com.google.android.gms.ads.AdView)?.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------

    private fun loadServers(force: Boolean) {
        if (force) countryInfo.text = getString(R.string.loading_servers)
        thread {
            ServerRepository.loadCached(this)
            runOnUiThread { renderLocation() }
            if (force || ServerRepository.isStale()) {
                val ok = ServerRepository.refresh(this)
                runOnUiThread {
                    renderLocation()
                    if (!ok && ServerRepository.servers.isEmpty()) {
                        Toast.makeText(this, R.string.error_no_list, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun onConnectTapped() {
        when (VpnState.current) {
            is VpnStatus.Connecting, is VpnStatus.Connected -> PickixoVpnService.instance?.disconnect()
                ?: VpnState.set(VpnStatus.Disconnected)
            else -> startVpn()
        }
    }

    private fun startVpn() {
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Prefs.setAskedNotifications(this)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return // continue in onRequestPermissionsResult
        }
        val consent = VpnService.prepare(this) // system "Connection request" dialog, first time only
        if (consent != null) {
            try { startActivityForResult(consent, REQ_VPN) } catch (e: Exception) {
                Toast.makeText(this, R.string.error_permission, Toast.LENGTH_LONG).show()
            }
        } else {
            launchService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) startVpn()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) launchService()
            else Toast.makeText(this, R.string.error_permission, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchService() {
        waitingForInterstitial = true
        startService(Intent(this, PickixoVpnService::class.java)
            .putExtra(PickixoVpnService.EXTRA_COUNTRY, Prefs.country(this)))
    }

    // ---------------------------------------------------------------------------------------

    private fun render(s: VpnStatus) {
        val prev = lastStatus
        lastStatus = s
        when (s) {
            is VpnStatus.Disconnected -> {
                status.setText(R.string.status_disconnected)
                timer.text = "00:00:00"
                detail.setText(R.string.tap_to_connect)
                button.setText(R.string.connect)
                button.setBackgroundResource(R.drawable.btn_off)
                ring.setBackgroundResource(R.drawable.ring_off)
            }
            is VpnStatus.Connecting -> {
                status.setText(R.string.status_connecting)
                detail.text = s.detail
                button.setText(R.string.cancel)
                button.setBackgroundResource(R.drawable.btn_connecting)
                ring.setBackgroundResource(R.drawable.ring_connecting)
            }
            is VpnStatus.Connected -> {
                status.text = getString(R.string.status_connected, s.countryName)
                detail.text = getString(R.string.server_ip, s.serverIp)
                button.setText(R.string.disconnect)
                button.setBackgroundResource(R.drawable.btn_on)
                ring.setBackgroundResource(R.drawable.ring_on)
                if (prev !is VpnStatus.Connected && waitingForInterstitial && resumed) {
                    waitingForInterstitial = false
                    Ads.maybeShowInterstitial(this)
                }
            }
            is VpnStatus.Failed -> {
                status.setText(R.string.status_failed)
                timer.text = "00:00:00"
                detail.text = s.message
                button.setText(R.string.retry)
                button.setBackgroundResource(R.drawable.btn_off)
                ring.setBackgroundResource(R.drawable.ring_off)
                waitingForInterstitial = false
            }
        }
    }

    private fun renderLocation() {
        val code = Prefs.country(this)
        val total = ServerRepository.servers.size
        if (code == ServerRepository.AUTO) {
            flag.text = "⚡"
            countryName.setText(R.string.fastest_server)
            countryInfo.text = if (total == 0) getString(R.string.loading_servers)
                else getString(R.string.auto_info, ServerRepository.countries().size, total)
        } else {
            val c = ServerRepository.country(code)
            flag.text = flagEmoji(code)
            countryName.text = c?.name ?: code
            countryInfo.text = when {
                total == 0 -> getString(R.string.loading_servers)
                c == null -> getString(R.string.no_servers_now)
                else -> getString(R.string.country_info, c.servers.size, c.bestPing)
            }
        }
    }

    private fun showCountryPicker() {
        val countries = ServerRepository.countries()
        if (countries.isEmpty()) { loadServers(force = true); return }
        val items: List<Country?> = listOf<Country?>(null) + countries // null = automatic
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val v = convert ?: layoutInflater.inflate(R.layout.item_country, parent, false)
                val c = items[i]
                v.findViewById<TextView>(R.id.itemFlag).text = c?.flag ?: "⚡"
                v.findViewById<TextView>(R.id.itemName).text = c?.name ?: getString(R.string.fastest_server)
                v.findViewById<TextView>(R.id.itemInfo).text =
                    if (c == null) getString(R.string.auto_info, countries.size, ServerRepository.servers.size)
                    else getString(R.string.country_info, c.servers.size, c.bestPing)
                val selected = (c?.code ?: ServerRepository.AUTO) == Prefs.country(this@MainActivity)
                v.findViewById<View>(R.id.itemCheck).visibility = if (selected) View.VISIBLE else View.INVISIBLE
                return v
            }
        }
        AlertDialog.Builder(this, R.style.Theme_Pickixo_Dialog)
            .setTitle(R.string.choose_location)
            .setAdapter(adapter) { _, which ->
                val code = items[which]?.code ?: ServerRepository.AUTO
                val changed = code != Prefs.country(this)
                Prefs.setCountry(this, code)
                renderLocation()
                if (changed && VpnState.isBusy) PickixoVpnService.instance?.switchTo(code)
            }
            .setNeutralButton(R.string.refresh) { _, _ -> loadServers(force = true) }
            .show()
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.refresh_servers)
        m.menu.add(0, 2, 0, R.string.privacy_policy)
        if (Ads.privacyOptionsRequired(this)) m.menu.add(0, 3, 0, R.string.ad_privacy)
        m.menu.add(0, 5, 0, R.string.connection_log)
        m.menu.add(0, 4, 0, R.string.about)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> loadServers(force = true)
                2 -> openUrl(BuildConfig.PRIVACY_URL)
                3 -> Ads.showPrivacyOptions(this)
                5 -> showConnectionLog()
                4 -> AlertDialog.Builder(this, R.style.Theme_Pickixo_Dialog)
                    .setTitle(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME)
                    .setMessage(R.string.about_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.vpngate_site) { _, _ -> openUrl("https://www.vpngate.net/") }
                    .show()
            }
            true
        }
        m.show()
    }

    private fun showConnectionLog() {
        val text = ConnLog.text()
        val tv = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this, R.style.Theme_Pickixo_Dialog)
            .setTitle(R.string.connection_log)
            .setView(scroll)
            .setPositiveButton(R.string.copy) { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Pickixo VPN log", text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (ignored: Exception) {}
    }

    private companion object {
        const val REQ_VPN = 1
        const val REQ_NOTIFY = 2
    }
}
