package com.aide.claudecast.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.aide.claudecast.ServiceLocator
import com.aide.claudecast.data.HeardSong
import com.aide.claudecast.data.Playlist
import com.aide.claudecast.data.Song
import com.aide.claudecast.data.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ======================= ViewModel ======================= */

class MusicViewModel : ViewModel() {
    private val radio = ServiceLocator.radio
    val store = ServiceLocator.music
    val library = ServiceLocator.library
    val player = ServiceLocator.player

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _resultsLabel = MutableStateFlow("Popular stations")
    val resultsLabel: StateFlow<String> = _resultsLabel.asStateFlow()

    init {
        searchStations(tag = "", name = "", label = "Popular stations")
        viewModelScope.launch { library.refresh() }
    }

    fun searchStations(name: String = "", tag: String = "", label: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _resultsLabel.value = label
            try {
                _stations.value = radio.search(name = name, tag = tag)
                if (_stations.value.isEmpty()) _error.value = "No stations found."
            } catch (e: Exception) {
                _error.value = "Couldn't reach the radio directory. Check your connection."
            } finally {
                _loading.value = false
            }
        }
    }

    fun refreshLibrary() { viewModelScope.launch { library.refresh() } }

    fun deleteRecording(song: Song) { viewModelScope.launch { library.deleteRecording(song) } }
}

private val genres = listOf(
    "rock", "classic rock", "country", "pop", "hits", "80s", "90s", "oldies", "hip hop",
    "r&b", "jazz", "blues", "metal", "alternative", "dance", "electronic", "christian",
    "reggae", "latin", "classical", "chillout", "talk", "news", "sports"
)

/* ======================= Screen ======================= */

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(onOpenPlayer: () -> Unit, vm: MusicViewModel = viewModel()) {
    val tabs = listOf("Radio", "Songs", "Artists", "Albums", "Playlists", "Heard")
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Music", fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> RadioTab(vm, onOpenPlayer)
            1 -> LibraryGate(vm) { SongsTab(vm, onOpenPlayer) }
            2 -> LibraryGate(vm) { GroupTab(vm, byArtist = true, onOpenPlayer = onOpenPlayer) }
            3 -> LibraryGate(vm) { GroupTab(vm, byArtist = false, onOpenPlayer = onOpenPlayer) }
            4 -> LibraryGate(vm) { PlaylistsTab(vm, onOpenPlayer) }
            5 -> HeardTab(vm)
        }
    }
}

/* ======================= Radio ======================= */

@Composable
private fun RadioTab(vm: MusicViewModel, onOpenPlayer: () -> Unit) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val favorites by vm.store.favorites.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val label by vm.resultsLabel.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }

    val play: (Station) -> Unit = { vm.player.playStation(it); onOpenPlayer() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search stations") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Clear") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    genre = ""
                    vm.searchStations(name = query, label = "Results for \"$query\"")
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(genres) { g ->
                    FilterChip(
                        selected = genre == g,
                        onClick = {
                            genre = g
                            vm.searchStations(tag = g, label = g.replaceFirstChar { it.uppercase() })
                        },
                        label = { Text(g) }
                    )
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionHeader("Favorites") }
            items(favorites, key = { "fav-" + it.uuid }) { st ->
                StationRow(st, isFavorite = true, onClick = { play(st) }, onStar = { vm.store.toggleFavorite(st) })
            }
        }
        item { SectionHeader(label) }
        when {
            loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> item {
                Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> items(stations, key = { it.uuid }) { st ->
                StationRow(
                    st,
                    isFavorite = favorites.any { it.uuid == st.uuid },
                    onClick = { play(st) },
                    onStar = { vm.store.toggleFavorite(st) }
                )
            }
        }
    }
}

@Composable
private fun StationRow(st: Station, isFavorite: Boolean, onClick: () -> Unit, onStar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StationArt(st.favicon, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(
                st.tags.split(',').take(3).joinToString(", ").ifBlank { null },
                st.country.ifBlank { null },
                if (st.bitrate > 0) "${st.bitrate}k" else null
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
fun StationArt(url: String?, modifier: Modifier = Modifier, corner: Int = 10) {
    Box(
        modifier.clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(Icons.Rounded.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize().padding(10.dp))
        }
        if (url.isNullOrBlank()) fallback()
        else SubcomposeAsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { fallback() }, error = { fallback() }
        )
    }
}

@Composable
fun SongArt(modifier: Modifier = Modifier, corner: Int = 10) {
    Box(
        modifier.clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxSize().padding(10.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

/* ======================= Library ======================= */

/** Asks for permission to read music on the phone; recordings show either way. */
@Composable
private fun LibraryGate(vm: MusicViewModel, content: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(vm.library.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        vm.refreshLibrary()
    }
    Column(Modifier.fillMaxSize()) {
        if (!granted) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Allow access to show all the music on your phone. Your radio recordings show either way.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { launcher.launch(vm.library.readPermission) }) { Text("Allow") }
            }
        }
        content()
    }
}

@Composable
private fun SongsTab(vm: MusicViewModel, onOpenPlayer: () -> Unit) {
    val songs by vm.library.songs.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = remember(songs, filter) {
        if (filter.isBlank()) songs
        else songs.filter { it.title.contains(filter, true) || it.artist.contains(filter, true) }
    }
    SongList(
        songs = shown, vm = vm, onOpenPlayer = onOpenPlayer,
        header = {
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                placeholder = { Text("Filter songs") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        },
        emptyText = "No music yet. Record songs from the Radio tab, or copy music files onto your phone."
    )
}

@Composable
private fun GroupTab(vm: MusicViewModel, byArtist: Boolean, onOpenPlayer: () -> Unit) {
    val songs by vm.library.songs.collectAsStateWithLifecycle()
    var open by rememberSaveable(byArtist) { mutableStateOf<String?>(null) }
    val groups = remember(songs, byArtist) {
        songs.groupBy { if (byArtist) it.artist else it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    val current = open
    if (current != null) {
        val list = groups[current].orEmpty()
        SongList(
            songs = list, vm = vm, onOpenPlayer = onOpenPlayer,
            header = { BackHeader(current) { open = null } },
            emptyText = "Nothing here."
        )
        return
    }
    if (groups.isEmpty()) {
        EmptyText("No music yet.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups.keys.toList(), key = { it }) { name ->
            val count = groups[name]?.size ?: 0
            Row(
                Modifier.fillMaxWidth().clickable { open = name }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArt(Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$count song" + if (count == 1) "" else "s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(vm: MusicViewModel, onOpenPlayer: () -> Unit) {
    val playlists by vm.store.playlists.collectAsStateWithLifecycle()
    val songs by vm.library.songs.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val open = playlists.firstOrNull { it.id == openId }
    if (open != null) {
        val byUri = songs.associateBy { it.uri }
        val list = open.songUris.mapNotNull { byUri[it] }
        SongList(
            songs = list, vm = vm, onOpenPlayer = onOpenPlayer,
            header = {
                BackHeader(open.name, onDelete = {
                    vm.store.deletePlaylist(open.id); openId = null
                }) { openId = null }
            },
            emptyText = "Empty playlist. Add songs with the ⋮ menu on any song.",
            playlist = open
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedButton(onClick = { creating = true }, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist")
            }
        }
        if (playlists.isEmpty()) item { EmptyText("No playlists yet.") }
        items(playlists, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { openId = p.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name)
                    Text("${p.songUris.size} songs", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (creating) NameDialog("New playlist", onDismiss = { creating = false }) { name ->
        vm.store.createPlaylist(name); creating = false
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    vm: MusicViewModel,
    onOpenPlayer: () -> Unit,
    header: @Composable () -> Unit,
    emptyText: String,
    playlist: Playlist? = null
) {
    var addTarget by remember { mutableStateOf<Song?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { header() }
        if (songs.isEmpty()) {
            item { EmptyText(emptyText) }
        } else {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.player.playSongs(songs, 0, shuffle = true); onOpenPlayer() }) {
                        Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                    }
                    OutlinedButton(onClick = { vm.player.playSongs(songs, 0); onOpenPlayer() }) { Text("Play all") }
                }
            }
            items(songs.size) { i ->
                val s = songs[i]
                SongRow(
                    s,
                    onClick = { vm.player.playSongs(songs, i); onOpenPlayer() },
                    onAddToPlaylist = { addTarget = s },
                    onRemoveFromPlaylist = playlist?.let { p -> { vm.store.removeFromPlaylist(p.id, s.uri) } },
                    onDelete = if (s.isRecording) ({ vm.deleteRecording(s) }) else null
                )
            }
        }
    }
    addTarget?.let { song ->
        AddToPlaylistDialog(vm, song) { addTarget = null }
    }
}

@Composable
private fun SongRow(
    s: Song,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongArt(Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                s.artist,
                if (s.durationMs > 0) Format.clock(s.durationMs) else null,
                if (s.isRecording) "recorded" else null
            ).joinToString(" • ")
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                    onClick = { menu = false; onAddToPlaylist() }
                )
                if (onRemoveFromPlaylist != null) DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    leadingIcon = { Icon(Icons.Rounded.Clear, null) },
                    onClick = { menu = false; onRemoveFromPlaylist() }
                )
                if (onDelete != null) DropdownMenuItem(
                    text = { Text("Delete recording") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menu = false; confirmDelete = true }
                )
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text = { Text("\"${s.title}\" will be removed from your phone. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AddToPlaylistDialog(vm: MusicViewModel, song: Song, onDone: () -> Unit) {
    val playlists by vm.store.playlists.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog("New playlist", onDismiss = onDone) { name ->
            val id = vm.store.createPlaylist(name)
            vm.store.addToPlaylist(id, song.uri)
            onDone()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add to playlist") },
        text = {
            Column {
                playlists.forEach { p ->
                    Text(
                        p.name,
                        Modifier.fillMaxWidth().clickable { vm.store.addToPlaylist(p.id, song.uri); onDone() }
                            .padding(vertical = 12.dp)
                    )
                }
                TextButton(onClick = { naming = true }) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}

@Composable
private fun NameDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BackHeader(title: String, onDelete: (() -> Unit)? = null, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete playlist") }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/* ======================= Heard on radio ======================= */

@Composable
private fun HeardTab(vm: MusicViewModel) {
    val heard by vm.store.heard.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("MMM d", Locale.US) }
    if (heard.isEmpty()) {
        EmptyText("Songs you heart while listening to the radio show up here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(heard, key = { it.artist + "|" + it.title + "|" + it.heardAt }) { h: HeardSong ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(h.artist, h.station, fmt.format(Date(h.heardAt)))
                            .filter { it.isNotBlank() }.joinToString(" • "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.store.removeHeard(h) }) { Icon(Icons.Rounded.Delete, "Remove") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
