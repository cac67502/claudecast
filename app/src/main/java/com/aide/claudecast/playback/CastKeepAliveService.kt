package com.aide.claudecast.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aide.claudecast.MainActivity
import com.aide.claudecast.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps ClaudeCast running while it's feeding a TV. Without this, Android pauses the
 * app a few minutes after it goes to the background (the phone's own player is paused
 * while casting), and the TV loses the stream.
 */
class CastKeepAliveService : Service() {

    companion object {
        private const val CHANNEL = "casting"
        private const val ID = 4207
        private const val ACTION_STOP = "com.aide.claudecast.STOP_CASTING"
        private const val EXTRA_TV = "tv"

        fun start(context: Context, tvName: String) {
            val i = Intent(context, CastKeepAliveService::class.java).putExtra(EXTRA_TV, tvName)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, i) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastKeepAliveService::class.java)) }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                val live = ServiceLocator.caster.state.value.isLive
                val pos = ServiceLocator.caster.stop()
                if (!live) ServiceLocator.player.seekTo(pos)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val tv = intent?.getStringExtra(EXTRA_TV) ?: "TV"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while ClaudeCast is playing on a TV"
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Casting to $tv")
            .setContentText(ServiceLocator.caster.state.value.title.ifBlank { "ClaudeCast" })
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop casting", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.launch { }.cancel()
    }
}
