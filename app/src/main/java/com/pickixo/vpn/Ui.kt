package com.pickixo.vpn

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Draws the screen edge-to-edge on every Android version (Android 15+ forces this anyway) and pads
 * [root] so content stays clear of the status bar, navigation bar and display cutout.
 */
fun Activity.applySystemBarInsets(root: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
    root.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(l + insets.systemWindowInsetLeft, t + insets.systemWindowInsetTop,
                r + insets.systemWindowInsetRight, b + insets.systemWindowInsetBottom)
        }
        insets
    }
    root.requestApplyInsets()
}
