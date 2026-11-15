package com.aide.claudecast.data

import kotlinx.serialization.Serializable

/** An internet radio station from the Radio Browser directory. */
@Serializable
data class Station(
    val uuid: String,
    val name: String,
    val url: String,            // resolved stream URL
    val favicon: String? = null,
    val tags: String = "",
    val country: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Boolean = false,
    val plays: Int = 0          // listens in the last 24 hours (Radio Browser "clickcount")
)

/** A song on the phone (bought, ripped from CD, or recorded off the radio). */
data class Song(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isRecording: Boolean
)

/** Extra info we keep for songs we recorded, since .aac files carry no tags. */
@Serializable
data class RecordingMeta(
    val uri: String,
    val title: String,
    val artist: String,
    val station: String,
    val recordedAt: Long,
    val partial: Boolean = false
)

/** A song saved from radio with the heart button. */
@Serializable
data class HeardSong(
    val artist: String,
    val title: String,
    val station: String,
    val heardAt: Long
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songUris: List<String> = emptyList()
)

/** Split a radio "StreamTitle" like "Artist - Song" into its parts. */
fun splitStreamTitle(raw: String): Pair<String, String> {
    val t = raw.trim()
    val i = t.indexOf(" - ")
    return if (i > 0) t.substring(0, i).trim() to t.substring(i + 3).trim() else "" to t
}
