package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.provider.Settings

/** Opens the car's own hotspot screen, falling back to Android's wireless settings. */
internal object CarHotspotScreens {
    private const val ACTION_TETHER_SETTINGS = "com.android.settings.WIFI_TETHER_SETTINGS"

    fun open(activity: Activity): Boolean {
        val hotspot = Intent(ACTION_TETHER_SETTINGS)
        val target = activity.packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        // BYD shows its hotspot screen as a dialog that closes unless its settings or the home screen is on top.
        if (target == "com.byd.carsettings") {
            runCatching { activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (target != null && runCatching { activity.startActivity(hotspot) }.isSuccess) return true
        return runCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.isSuccess
    }
}
