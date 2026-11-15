package com.aide.claudecast.playback

import com.aide.claudecast.data.MusicLibrary
import com.aide.claudecast.data.MusicStore
import com.aide.claudecast.data.Station
import com.aide.claudecast.data.splitStreamTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.concurrent.TimeUnit

data class RecorderState(
    val listening: Boolean = false,
    val message: String = "",
    val currentTitle: String = "",
    val joinedMidSong: Boolean = true,
    val armed: Boolean = false,
    val canRecord: Boolean = false
)

/**
 * Tape-deck style recorder for radio. While a station plays, it listens to the same
 * stream and keeps the song that's on in memory. Nothing is saved unless you press
 * Record; then that one song is written to your library when the station's song
 * info changes. One song per press, for your own listening.
 */
class StreamRecorder(
    private val library: MusicLibrary,
    private val store: MusicStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var call: okhttp3.Call? = null
    private var stopJob: Job? = null
    private var activeUuid: String? = null

    @Volatile private var armed = false

    private val maxSongBytes = 30 * 1024 * 1024

    /** Start/stop automatically with radio playback. */
    fun bind(player: PlayerController) {
        scope.launch {
            combine(player.currentStation, player.state, store.captureEnabled) { st, ps, capture ->
                val want = st != null && capture && !st.hls && (ps.isPlaying || ps.isBuffering)
                if (want) st else null
            }.distinctUntilChanged().collect { st ->
                if (st != null) {
                    stopJob?.cancel()
                    if (activeUuid != st.uuid) start(st)
                } else {
                    // Short grace period so a quick pause doesn't throw away the song.
                    stopJob?.cancel()
                    stopJob = scope.launch { delay(20_000); stop() }
                }
            }
        }
        scope.launch {
            player.currentStation.collect { st ->
                if (st == null) stop()
                else if (st.hls) _state.value = RecorderState(message = "This station can't be recorded (HLS stream).")
            }
        }
    }

    fun toggleArm() {
        if (!_state.value.canRecord) return
        armed = !armed
        _state.value = _state.value.copy(armed = armed)
    }

    private fun start(station: Station) {
        stop()
        activeUuid = station.uuid
        armed = false
        job = scope.launch {
            while (isActive) {
                try {
                    _state.value = RecorderState(listening = true, message = "Connecting…")
                    capture(station)
                } catch (e: Exception) {
                    if (!isActive) break
                    _state.value = _state.value.copy(canRecord = false, message = "Reconnecting…")
                }
                delay(5000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        call?.cancel()   // unblocks a read that's waiting on the network
        call = null
        activeUuid = null
        armed = false
        _state.value = RecorderState()
    }

    private suspend fun capture(station: Station) {
        val req = Request.Builder().url(station.url)
            .header("Icy-MetaData", "1")
            .header("User-Agent", "ClaudeCast/1.2")
            .build()
        val c = client.newCall(req).also { call = it }
        c.execute().use { resp ->
            val metaInt = resp.header("icy-metaint")?.trim()?.toIntOrNull() ?: 0
            val ctype = resp.header("Content-Type").orEmpty().lowercase()
            val isMp3 = "mpeg" in ctype || "mp3" in ctype
            val isAac = "aac" in ctype
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            if (metaInt <= 0) {
                _state.value = RecorderState(message = "This station doesn't send song info, so songs can't be recorded.")
                kotlinx.coroutines.awaitCancellation()
            }
            if (!isMp3 && !isAac) {
                _state.value = RecorderState(message = "This station's audio format can't be recorded.")
                kotlinx.coroutines.awaitCancellation()
            }

            val input = DataInputStream(resp.body!!.byteStream())
            val chunk = ByteArray(metaInt)
            var segment = ByteArrayOutputStream()
            var title = ""
            var clean = false   // true once a song started while we were listening
            var overflow = false

            _state.value = RecorderState(listening = true, message = "Waiting for song info…")

            while (kotlin.coroutines.coroutineContext.isActive) {
                input.readFully(chunk)
                if (!overflow) {
                    if (segment.size() + chunk.size > maxSongBytes) {
                        overflow = true
                        segment = ByteArrayOutputStream()
                    } else segment.write(chunk)
                }

                val metaLen = input.read() * 16
                if (metaLen < 0) throw RuntimeException("Stream ended")
                if (metaLen == 0) continue
                val meta = ByteArray(metaLen).also { input.readFully(it) }
                val newTitle = parseStreamTitle(meta) ?: continue
                if (newTitle == title) continue

                // Song changed: save the one that just ended if Record was pressed.
                if (armed && title.isNotBlank() && !overflow && segment.size() > 0) {
                    val bytes = segment.toByteArray()
                    val (artist, song) = splitStreamTitle(title)
                    val partial = !clean
                    scope.launch {
                        val uri = library.saveRecording(bytes, isMp3, artist, song, station.name, partial)
                        _state.value = _state.value.copy(
                            message = if (uri != null)
                                "Saved \"${song.ifBlank { title }}\"" + if (partial) " (started mid-song)" else ""
                            else "Couldn't save that song."
                        )
                    }
                }

                val firstTitle = title.isEmpty()
                title = newTitle
                segment = ByteArrayOutputStream()
                overflow = false
                clean = !firstTitle
                armed = false
                _state.value = _state.value.copy(
                    listening = true,
                    canRecord = newTitle.isNotBlank(),
                    currentTitle = newTitle,
                    joinedMidSong = !clean,
                    armed = false,
                    message = if (_state.value.message.startsWith("Saved")) _state.value.message
                    else if (!clean) "Joined mid-song. Record now saves from here; the next song can be saved in full."
                    else "Ready. Press Record to save this song."
                )
            }
        }
    }

    private fun parseStreamTitle(meta: ByteArray): String? {
        val end = meta.indexOfFirst { it == 0.toByte() }.let { if (it < 0) meta.size else it }
        val raw = meta.copyOf(end)
        var text = String(raw, Charsets.UTF_8)
        if ('�' in text) text = String(raw, Charsets.ISO_8859_1)
        val key = "StreamTitle='"
        val i = text.indexOf(key)
        if (i < 0) return null
        val start = i + key.length
        val stop = text.indexOf("';", start).let { if (it < 0) text.lastIndexOf('\'') else it }
        if (stop < start) return null
        return text.substring(start, stop).trim()
    }
}
