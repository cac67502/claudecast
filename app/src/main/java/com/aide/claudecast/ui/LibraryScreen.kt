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
import androidx.compose.material3.Button
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
    onGoToSearch: () -> Unit,
    vm: LibraryViewModel = viewModel()
) {
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()

    LaunchedEffect(subs.isEmpty()) { if (subs.isEmpty()) vm.loadSuggestions() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Podcasts", fontWeight = FontWeight.SemiBold) },
            actions = {
                androidx.compose.material3.IconButton(onClick = onGoToSearch) {
                    androidx.compose.material3.Icon(
                        Icons.Rounded.Search,
                        contentDescription = "Search podcasts"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.primary
            )
        )
        if (subs.isEmpty()) {
            if (suggestions.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    EmptyState(
                        title = "Your library is empty",
                        subtitle = "Search to find and subscribe to any podcast. No account needed.",
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onGoToSearch,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) { Text("Find podcasts") }
                }
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
