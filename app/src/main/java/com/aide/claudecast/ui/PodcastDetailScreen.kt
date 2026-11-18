package com.aide.claudecast.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aide.claudecast.ServiceLocator
import com.aide.claudecast.data.Episode
import com.aide.claudecast.data.LiveItem
import com.aide.claudecast.data.Podcast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val podcast: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val liveItems: List<LiveItem> = emptyList(),
    val subscribed: Boolean = false,
    val error: String? = null
)

class PodcastDetailViewModel : ViewModel() {
    private val repo = ServiceLocator.repo
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state

    fun load(feedUrl: String) {
        _state.value = DetailUiState(loading = true)
        viewModelScope.launch {
            runCatching {
                // Always re-read the feed on open: live status changes by the minute.
                val eps = repo.episodes(feedUrl, forceRefresh = true)
                val pod = repo.podcast(feedUrl)
                _state.value = DetailUiState(
                    loading = false,
                    podcast = pod,
                    episodes = eps,
                    liveItems = repo.liveItems(feedUrl),
                    subscribed = repo.isSubscribed(feedUrl)
                )
            }.onFailure {
                _state.value = DetailUiState(loading = false, error = it.message ?: "Failed to load feed")
            }
        }
    }

    fun toggleSubscribe() {
        val pod = _state.value.podcast ?: return
        viewModelScope.launch {
            if (_state.value.subscribed) repo.unsubscribe(pod.feedUrl)
            else repo.subscribe(pod)
            _state.value = _state.value.copy(subscribed = repo.isSubscribed(pod.feedUrl))
        }
    }

    fun playFrom(index: Int) =
        ServiceLocator.player.playQueue(_state.value.episodes, index)

    fun playLive(item: LiveItem) {
        val pod = _state.value.podcast
        ServiceLocator.player.play(
            item.toEpisode(pod?.feedUrl.orEmpty(), pod?.title.orEmpty(), pod?.artworkUrl)
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    feedUrl: String,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    vm: PodcastDetailViewModel = viewModel()
) {
    LaunchedEffect(feedUrl) { vm.load(feedUrl) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    state.podcast?.title ?: "Podcast",
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            },
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

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> EmptyState(title = "Couldn't load", subtitle = state.error!!)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    val p = state.podcast
                    Row(Modifier.padding(16.dp)) {
                        Artwork(p?.artworkUrl, modifier = Modifier.size(110.dp), corner = 14)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p?.title.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                p?.author.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            if (state.subscribed) {
                                OutlinedButton(onClick = { vm.toggleSubscribe() }) {
                                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Subscribed")
                                }
                            } else {
                                Button(
                                    onClick = { vm.toggleSubscribe() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) { Text("Subscribe") }
                            }
                        }
                    }
                }
                val nowSec = System.currentTimeMillis() / 1000
                items(state.liveItems.filter { it.isLive(nowSec) || it.isUpcoming(nowSec) }, key = { it.id }) { li ->
                    LiveBanner(li, nowSec, onPlay = { vm.playLive(li); onOpenPlayer() })
                }
                itemsIndexed(state.episodes, key = { _, it -> it.id }) { index, ep ->
                    EpisodeRow(ep = ep, onPlay = { vm.playFrom(index); onOpenPlayer() })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** A live show: red LIVE badge + Listen live when on the air, or its start time when scheduled. */
@Composable
private fun LiveBanner(item: LiveItem, nowSec: Long, onPlay: () -> Unit) {
    val onAir = item.isLive(nowSec)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val label = if (onAir) "● LIVE NOW" else "Live show, starts " +
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(item.startSec * 1000))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (onAir) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (onAir) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = onPlay) { Text("Listen live") }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            val meta = buildString {
                append(Format.date(ep.datePublished))
                if (ep.durationSec > 0) append("  •  ${Format.duration(ep.durationSec)}")
            }
            Text(
                meta, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                ep.title, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ep.chaptersUrl != null) Pc20Tag("Chapters")
                if (ep.transcripts.isNotEmpty()) Pc20Tag("Transcript")
                if (ep.value != null) Pc20Tag("V4V")
            }
        }
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Rounded.PlayCircle, contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun Pc20Tag(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp)
    )
}
