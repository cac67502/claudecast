package com.aide.claudecast.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aide.claudecast.data.Episode
import com.aide.claudecast.data.Channel
import com.aide.claudecast.data.Movie
import com.aide.claudecast.data.Song
import com.aide.claudecast.data.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MediaKind { None, Podcast, Music, Radio, Tv, Movie }

data class PlayerUiState(
    val kind: MediaKind = MediaKind.None,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
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

    private var kind = MediaKind.None
    private val _currentStation = MutableStateFlow<Station?>(null)
    val currentStation: StateFlow<Station?> = _currentStation.asStateFlow()
    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _currentMovie = MutableStateFlow<Movie?>(null)
    val currentMovie: StateFlow<Movie?> = _currentMovie.asStateFlow()
    private var movieUrl: String = ""

    /** Saves a movie's resume point: (id, positionMs, durationMs). */
    var onMoviePosition: ((String, Long, Long) -> Unit)? = null

    /** The underlying player, for attaching a video surface (TV). */
    val videoPlayer: Player? get() = controller

    private var defaultSpeed = 1.0f

    // Sleep timer
    private var sleepJob: Job? = null
    private var sleepAtEpisodeEnd = false
    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs.asStateFlow()
    private val _sleepEndOfEpisode = MutableStateFlow(false)
    val sleepEndOfEpisode: StateFlow<Boolean> = _sleepEndOfEpisode.asStateFlow()

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
        val st = _currentStation.value
        val metaTitle = player.mediaMetadata.title?.toString().orEmpty()
        _state.value = _state.value.copy(
            kind = if (player.mediaItemCount > 0) kind else MediaKind.None,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            hasMedia = player.mediaItemCount > 0,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.coerceAtLeast(0L),
            speed = player.playbackParameters.speed,
            episodeId = ep?.id ?: "",
            title = when {
                st != null -> metaTitle.takeIf { it.isNotBlank() && !it.equals(st.name, true) } ?: st.name
                else -> player.mediaMetadata.title?.toString() ?: ep?.title.orEmpty()
            },
            podcastTitle = when {
                st != null -> st.name
                else -> player.mediaMetadata.artist?.toString() ?: ep?.podcastTitle.orEmpty()
            },
            artworkUrl = ep?.artworkUrl ?: st?.favicon ?: _currentChannel.value?.logo ?: _currentMovie.value?.poster
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
                        _currentMovie.value?.let { onMoviePosition?.invoke(it.id, pos, c.duration) }
                    }
                    // sleep timer: stop at the end of the current episode
                    if (sleepAtEpisodeEnd) {
                        val d = c.duration
                        if (d > 0 && pos >= d - 800) {
                            pauseInternal()
                            cancelSleepTimer()
                        }
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
        kind = MediaKind.Podcast
        _currentChannel.value = null
        _currentMovie.value = null
        _currentStation.value = null
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
        // Live shows start at the live edge, never at a saved position.
        val resume = if (episodes[idx].id.startsWith("live:")) C.TIME_UNSET
        else resumePositionProvider(episodes[idx].id)
        c.setMediaItems(items, idx, resume)
        c.prepare()
        c.setPlaybackSpeed(defaultSpeed)
        c.playWhenReady = true
        syncFrom(c)
    }

    /** Play songs from the phone's library as a playlist. */
    fun playSongs(songs: List<Song>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        queue = emptyList()
        kind = MediaKind.Music
        _currentChannel.value = null
        _currentMovie.value = null
        _currentStation.value = null
        _currentEpisode.value = null
        val items = songs.map { s ->
            MediaItem.Builder()
                .setMediaId(s.uri)
                .setUri(s.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .build()
                )
                .build()
        }
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(items, if (shuffle) (songs.indices).random() else idx, 0L)
        c.prepare()
        c.setPlaybackSpeed(1.0f)
        c.playWhenReady = true
        syncFrom(c)
    }

    /** Play a live radio station. */
    fun playStation(station: Station) {
        val c = controller ?: return
        queue = emptyList()
        kind = MediaKind.Radio
        _currentChannel.value = null
        _currentMovie.value = null
        _currentEpisode.value = null
        _currentStation.value = station
        val item = MediaItem.Builder()
            .setMediaId("radio:" + station.uuid)
            .setUri(station.url)
            .apply { if (station.hls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.name)
                    .setArtworkUri(station.favicon?.let { Uri.parse(it) })
                    .build()
            )
            .build()
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setMediaItem(item)
        c.prepare()
        c.setPlaybackSpeed(1.0f)
        c.playWhenReady = true
        syncFrom(c)
    }

    /** Play an on-demand movie from [url], starting at [startMs]. */
    fun playMovie(movie: Movie, url: String, startMs: Long) {
        val c = controller ?: return
        queue = emptyList()
        kind = MediaKind.Movie
        _currentEpisode.value = null
        _currentStation.value = null
        _currentChannel.value = null
        _currentMovie.value = null
        _currentMovie.value = movie
        movieUrl = url
        val item = MediaItem.Builder()
            .setMediaId("movie:" + movie.id)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(movie.title)
                    .setArtist(listOf("Movie", movie.year).filter { it.isNotBlank() }.joinToString(" • "))
                    .setArtworkUri(Uri.parse(movie.poster))
                    .build()
            )
            .build()
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setMediaItem(item, startMs.coerceAtLeast(0L))
        c.prepare()
        c.setPlaybackSpeed(1.0f)
        c.playWhenReady = true
        syncFrom(c)
    }

    /** Play a live TV channel. */
    fun playChannel(channel: Channel) {
        val c = controller ?: return
        queue = emptyList()
        kind = MediaKind.Tv
        _currentEpisode.value = null
        _currentStation.value = null
        _currentChannel.value = channel
        _currentMovie.value = null
        val item = MediaItem.Builder()
            .setMediaId("tv:" + channel.id)
            .setUri(channel.url)
            .apply {
                if (".m3u8" in channel.url.lowercase()) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(channel.name)
                    .setArtist("Live TV")
                    .setArtworkUri(channel.logo?.let { Uri.parse(it) })
                    .build()
            )
            .build()
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setMediaItem(item)
        c.prepare()
        c.setPlaybackSpeed(1.0f)
        c.playWhenReady = true
        syncFrom(c)
    }

    /**
     * What's playing, in a form a TV can fetch itself, or null if it can't be cast
     * (songs stored on the phone, or nothing playing).
     */
    fun castItem(): CastItem? {
        val c = controller ?: return null
        if (c.mediaItemCount == 0) return null
        val pos = c.currentPosition.coerceAtLeast(0L)
        fun isHls(u: String) = ".m3u8" in u.lowercase()
        return when (kind) {
            MediaKind.Podcast -> {
                val id = c.currentMediaItem?.mediaId
                val ep = queue.firstOrNull { it.id == id } ?: _currentEpisode.value ?: return null
                val u = ep.enclosureUrl
                if (!u.startsWith("http")) return null
                val lower = u.lowercase().substringBefore('?')
                val (mime, video) = when {
                    lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4" to true
                    lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4" to false
                    else -> "audio/mpeg" to false
                }
                CastItem(u, ep.title, mime, video, isLive = false, startMs = pos)
            }
            MediaKind.Radio -> {
                val st = _currentStation.value ?: return null
                val mime = when {
                    st.hls || isHls(st.url) -> "application/vnd.apple.mpegurl"
                    st.codec.contains("aac", true) -> "audio/aac"
                    else -> "audio/mpeg"
                }
                CastItem(st.url, st.name, mime, isVideo = false, isLive = true, startMs = 0)
            }
            MediaKind.Tv -> {
                val ch = _currentChannel.value ?: return null
                val mime = if (isHls(ch.url)) "application/vnd.apple.mpegurl" else "video/mp4"
                CastItem(ch.url, ch.name, mime, isVideo = true, isLive = true, startMs = 0)
            }
            MediaKind.Movie -> {
                val m = _currentMovie.value ?: return null
                if (movieUrl.isBlank()) return null
                CastItem(movieUrl, m.title, "video/mp4", isVideo = true, isLive = false, startMs = pos)
            }
            else -> null   // songs on the phone: the TV can't reach them
        }
    }

    fun pause() { controller?.let { if (it.isPlaying) togglePlayPause() } }

    /** Pick up on the phone where the TV left off. */
    fun resumeAfterCast(positionMs: Long, wasLive: Boolean) {
        val c = controller ?: return
        if (wasLive) { togglePlayPause(); return }   // rejoins the live stream
        if (positionMs > 0) c.seekTo(positionMs)
        c.play()
    }

    fun next() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (!c.isPlaying && kind == MediaKind.Radio) {
            // Rejoin the live broadcast instead of resuming a stale buffer.
            _currentStation.value?.let { playStation(it) }
            return
        }
        if (!c.isPlaying && kind == MediaKind.Tv) {
            _currentChannel.value?.let { playChannel(it) }
            return
        }
        if (c.isPlaying) {
            c.pause()
            _currentMovie.value?.let { onMoviePosition?.invoke(it.id, c.currentPosition, c.duration) }
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

    /* ---------- Sleep timer ---------- */

    private fun pauseInternal() {
        val c = controller ?: return
        c.pause()
        _currentEpisode.value?.let { ep -> scope.launch { onPersistPosition(ep.id, c.currentPosition) } }
    }

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepAtEpisodeEnd = false
        _sleepEndOfEpisode.value = false
        sleepJob = scope.launch {
            var remaining = minutes * 60_000L
            _sleepRemainingMs.value = remaining
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepRemainingMs.value = remaining.coerceAtLeast(0L)
            }
            pauseInternal()
            _sleepRemainingMs.value = null
            sleepJob = null
        }
    }

    fun sleepAtEndOfEpisode() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = null
        sleepAtEpisodeEnd = true
        _sleepEndOfEpisode.value = true
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepAtEpisodeEnd = false
        _sleepRemainingMs.value = null
        _sleepEndOfEpisode.value = false
    }
}
