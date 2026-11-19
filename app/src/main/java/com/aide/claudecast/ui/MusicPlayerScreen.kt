package com.aide.claudecast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.aide.claudecast.ServiceLocator
import com.aide.claudecast.data.HeardSong
import com.aide.claudecast.data.splitStreamTitle
import com.aide.claudecast.playback.MediaKind

/** Now-playing screen for songs and radio (podcasts keep their own player). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(onBack: () -> Unit) {
    val player = ServiceLocator.player
    val state by player.state.collectAsStateWithLifecycle()
    val station by player.currentStation.collectAsStateWithLifecycle()
    val isRadio = state.kind == MediaKind.Radio

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (isRadio) "Radio" else "Now playing") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            },
            actions = { CastButton() },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            // Capped so the art stays a sensible size on a wide TV screen. widthIn must come
            // first, or the fixed fillMaxWidth size wins and the cap does nothing.
            val artCap = if (com.aide.claudecast.TvMode.isTv(androidx.compose.ui.platform.LocalContext.current)) 190.dp else 360.dp
            if (isRadio) StationArt(station?.favicon, Modifier.widthIn(max = artCap).fillMaxWidth(0.72f).aspectRatio(1f), corner = 20)
            else SongArt(Modifier.widthIn(max = artCap).fillMaxWidth(0.72f).aspectRatio(1f), corner = 20)
            Spacer(Modifier.height(20.dp))

            Text(
                state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.podcastTitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            if (isRadio) RadioControls() else SongControls()
        }
    }
}

@Composable
private fun SongControls() {
    val player = ServiceLocator.player
    val state by player.state.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }

    Spacer(Modifier.height(16.dp))
    val dur = state.durationMs.coerceAtLeast(1L)
    Slider(
        value = dragging ?: (state.positionMs.toFloat() / dur).coerceIn(0f, 1f),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { player.seekTo((it * dur).toLong()) }
            dragging = null
        },
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary
        )
    )
    Row(Modifier.fillMaxWidth()) {
        Text(Format.clock(state.positionMs), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text(Format.clock(state.durationMs), style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val on = MaterialTheme.colorScheme.primary
        val off = MaterialTheme.colorScheme.onSurfaceVariant
        IconButton(onClick = { player.toggleShuffle() }) {
            Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) on else off)
        }
        IconButton(onClick = { player.previous() }) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(36.dp))
        }
        PlayPauseButton(state.isPlaying, state.isBuffering) { player.togglePlayPause() }
        IconButton(onClick = { player.next() }, enabled = state.hasNext) {
            Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(36.dp))
        }
        IconButton(onClick = { player.cycleRepeat() }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                "Repeat", tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) off else on
            )
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun RadioControls() {
    val player = ServiceLocator.player
    val music = ServiceLocator.music
    val state by player.state.collectAsStateWithLifecycle()
    val station by player.currentStation.collectAsStateWithLifecycle()
    val rec by ServiceLocator.recorder.state.collectAsStateWithLifecycle()
    val capture by music.captureEnabled.collectAsStateWithLifecycle()
    val favorites by music.favorites.collectAsStateWithLifecycle()
    val heard by music.heard.collectAsStateWithLifecycle()

    val st = station ?: return
    // What's on right now: the recorder's song info, else the player's.
    val nowPlaying = rec.currentTitle.ifBlank { if (state.title != st.name) state.title else "" }
    val (artist, title) = splitStreamTitle(nowPlaying)
    val alreadyHearted = nowPlaying.isNotBlank() &&
        heard.any { it.title.equals(title, true) && it.artist.equals(artist, true) }

    Spacer(Modifier.height(10.dp))
    Surface(color = MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.small) {
        Text("LIVE", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(16.dp))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isFav = favorites.any { it.uuid == st.uuid }
        IconButton(onClick = { music.toggleFavorite(st) }) {
            Icon(if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite station",
                tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlayPauseButton(state.isPlaying, state.isBuffering) { player.togglePlayPause() }
        IconButton(
            enabled = nowPlaying.isNotBlank() && !alreadyHearted,
            onClick = {
                music.addHeard(HeardSong(artist, title, st.name, System.currentTimeMillis()))
            }
        ) {
            Icon(if (alreadyHearted) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                "Save to Heard list",
                tint = if (alreadyHearted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Spacer(Modifier.height(24.dp))

    // Record
    if (capture) {
        if (rec.armed) {
            Button(
                onClick = { ServiceLocator.recorder.toggleArm() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.FiberManualRecord, null)
                Spacer(Modifier.width(8.dp))
                Text("Recording. Saves when the song ends. Tap to cancel")
            }
        } else {
            OutlinedButton(
                onClick = { ServiceLocator.recorder.toggleArm() },
                enabled = rec.canRecord,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Record this song")
            }
        }
        if (rec.message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(rec.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Song recording", style = MaterialTheme.typography.bodyMedium)
            Text("Listens to the stream a second time so a song can be saved. Uses about 1 MB per minute of extra data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = capture, onCheckedChange = { music.setCaptureEnabled(it) })
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        if (isBuffering && !isPlaying) CircularProgressIndicator(
            Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp
        ) else Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (isPlaying) "Pause" else "Play",
            Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}
