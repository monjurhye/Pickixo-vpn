package com.pickixo.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * Prominent disclosure required by Google Play's VpnService policy. Shown on first launch, before
 * the VPN can be used and before (and separately from) the AdMob consent form.
 */
class ConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)
        applySystemBarInsets(findViewById(R.id.root))

        findViewById<TextView>(R.id.privacyLink).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL))) } catch (ignored: Exception) {}
        }
        findViewById<Button>(R.id.agree).setOnClickListener {
            Prefs.setConsentAccepted(this, true)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.disagree).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.consent_declined_title)
                .setMessage(R.string.consent_declined_text)
                .setPositiveButton(R.string.consent_review) { d, _ -> d.dismiss() }
                .setNegativeButton(R.string.exit) { _, _ -> finishAffinity() }
                .show()
        }
    }
}
