package com.aide.claudecast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aide.claudecast.ServiceLocator
import com.aide.claudecast.data.Chapter
import com.aide.claudecast.data.Episode
import com.aide.claudecast.data.TranscriptCue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {
    private val repo = ServiceLocator.repo
    val player = ServiceLocator.player

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters

    private val _transcript = MutableStateFlow<List<TranscriptCue>>(emptyList())
    val transcript: StateFlow<List<TranscriptCue>> = _transcript

    private val _loadingTranscript = MutableStateFlow(false)
    val loadingTranscript: StateFlow<Boolean> = _loadingTranscript

    private var loadedForEpisode = ""

    fun ensureLoaded(ep: Episode) {
        if (ep.id == loadedForEpisode) return
        loadedForEpisode = ep.id
        _chapters.value = emptyList()
        _transcript.value = emptyList()
        ep.chaptersUrl?.let { url ->
            viewModelScope.launch { _chapters.value = repo.chapters(url) }
        }
        ep.transcripts.firstOrNull()?.let { ref ->
            viewModelScope.launch {
                _loadingTranscript.value = true
                _transcript.value = repo.transcript(ref)
                _loadingTranscript.value = false
            }
        }
    }
}

private val speedSteps = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    vm: PlayerViewModel = viewModel()
) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    val episode by vm.player.currentEpisode.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val loadingTranscript by vm.loadingTranscript.collectAsStateWithLifecycle()

    LaunchedEffect(episode?.id) { episode?.let { vm.ensureLoaded(it) } }

    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Now Playing", "Chapters", "Transcript", "Details")

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Playing", maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> NowPlayingTab(vm, state, episode)
                1 -> ChaptersTab(chapters, state.positionMs) { vm.player.seekTo((it * 1000).toLong()) }
                2 -> TranscriptTab(transcript, loadingTranscript, state.positionMs) {
                    vm.player.seekTo((it * 1000).toLong())
                }
                3 -> DetailsTab(episode)
            }
        }
    }
}

@Composable
private fun NowPlayingTab(
    vm: PlayerViewModel,
    state: com.aide.claudecast.playback.PlayerUiState,
    episode: Episode?
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Artwork(
            state.artworkUrl ?: episode?.artworkUrl,
            modifier = Modifier.fillMaxWidth(0.8f).size(280.dp),
            corner = 18
        )
        Spacer(Modifier.height(24.dp))
        Text(
            state.title.ifBlank { episode?.title.orEmpty() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Text(
            state.podcastTitle.ifBlank { episode?.podcastTitle.orEmpty() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        // Scrubber
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(0f) }
        val dur = state.durationMs.coerceAtLeast(1L)
        val sliderPos = if (dragging) dragValue else state.positionMs.toFloat() / dur

        Slider(
            value = sliderPos.coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                vm.player.seekTo((dragValue * dur).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                Format.clock(if (dragging) (dragValue * dur).toLong() else state.positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                Format.clock(state.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.player.seekBy(-10_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Replay10, "Back 10s", modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(20.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            ) {
                IconButton(onClick = { vm.player.togglePlayPause() }) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            IconButton(onClick = { vm.player.seekBy(30_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Forward30, "Forward 30s", modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        Spacer(Modifier.height(16.dp))
        // Speed control
        val currentSpeed = state.speed
        TextButton(onClick = {
            val idx = speedSteps.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.01f }
            val next = speedSteps[(idx + 1).coerceAtLeast(0) % speedSteps.size]
            vm.player.setSpeed(next)
        }) {
            Text(
                "Speed  ${"%.1f".format(currentSpeed)}x",
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ChaptersTab(chapters: List<Chapter>, positionMs: Long, onSeek: (Double) -> Unit) {
    if (chapters.isEmpty()) {
        EmptyState(title = "No chapters", subtitle = "This episode has no Podcasting 2.0 chapters.")
        return
    }
    val posSec = positionMs / 1000.0
    val currentIdx = chapters.indexOfLast { it.startSec <= posSec }
    LazyColumn(Modifier.fillMaxSize()) {
        items(chapters) { ch ->
            val isCurrent = chapters.indexOf(ch) == currentIdx
            Row(
                Modifier.fillMaxWidth().clickable { onSeek(ch.startSec) }
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.background
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    Format.clockSec(ch.startSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(64.dp)
                )
                Text(
                    ch.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun TranscriptTab(
    cues: List<TranscriptCue>,
    loading: Boolean,
    positionMs: Long,
    onSeek: (Double) -> Unit
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        cues.isEmpty() -> EmptyState(title = "No transcript", subtitle = "This episode has no transcript.")
        else -> {
            val posSec = positionMs / 1000.0
            val hasTimes = cues.any { it.endSec > 0.0 }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(cues) { cue ->
                    val isCurrent = hasTimes && posSec >= cue.startSec &&
                        (cue.endSec <= 0.0 || posSec < cue.endSec)
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = hasTimes) { onSeek(cue.startSec) }
                            .padding(vertical = 6.dp)
                    ) {
                        if (cue.speaker != null) {
                            Text(
                                cue.speaker,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            cue.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.alpha(if (!hasTimes || isCurrent) 1f else 0.55f)
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DetailsTab(episode: Episode?) {
    if (episode == null) {
        EmptyState(title = "Nothing playing", subtitle = "Start an episode to see details.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        if (episode.persons.isNotEmpty()) {
            item {
                Text("People", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            items(episode.persons) { person ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Artwork(person.img, modifier = Modifier.size(40.dp), corner = 20)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(person.name, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground)
                        val role = listOf(person.role, person.group)
                            .filter { it.isNotBlank() }.joinToString(" • ")
                        if (role.isNotBlank()) Text(
                            role, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        episode.value?.let { v ->
            item {
                Text("Value 4 Value", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Lightning recipients & splits. Sending sats (boosts) needs a connected wallet — display-only in v1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            items(v.recipients) { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text("${r.split}%", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        item {
            Text("About", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                stripTags(episode.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun stripTags(s: String): String =
    s.replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#39;", "'").replace("&quot;", "\"").trim()
