package com.aide.claudecast.playback

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.aide.claudecast.ServiceLocator
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Foreground media service. Media3 supplies the lock-screen / notification controls. */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retryAttempt = 0
    private var retryPending: Runnable? = null

    private fun attributes(speech: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes(speech = true), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Keep the CPU and Wi-Fi awake while playing, so a stream doesn't starve
            // and stop once the screen is off in a pocket.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(ResilienceListener(player))

        mediaSession = MediaSession.Builder(this, player).build()
    }

    /**
     * Keeps playback going through the small failures of a working day:
     * - a network blip (Wi-Fi handoff, weak signal) retries on its own with a growing
     *   pause instead of leaving the player stopped until play is pressed;
     * - music and radio duck under a notification sound; only spoken podcasts pause for it.
     */
    private inner class ResilienceListener(private val player: ExoPlayer) : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val speech = mediaItem != null && isSpoken(mediaItem.mediaId)
            val wanted = attributes(speech)
            if (player.audioAttributes != wanted) player.setAudioAttributes(wanted, true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retryAttempt = 0
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // The listener paused or stopped on purpose: don't fight them.
            if (!playWhenReady) cancelRetry()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isNetworkError(error) || !player.playWhenReady) return
            if (retryAttempt >= MAX_RETRIES) return
            val wait = (2_000L shl retryAttempt.coerceAtMost(4)).coerceAtMost(30_000L)
            retryAttempt++
            cancelRetry()
            val r = Runnable {
                retryPending = null
                if (player.playbackState == Player.STATE_IDLE && player.playWhenReady &&
                    player.mediaItemCount > 0
                ) {
                    if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
                    player.prepare()
                }
            }
            retryPending = r
            handler.postDelayed(r, wait)
        }
    }

    private fun cancelRetry() {
        retryPending?.let { handler.removeCallbacks(it) }
        retryPending = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Swiping the app away closes it: stop playback, drop the notification, shut down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        cancelRetry()
        mediaSession?.player?.run {
            stop()
            clearMediaItems()
        }
        ServiceLocator.recorder.stop()
        stopSelf()
    }

    override fun onDestroy() {
        cancelRetry()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** About five minutes of retrying (2, 4, 8, 16, 30, 30 … s) before giving up. */
        private const val MAX_RETRIES = 12

        /** Podcasts are speech; songs (content://), radio, TV and movies are not. */
        fun isSpoken(mediaId: String): Boolean =
            !mediaId.startsWith("content:") && !mediaId.startsWith("radio:") &&
                !mediaId.startsWith("tv:") && !mediaId.startsWith("movie:")

        /** Errors worth retrying: the network or the server, not a broken file. */
        fun isNetworkError(e: PlaybackException): Boolean = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_TIMEOUT -> true
            else -> false
        }
    }
}
