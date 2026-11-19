package com.aide.claudecast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
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
import com.aide.claudecast.data.Podcast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LibraryViewModel : ViewModel() {
    private val repo = ServiceLocator.repo
    val subscriptions: StateFlow<List<Podcast>> = repo.subscriptions

    private val _suggestions = MutableStateFlow<List<Podcast>>(emptyList())
    val suggestions: StateFlow<List<Podcast>> = _suggestions

    fun loadSuggestions() {
        if (_suggestions.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { repo.popular() }.onSuccess { _suggestions.value = it }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenPodcast: (String) -> Unit,
    vm: LibraryViewModel = viewModel(),
    searchVm: SearchViewModel = viewModel()
) {
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showResults by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(subs.isEmpty()) { if (subs.isEmpty()) vm.loadSuggestions() }
    // Search as you type, after a short pause.
    LaunchedEffect(query) {
        if (query.trim().length >= 3) {
            delay(600)
            showResults = true
            searchVm.search(query.trim())
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Podcasts", fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.primary
            )
        )
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) showResults = false
            },
            placeholder = { Text("Search podcasts") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = ""; showResults = false }) {
                    Icon(Icons.Rounded.Clear, "Clear")
                }
            },
            singleLine = true,
            keyboardActions = KeyboardActions(onSearch = {
                if (query.isNotBlank()) { showResults = true; searchVm.search(query.trim()) }
            }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (showResults && query.isNotBlank()) {
            SearchResults(searchState, onOpenPodcast)
        } else if (subs.isEmpty()) {
            if (suggestions.isEmpty()) {
                EmptyState(
                    title = "Your library is empty",
                    subtitle = "Search above to find and subscribe to any podcast. No account needed."
                )
            } else {
                Text(
                    "Popular now",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                )
                PodcastGrid(suggestions, onOpenPodcast)
            }
        } else {
            PodcastGrid(subs, onOpenPodcast)
        }
    }
}

@Composable
private fun PodcastGrid(items: List<Podcast>, onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(items, key = { it.feedUrl }) { p ->
            Column(
                modifier = Modifier.clickable { onOpen(p.feedUrl) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Artwork(p.artworkUrl, modifier = Modifier.fillMaxWidth().aspectRatio(1f), corner = 12)
                Text(
                    p.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
