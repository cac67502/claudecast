package com.aide.claudecast.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal data class MusicData(
    val favorites: List<Station> = emptyList(),
    val heard: List<HeardSong> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val recordings: List<RecordingMeta> = emptyList(),
    val captureEnabled: Boolean = true,
    val localState: String = "",    // two-letter code, e.g. "KS"
    val tvFavorites: List<Channel> = emptyList()
)

/** File-backed store for radio favorites, saved songs, playlists and recording info. */
class MusicStore(context: Context) {
    private val file = File(context.filesDir, "music.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _data = MutableStateFlow(MusicData())

    private val _favorites = MutableStateFlow<List<Station>>(emptyList())
    val favorites: StateFlow<List<Station>> = _favorites.asStateFlow()
    private val _heard = MutableStateFlow<List<HeardSong>>(emptyList())
    val heard: StateFlow<List<HeardSong>> = _heard.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _recordings = MutableStateFlow<List<RecordingMeta>>(emptyList())
    val recordings: StateFlow<List<RecordingMeta>> = _recordings.asStateFlow()
    private val _captureEnabled = MutableStateFlow(true)
    val captureEnabled: StateFlow<Boolean> = _captureEnabled.asStateFlow()
    private val _tvFavorites = MutableStateFlow<List<Channel>>(emptyList())
    val tvFavorites: StateFlow<List<Channel>> = _tvFavorites.asStateFlow()
    private val _localState = MutableStateFlow("")
    val localState: StateFlow<String> = _localState.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val d = runCatching {
            if (file.exists()) json.decodeFromString<MusicData>(file.readText()) else MusicData()
        }.getOrDefault(MusicData())
        publish(d)
    }

    private fun publish(d: MusicData) {
        _data.value = d
        _favorites.value = d.favorites
        _heard.value = d.heard
        _playlists.value = d.playlists
        _recordings.value = d.recordings
        _captureEnabled.value = d.captureEnabled
        _localState.value = d.localState
        _tvFavorites.value = d.tvFavorites
    }

    @Synchronized
    private fun update(change: (MusicData) -> MusicData) {
        val d = change(_data.value)
        publish(d)
        runCatching { file.writeText(json.encodeToString(d)) }
    }

    /* Favorites */
    fun isFavorite(uuid: String) = _favorites.value.any { it.uuid == uuid }
    fun toggleFavorite(s: Station) = update { d ->
        if (d.favorites.any { it.uuid == s.uuid }) d.copy(favorites = d.favorites.filterNot { it.uuid == s.uuid })
        else d.copy(favorites = (d.favorites + s).sortedBy { it.name.lowercase() })
    }

    /* Heard on radio */
    fun addHeard(h: HeardSong) = update { d ->
        if (d.heard.any { it.artist.equals(h.artist, true) && it.title.equals(h.title, true) }) d
        else d.copy(heard = listOf(h) + d.heard)
    }
    fun removeHeard(h: HeardSong) = update { d -> d.copy(heard = d.heard - h) }

    /* Playlists */
    fun createPlaylist(name: String): String {
        val id = UUID.randomUUID().toString()
        update { d -> d.copy(playlists = d.playlists + Playlist(id, name.trim().ifBlank { "New playlist" })) }
        return id
    }
    fun deletePlaylist(id: String) = update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }
    fun addToPlaylist(id: String, uri: String) = update { d ->
        d.copy(playlists = d.playlists.map {
            if (it.id == id && uri !in it.songUris) it.copy(songUris = it.songUris + uri) else it
        })
    }
    fun removeFromPlaylist(id: String, uri: String) = update { d ->
        d.copy(playlists = d.playlists.map {
            if (it.id == id) it.copy(songUris = it.songUris - uri) else it
        })
    }

    /* Recordings */
    fun addRecording(r: RecordingMeta) = update { d -> d.copy(recordings = listOf(r) + d.recordings) }
    fun removeRecording(uri: String) = update { d ->
        d.copy(
            recordings = d.recordings.filterNot { it.uri == uri },
            playlists = d.playlists.map { it.copy(songUris = it.songUris - uri) }
        )
    }

    fun setCaptureEnabled(on: Boolean) = update { d -> d.copy(captureEnabled = on) }
    fun toggleTvFavorite(c: Channel) = update { d ->
        if (d.tvFavorites.any { it.id == c.id }) d.copy(tvFavorites = d.tvFavorites.filterNot { it.id == c.id })
        else d.copy(tvFavorites = (d.tvFavorites + c).sortedBy { it.name.lowercase() })
    }

    fun setLocalState(code: String) = update { d -> d.copy(localState = code) }
}
