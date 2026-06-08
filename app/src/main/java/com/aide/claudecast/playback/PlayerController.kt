package com.aide.claudecast.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aide.claudecast.data.Episode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val episodeId: String = "",
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null
)

/** Wraps a Media3 MediaController; exposes flows for Compose + persists resume positions. */
class PlayerController(
    context: Context,
    private val onPersistPosition: suspend (String, Long) -> Unit,
    private val resumePositionProvider: (String) -> Long
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode: StateFlow<Episode?> = _currentEpisode.asStateFlow()

    private var queue: List<Episode> = emptyList()

    private var defaultSpeed = 1.0f

    fun setDefaultSpeed(speed: Float) { defaultSpeed = speed }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(playerListener)
            controller?.let { syncFrom(it) }
            startPolling()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncFrom(player) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Auto-advance: keep currentEpisode in sync as the playlist moves on.
            val id = mediaItem?.mediaId
            queue.firstOrNull { it.id == id }?.let { _currentEpisode.value = it }
            controller?.let { syncFrom(it) }
        }
    }

    private fun syncFrom(player: Player) {
        val ep = _currentEpisode.value
        _state.value = _state.value.copy(
            hasMedia = player.mediaItemCount > 0,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.coerceAtLeast(0L),
            speed = player.playbackParameters.speed,
            episodeId = ep?.id ?: "",
            title = player.mediaMetadata.title?.toString() ?: ep?.title.orEmpty(),
            podcastTitle = player.mediaMetadata.artist?.toString() ?: ep?.podcastTitle.orEmpty(),
            artworkUrl = ep?.artworkUrl
        )
    }

    private fun startPolling() {
        scope.launch {
            var ticks = 0
            while (true) {
                val c = controller
                if (c != null && c.mediaItemCount > 0) {
                    val pos = c.currentPosition.coerceAtLeast(0L)
                    _state.value = _state.value.copy(
                        positionMs = pos,
                        durationMs = c.duration.coerceAtLeast(0L),
                        isPlaying = c.isPlaying,
                        isBuffering = c.playbackState == Player.STATE_BUFFERING
                    )
                    // persist resume position every ~5s while playing
                    if (c.isPlaying && ++ticks >= 10) {
                        ticks = 0
                        _currentEpisode.value?.let { onPersistPosition(it.id, pos) }
                    }
                }
                delay(500)
            }
        }
    }

    /** Play a single episode (a one-item queue). */
    fun play(episode: Episode) = playQueue(listOf(episode), 0)

    /**
     * Load [episodes] as a playlist starting at [startIndex] and play. ExoPlayer then
     * auto-advances to the next episode in the list when each one finishes.
     */
    fun playQueue(episodes: List<Episode>, startIndex: Int) {
        val c = controller ?: return
        if (episodes.isEmpty()) return
        val idx = startIndex.coerceIn(0, episodes.lastIndex)
        queue = episodes
        _currentEpisode.value = episodes[idx]
        val items = episodes.map { ep ->
            MediaItem.Builder()
                .setMediaId(ep.id)
                .setUri(ep.enclosureUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ep.title)
                        .setArtist(ep.podcastTitle)
                        .setArtworkUri(ep.artworkUrl?.let { Uri.parse(it) })
                        .build()
                )
                .build()
        }
        val resume = resumePositionProvider(episodes[idx].id)
        c.setMediaItems(items, idx, resume)
        c.prepare()
        c.setPlaybackSpeed(defaultSpeed)
        c.playWhenReady = true
        syncFrom(c)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            _currentEpisode.value?.let { ep -> scope.launch { onPersistPosition(ep.id, c.currentPosition) } }
        } else {
            c.play()
        }
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0L)) }
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0L, if (c.duration > 0) c.duration else Long.MAX_VALUE))
    }

    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }
}
