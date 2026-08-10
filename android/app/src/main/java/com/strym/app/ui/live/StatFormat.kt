package com.strym.app.ui.live

import java.util.Locale

fun formatBitrate(bps: Double): String = when {
    bps <= 0.0 -> "0 bps"
    bps < 1_000.0 -> "${bps.toInt()} bps"
    bps < 1_000_000.0 -> String.format(Locale.US, "%.0f kbps", bps / 1_000.0)
    else -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
}

fun formatDropRatio(ratio: Double): String =
    String.format(Locale.US, "%.1f%%", ratio * 100.0)

fun formatLagMs(ms: Long): String = when {
    ms < 0 -> "0 ms"
    ms < 1_000 -> "$ms ms"
    else -> String.format(Locale.US, "%.1f s", ms / 1_000.0)
}

fun formatRtt(rttMs: Double?): String =
    rttMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "–"

fun formatUptime(uptimeMs: Long): String {
    val totalSeconds = (uptimeMs / 1_000L).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
