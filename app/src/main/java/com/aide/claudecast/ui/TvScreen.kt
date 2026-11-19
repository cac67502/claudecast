package com.aide.claudecast.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import com.aide.claudecast.ServiceLocator
import com.aide.claudecast.data.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* ======================= ViewModel ======================= */

class TvViewModel : ViewModel() {
    private val api = ServiceLocator.tv
    val store = ServiceLocator.music

    private val _all = MutableStateFlow<List<Channel>>(emptyList())
    val all: StateFlow<List<Channel>> = _all.asStateFlow()
    private val _local = MutableStateFlow<List<Channel>?>(null)
    val local: StateFlow<List<Channel>?> = _local.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { load(false) }

    fun load(force: Boolean) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _all.value = api.allChannels(force)
            } catch (e: Exception) {
                _error.value = "Couldn't load the channel list. Check your connection."
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadLocal(code: String) {
        viewModelScope.launch {
            _local.value = null
            _loading.value = true
            try {
                _local.value = api.localChannels(code)
            } catch (e: Exception) {
                _local.value = emptyList()
                _error.value = "Couldn't load local channels."
            } finally {
                _loading.value = false
            }
        }
    }
}

private const val TV_LOCAL = "@local"
private const val TV_ALL = ""
private val tvCategories = listOf(
    "News", "Movies", "Series", "Comedy", "Classic", "Sports", "Entertainment", "Kids",
    "Animation", "Documentary", "Music", "Outdoor", "Cooking", "Lifestyle", "Travel",
    "Education", "Science", "Business", "Weather", "Religious", "Legislative"
)

/* ======================= Channel list ======================= */

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TvScreen(onOpenPlayer: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val liveVm: TvViewModel = viewModel()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("TV", fontWeight = FontWeight.SemiBold) },
            actions = {
                if (tab == 0) IconButton(onClick = { liveVm.load(true) }) { Icon(Icons.Rounded.Refresh, "Refresh list") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        androidx.compose.material3.TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Live TV") })
            androidx.compose.material3.Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Movies") })
        }
        if (tab == 0) LiveTvTab(onOpenPlayer, liveVm) else MoviesTab(onOpenPlayer)
    }
}

@Composable
private fun LiveTvTab(onOpenPlayer: () -> Unit, vm: TvViewModel) {
    val all by vm.all.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val favorites by vm.store.tvFavorites.collectAsStateWithLifecycle()
    val localState by vm.store.localState.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(TV_ALL) }
    var pickingState by remember { mutableStateOf(false) }

    val showLocal: (String) -> Unit = { code -> category = TV_LOCAL; vm.loadLocal(code) }
    val play: (Channel) -> Unit = { ServiceLocator.player.playChannel(it); onOpenPlayer() }

    val shown = remember(all, local, query, category) {
        val base = when (category) {
            TV_LOCAL -> local.orEmpty()
            TV_ALL -> all
            else -> all.filter { ch -> ch.categories.any { it.equals(category, true) } }
        }
        if (query.isBlank()) base else base.filter { it.name.contains(query.trim(), true) }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search channels") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Clear") }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(selected = category == TV_ALL, onClick = { category = TV_ALL }, label = { Text("All") })
                    }
                    item {
                        FilterChip(
                            selected = category == TV_LOCAL,
                            onClick = { if (localState.isBlank()) pickingState = true else showLocal(localState) },
                            leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) },
                            label = { Text(if (localState.isBlank()) "Local" else "Local ($localState)") }
                        )
                    }
                    items(tvCategories) { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                }
            }
            if (category == TV_LOCAL) item {
                TextButton(onClick = { pickingState = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Change state")
                }
            }
            if (favorites.isNotEmpty() && category == TV_ALL && query.isBlank()) {
                item { TvHeader("Favorites") }
                items(favorites, key = { "fav-" + it.id }) { ch ->
                    ChannelRow(ch, true, onClick = { play(ch) }, onStar = { vm.store.toggleTvFavorite(ch) })
                }
            }
            item {
                TvHeader(
                    when (category) {
                        TV_LOCAL -> "Local: " + (usStates[localState] ?: localState)
                        TV_ALL -> "All channels (${shown.size})"
                        else -> "$category (${shown.size})"
                    }
                )
            }
            when {
                loading && shown.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null && shown.isEmpty() -> item { TvEmpty(error!!) }
                shown.isEmpty() && category == TV_LOCAL -> item {
                    TvEmpty(
                        "No free local channels are listed for this state. Local stations' own " +
                            "newscasts sometimes show up under News; try searching their call letters."
                    )
                }
                shown.isEmpty() -> item { TvEmpty("No channels found.") }
                else -> items(shown, key = { it.id }) { ch ->
                    ChannelRow(
                        ch, favorites.any { it.id == ch.id },
                        onClick = { play(ch) }, onStar = { vm.store.toggleTvFavorite(ch) }
                    )
                }
            }
        }
    }

    if (pickingState) {
        AlertDialog(
            onDismissRequest = { pickingState = false },
            title = { Text("Your state") },
            text = {
                LazyColumn(Modifier.height(400.dp)) {
                    items(usStates.entries.toList(), key = { it.key }) { (code, name) ->
                        Text(
                            name,
                            color = if (code == localState) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable {
                                pickingState = false
                                vm.store.setLocalState(code)
                                showLocal(code)
                            }.padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingState = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ChannelRow(ch: Channel, isFavorite: Boolean, onClick: () -> Unit, onStar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogo(ch.logo, Modifier.size(width = 64.dp, height = 40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(
                ch.categories.take(2).joinToString(", ").ifBlank { null },
                if (ch.part24) "part-time" else null
            ).joinToString(" • ")
            if (sub.isNotBlank()) Text(
                sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onStar) {
            Icon(
                if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChannelLogo(url: String?, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(Icons.Rounded.LiveTv, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp))
        }
        if (url.isNullOrBlank()) fallback()
        else SubcomposeAsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            loading = { fallback() }, error = { fallback() }
        )
    }
}

@Composable
private fun TvHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun TvEmpty(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/* ======================= Video player ======================= */

/** The video itself. Used on the TV player screen and in picture-in-picture. */
@Composable
fun TvVideo(modifier: Modifier = Modifier, showControls: Boolean = true) {
    val player = ServiceLocator.player.videoPlayer
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowRewindButton(false)
                setShowFastForwardButton(false)
                setBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
                useController = showControls
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.useController = showControls
        },
        onRelease = { it.player = null },
        modifier = modifier.background(Color.Black)
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TvPlayerScreen(onBack: () -> Unit) {
    val channel by ServiceLocator.player.currentChannel.collectAsStateWithLifecycle()
    val movie by ServiceLocator.player.currentMovie.collectAsStateWithLifecycle()
    val episode by ServiceLocator.player.currentEpisode.collectAsStateWithLifecycle()
    val favorites by ServiceLocator.music.tvFavorites.collectAsStateWithLifecycle()
    val isTv = com.aide.claudecast.TvMode.isTv(LocalContext.current)
    // On a TV the picture fills the screen from the start; Back leaves the player.
    var fullscreen by rememberSaveable { mutableStateOf(isTv) }
    val activity = LocalContext.current as? Activity

    DisposableEffect(fullscreen) {
        val window = activity?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null && fullscreen) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    if (fullscreen) {
        androidx.activity.compose.BackHandler { if (isTv) onBack() else fullscreen = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            TvVideo(Modifier.fillMaxSize())
            // On a TV, Back on the remote leaves the player, so no on-screen button is needed.
            if (!isTv) IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Rounded.FullscreenExit, "Exit full screen", tint = Color.White)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(movie?.title ?: channel?.name ?: episode?.title ?: "TV", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                channel?.let { ch ->
                    val fav = favorites.any { it.id == ch.id }
                    IconButton(onClick = { ServiceLocator.music.toggleTvFavorite(ch) }) {
                        Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite",
                            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!isTv) CastButton()
                IconButton(onClick = { fullscreen = true }) { Icon(Icons.Rounded.Fullscreen, "Full screen") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        TvVideo(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            channel?.let { ch ->
                if (ch.categories.isNotEmpty()) Text(
                    ch.categories.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ch.part24) Text(
                    "This channel is only on the air part of the day. If it's dark, try again later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            movie?.let { m ->
                if (m.year.isNotBlank() || m.runtime.isNotBlank()) Text(
                    listOf(m.year, m.runtime).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (m.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(m.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Tip: press Home while a channel is playing to keep watching in a small window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
