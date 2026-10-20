package com.pickixo.vpn

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AdMob with Google's consent form (UMP). Ads load only after consent has been gathered.
 * Interstitials are shown at most every 3 minutes, and only right after the user taps Connect.
 */
object Ads {
    private const val INTERSTITIAL_GAP_MS = 3 * 60 * 1000L
    private val started = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val waiting = mutableListOf<() -> Unit>()
    private var ready = false
    private var interstitial: InterstitialAd? = null
    private var loading = false

    fun gatherConsent(activity: Activity) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                if (info.canRequestAds()) start(activity.applicationContext)
            }
        }, {
            if (info.canRequestAds()) start(activity.applicationContext)
        })
        // Consent from a previous session is enough to start right away.
        if (info.canRequestAds()) start(activity.applicationContext)
    }

    fun privacyOptionsRequired(ctx: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(ctx).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {}
    }

    private fun start(app: Context) {
        if (!started.compareAndSet(false, true)) return
        thread(name = "ads-init") {
            MobileAds.initialize(app) {
                main.post {
                    ready = true
                    loadInterstitial(app)
                    waiting.toList().forEach { it() }
                    waiting.clear()
                }
            }
        }
    }

    private fun whenReady(block: () -> Unit) {
        if (ready) block() else waiting.add(block)
    }

    fun showBanner(activity: Activity, container: FrameLayout) = whenReady {
        if (activity.isFinishing || activity.isDestroyed) return@whenReady
        val density = activity.resources.displayMetrics.density
        val widthPx = if (container.width > 0) container.width else activity.resources.displayMetrics.widthPixels
        val ad = AdView(activity)
        ad.adUnitId = BuildConfig.BANNER_ID
        ad.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, (widthPx / density).toInt()))
        container.removeAllViews()
        container.addView(ad)
        ad.loadAd(AdRequest.Builder().build())
    }

    private fun loadInterstitial(ctx: Context) {
        if (loading || interstitial != null) return
        loading = true
        InterstitialAd.load(ctx, BuildConfig.INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad; loading = false }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null; loading = false }
            })
    }

    fun maybeShowInterstitial(activity: Activity) {
        val ad = interstitial
        val now = System.currentTimeMillis()
        if (ad == null) { if (ready) loadInterstitial(activity.applicationContext); return }
        if (now - Prefs.lastInterstitial(activity) < INTERSTITIAL_GAP_MS) return
        interstitial = null
        Prefs.setLastInterstitial(activity, now)
        val app = activity.applicationContext
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = loadInterstitial(app)
            override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) = loadInterstitial(app)
        }
        ad.show(activity)
    }
}
