package com.aide.claudecast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.aide.claudecast.ServiceLocator
import com.aide.claudecast.data.Movie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MoviesViewModel : ViewModel() {
    private val api = ServiceLocator.movies

    private val _movies = MutableStateFlow<List<Movie>>(emptyList())
    val movies: StateFlow<List<Movie>> = _movies.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private var text = ""
    private var genre = ""
    private var page = 1

    init { search("", "") }

    fun search(text: String, genre: String) {
        this.text = text
        this.genre = genre
        page = 1
        _movies.value = emptyList()
        fetch()
    }

    fun loadMore() {
        if (_loading.value) return
        page++
        fetch()
    }

    private fun fetch() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val got = api.search(text, genre, page)
                _movies.value = (_movies.value + got).distinctBy { it.id }
                _canLoadMore.value = got.size >= 50
                if (_movies.value.isEmpty()) _error.value = "No movies found."
            } catch (e: Exception) {
                _error.value = "Couldn't reach the Internet Archive. Check your connection."
            } finally {
                _loading.value = false
            }
        }
    }
}

private val movieGenres = listOf(
    "Western", "Film Noir", "Horror", "Science Fiction", "Comedy", "Crime", "Mystery",
    "Drama", "War", "Adventure", "Action", "Romance", "Musical", "Thriller", "Silent", "Hitchcock"
)

@Composable
fun MoviesTab(onOpenPlayer: () -> Unit, vm: MoviesViewModel = viewModel()) {
    val movies by vm.movies.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val canLoadMore by vm.canLoadMore.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Movie?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search movies, actors, directors") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = {
                        query = ""; vm.search("", genre)
                    }) { Icon(Icons.Rounded.Clear, "Clear") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query, genre) }),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = genre.isEmpty(), onClick = { genre = ""; vm.search(query, "") },
                        label = { Text("Most watched") })
                }
                items(movieGenres) { g ->
                    FilterChip(
                        selected = genre == g,
                        onClick = { genre = g; vm.search(query, g) },
                        label = { Text(g) }
                    )
                }
            }
        }
        items(movies, key = { it.id }) { m ->
            Column(Modifier.clickable { selected = m }) {
                Poster(m, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                Spacer(Modifier.height(4.dp))
                Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                if (m.year.isNotBlank()) Text(m.year, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    canLoadMore -> OutlinedButton(onClick = { vm.loadMore() }) { Text("Load more") }
                }
            }
        }
    }

    selected?.let { m -> MovieDialog(m, onDismiss = { selected = null }, onPlaying = {
        selected = null; onOpenPlayer()
    }) }
}

@Composable
private fun Poster(m: Movie, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SubcomposeAsyncImage(
            model = m.poster, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), loading = { fallback() }, error = { fallback() }
        )
    }
}

@Composable
private fun MovieDialog(m: Movie, onDismiss: () -> Unit, onPlaying: () -> Unit) {
    val scope = rememberCoroutineScope()
    val resume = remember(m.id) { ServiceLocator.movies.resumeMs(m.id) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun start(fromMs: Long) {
        busy = true
        problem = null
        scope.launch {
            try {
                val url = ServiceLocator.movies.streamUrl(m)
                ServiceLocator.player.playMovie(m, url, fromMs)
                onPlaying()
            } catch (e: Exception) {
                problem = "Couldn't find a playable copy of this movie."
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val meta = listOf(m.year, m.runtime).filter { it.isNotBlank() }.joinToString(" • ")
                if (meta.isNotBlank()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(m.description, style = MaterialTheme.typography.bodyMedium)
                }
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = { start(resume) }) {
                Text(if (resume > 60_000) "Resume at ${Format.clock(resume)}" else "Play")
            }
        },
        dismissButton = {
            if (resume > 60_000) TextButton(enabled = !busy, onClick = { start(0L) }) { Text("Start over") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
