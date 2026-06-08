package com.aide.claudecast.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {
    fun clock(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun clockSec(sec: Double): String = clock((sec * 1000).toLong())

    fun duration(sec: Long): String {
        if (sec <= 0) return ""
        val h = TimeUnit.SECONDS.toHours(sec)
        val m = TimeUnit.SECONDS.toMinutes(sec) % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m} min"
            else -> "${sec}s"
        }
    }

    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
    fun date(epochSec: Long): String =
        if (epochSec <= 0) "" else dateFmt.format(Date(epochSec * 1000))
}
