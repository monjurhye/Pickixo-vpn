package com.pickixo.vpn

import android.content.Context

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("pickixo", Context.MODE_PRIVATE)

    fun consentAccepted(ctx: Context) = p(ctx).getBoolean("disclosure_accepted_v1", false)
    fun setConsentAccepted(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("disclosure_accepted_v1", value).apply()

    fun country(ctx: Context): String = p(ctx).getString("country", ServerRepository.AUTO) ?: ServerRepository.AUTO
    fun setCountry(ctx: Context, code: String) = p(ctx).edit().putString("country", code).apply()

    fun lastInterstitial(ctx: Context) = p(ctx).getLong("last_interstitial", 0)
    fun setLastInterstitial(ctx: Context, t: Long) = p(ctx).edit().putLong("last_interstitial", t).apply()

    fun askedNotifications(ctx: Context) = p(ctx).getBoolean("asked_notifications", false)
    fun setAskedNotifications(ctx: Context) = p(ctx).edit().putBoolean("asked_notifications", true).apply()
}
