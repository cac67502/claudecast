package com.aide.claudecast.volumeguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VolumeGuardPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("claudecast_volumeguard", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun saves(c: Context) = sp(c).getInt("saves", 0)
    fun bumpSaves(c: Context) = sp(c).edit().putInt("saves", saves(c) + 1).apply()

    fun log(c: Context): String = sp(c).getString("log", "") ?: ""

    @Synchronized
    fun addLog(c: Context, line: String) {
        val ts = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(Date())
        val lines = listOf("$ts  $line") + log(c).lines().filter { it.isNotBlank() }
        sp(c).edit().putString("log", lines.take(80).joinToString("\n")).apply()
    }

    fun clearLog(c: Context) = sp(c).edit().putString("log", "").apply()
}
