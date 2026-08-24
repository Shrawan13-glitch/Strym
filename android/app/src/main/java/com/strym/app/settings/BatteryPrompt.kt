package com.strym.app.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption, asked about gracefully and exactly once.
 *
 * The exemption decides whether Android suspends network access when the app
 * is backgrounded — without it, Doze and OEM power managers are the #1 killer
 * of long mobile broadcasts. The flow is deliberately gentle: the first time
 * the user goes live, they get one plain-language explanation with an opt-out;
 * afterwards only the Settings row surfaces it again. Streaming itself never
 * waits on any of this.
 */
object BatteryPrompt {

    private const val PREFS = "battery_prompt"
    private const val KEY_ASKED = "asked_once"

    fun wasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The system allowlist dialog. Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
