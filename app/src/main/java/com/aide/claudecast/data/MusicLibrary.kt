package com.aide.claudecast.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The music on the phone. Reads Android's media index (nothing leaves the device),
 * and saves radio recordings into Music/ClaudeCast/<station>/ so they survive
 * reinstalling the app and show up in any other music player too.
 */
class MusicLibrary(private val context: Context, private val store: MusicStore) {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    val readPermission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val meta = store.recordings.value.associateBy { it.uri }
        val out = mutableListOf<Song>()
        val seen = HashSet<String>()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val selection = if (Build.VERSION.SDK_INT >= 29) {
            projection += MediaStore.Audio.Media.RELATIVE_PATH
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE 'Music/ClaudeCast/%')"
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }

        runCatching {
            context.contentResolver.query(collection, projection.toTypedArray(), selection, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val tCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val arCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val alCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val dCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val m = meta[uri]
                    out += Song(
                        id = id,
                        uri = uri,
                        title = m?.title ?: c.getString(tCol).orEmpty().ifBlank { "Unknown title" },
                        artist = m?.artist?.ifBlank { null } ?: cleanUnknown(c.getString(arCol)),
                        album = m?.station ?: cleanUnknown(c.getString(alCol)),
                        durationMs = c.getLong(dCol),
                        isRecording = m != null
                    )
                    seen += uri
                }
            }
        }

        // Older Android: recordings live in the app's own folder as file:// URIs.
        meta.values.filter { it.uri.startsWith("file:") && it.uri !in seen }.forEach { m ->
            val f = File(Uri.parse(m.uri).path ?: "")
            if (f.exists()) out += Song(
                id = m.uri.hashCode().toLong(), uri = m.uri, title = m.title,
                artist = m.artist.ifBlank { "Unknown artist" }, album = m.station,
                durationMs = 0L, isRecording = true
            )
        }

        _songs.value = out.sortedBy { it.title.lowercase() }
    }

    private fun cleanUnknown(s: String?): String =
        s?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown"

    /** Save one recorded song. Returns its URI, or null if saving failed. */
    suspend fun saveRecording(
        audio: ByteArray, isMp3: Boolean,
        artist: String, title: String, station: String, partial: Boolean
    ): String? = withContext(Dispatchers.IO) {
        val ext = if (isMp3) "mp3" else "aac"
        val mime = if (isMp3) "audio/mpeg" else "audio/aac"
        val bytes = if (isMp3) id3Tag(title, artist, station) + audio else audio
        val name = safe(listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
            .ifBlank { "Recording ${System.currentTimeMillis()}" }) + ".$ext"
        val folder = safe(station).ifBlank { "Radio" }

        val uri: String? = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ClaudeCast/$folder")
                    put(MediaStore.Audio.Media.TITLE, title)
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    put(MediaStore.Audio.Media.ALBUM, station)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val target = resolver.insert(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                ) ?: return@runCatching null
                resolver.openOutputStream(target)?.use { it.write(bytes) }
                resolver.update(target, ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }, null, null)
                target.toString()
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "ClaudeCast/$folder")
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(bytes)
                Uri.fromFile(f).toString()
            }
        }.getOrNull()

        if (uri != null) {
            store.addRecording(
                RecordingMeta(uri, title.ifBlank { "Untitled" }, artist, station, System.currentTimeMillis(), partial)
            )
            refresh()
        }
        uri
    }

    /** Delete a song this app recorded. */
    suspend fun deleteRecording(song: Song): Boolean = withContext(Dispatchers.IO) {
        if (!song.isRecording) return@withContext false
        val ok = runCatching {
            val u = Uri.parse(song.uri)
            if (u.scheme == "file") File(u.path ?: "").delete()
            else context.contentResolver.delete(u, null, null) > 0
        }.getOrDefault(false)
        if (ok) {
            store.removeRecording(song.uri)
            refresh()
        }
        ok
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)

    /** Minimal ID3v2.3 tag so the title/artist show in any player. */
    private fun id3Tag(title: String, artist: String, album: String): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, text: String) {
            if (text.isBlank()) return
            val body = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            val n = body.size
            frames.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        frame("TIT2", title)
        frame("TPE1", artist)
        frame("TALB", album)
        frame("TCON", "Radio recording")
        val payload = frames.toByteArray()
        val n = payload.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
            ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()
        )
        return header + payload
    }
}
