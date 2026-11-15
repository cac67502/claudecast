package com.aide.claudecast.volumeguard

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Watches for Android's "Volume lowered to safer level" notification.
 * When it shows up: taps its Undo button (same as doing it by hand),
 * then checks the media volume and puts it back if it's still low.
 */
class VolumeGuardService : NotificationListenerService() {

    companion object {
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private val WATCHED_PACKAGES = setOf("com.android.systemui", "android")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Media volume right before the most recent drop, and when that drop happened.
    private var levelBeforeDrop = -1
    private var dropAt = 0L
    private var restoring = false
    private val handled = HashMap<String, Long>()

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            if (restoring) return
            val now = intent.getIntExtra(EXTRA_VALUE, -1)
            val prev = intent.getIntExtra(EXTRA_PREV_VALUE, -1)
            if (now < 0 || prev < 0) return
            if (now < prev) {
                val t = SystemClock.elapsedRealtime()
                // A drop that's part of the same burst keeps the original starting level.
                if (t - dropAt > 3000) levelBeforeDrop = prev
                dropAt = t
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, filter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(volumeReceiver) } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        VolumeGuardPrefs.addLog(this, "Guard running")
    }

    override fun onListenerDisconnected() {
        VolumeGuardPrefs.addLog(this, "Guard disconnected, asking Android to restart it")
        requestRebind(ComponentName(this, VolumeGuardService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!VolumeGuardPrefs.enabled(this)) return
        if (sbn.packageName !in WATCHED_PACKAGES) return

        val n = sbn.notification
        val extras = n.extras
        val text = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT
        ).joinToString(" ") { extras.getCharSequence(it)?.toString() ?: "" }.trim()
        val lower = text.lowercase()
        val actions = n.actions ?: emptyArray()
        val actionTitles = actions.map { it.title?.toString() ?: "" }

        val mentionsVolume = "volume" in lower || "hearing" in lower || "listening" in lower
        if (!mentionsVolume) return

        // Log anything volume-related from the system so the matching can be tuned.
        VolumeGuardPrefs.addLog(this, "Seen: \"${text.take(140)}\" buttons=$actionTitles")

        val isLoweredAlert = "lower" in lower || "safe" in lower || "reduc" in lower ||
            actionTitles.any { "undo" in it.lowercase() }
        if (!isLoweredAlert) return

        val now = SystemClock.elapsedRealtime()
        val last = handled[sbn.key]
        if (last != null && now - last < 5000) return
        handled[sbn.key] = now

        val target = if (levelBeforeDrop > 0 && now - dropAt < 30_000) levelBeforeDrop else -1

        val undo = actions.firstOrNull {
            val t = it.title?.toString()?.lowercase() ?: ""
            "undo" in t || "restore" in t || "keep" in t
        }
        if (undo != null) {
            try {
                undo.actionIntent.send()
                VolumeGuardPrefs.addLog(this, "Tapped \"${undo.title}\"")
            } catch (e: Exception) {
                VolumeGuardPrefs.addLog(this, "Couldn't tap \"${undo.title}\": ${e.javaClass.simpleName}")
            }
        } else {
            VolumeGuardPrefs.addLog(this, "No Undo button on that alert")
        }

        handler.postDelayed({ verify(target, sbn.key) }, 1200)
    }

    private fun verify(target: Int, key: String) {
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (target > 0 && current < target) {
            restoring = true
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            } catch (e: Exception) {
                VolumeGuardPrefs.addLog(this, "Volume set failed: ${e.javaClass.simpleName}")
            }
            handler.postDelayed({ restoring = false }, 600)
            val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            VolumeGuardPrefs.addLog(this, "Volume was $current/$max, set back to $after/$max (wanted $target)")
        } else {
            VolumeGuardPrefs.addLog(this, "Volume now $current/$max" + if (target > 0) " (was $target before drop)" else "")
        }
        VolumeGuardPrefs.bumpSaves(this)
        try { cancelNotification(key) } catch (e: Exception) {}
    }
}
