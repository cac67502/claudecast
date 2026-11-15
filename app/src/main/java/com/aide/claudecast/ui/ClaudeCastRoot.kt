package com.aide.claudecast.ui

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aide.claudecast.ServiceLocator
import com.aide.claudecast.playback.MediaKind

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Library : Dest("library", "Library", Icons.Rounded.LibraryBooks)
    data object Music : Dest("music", "Music", Icons.Rounded.MusicNote)
    data object Search : Dest("search", "Search", Icons.Rounded.Search)
    data object Settings : Dest("settings", "Settings", Icons.Rounded.Settings)
}

private val bottomDests = listOf(Dest.Library, Dest.Music, Dest.Search, Dest.Settings)

private fun encodeArg(s: String): String =
    Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun decodeArg(s: String): String =
    runCatching { String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }
        .getOrDefault("")

@Composable
fun ClaudeCastRoot() {
    val nav = rememberNavController()
    val player = ServiceLocator.player
    val playerState by player.state.collectAsStateWithLifecycle()

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBars = currentRoute in setOf(Dest.Library.route, Dest.Music.route, Dest.Search.route, Dest.Settings.route)

    val openPodcast: (String) -> Unit = { feedUrl -> nav.navigate("podcast/${encodeArg(feedUrl)}") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBars) {
                Column {
                    if (playerState.hasMedia) {
                        val progress = if (playerState.durationMs > 0)
                            playerState.positionMs.toFloat() / playerState.durationMs else 0f
                        MiniPlayer(
                            title = playerState.title,
                            podcast = playerState.podcastTitle,
                            artworkUrl = playerState.artworkUrl,
                            isPlaying = playerState.isPlaying,
                            progress = progress,
                            onToggle = { player.togglePlayPause() },
                            onClick = { nav.navigate("player") }
                        )
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        bottomDests.forEach { dest ->
                            val selected = backStack?.destination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(dest.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Library.route,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            composable(Dest.Library.route) {
                LibraryScreen(onOpenPodcast = openPodcast, onGoToSearch = { nav.navigate(Dest.Search.route) })
            }
            composable(Dest.Search.route) {
                SearchScreen(onOpenPodcast = openPodcast)
            }
            composable(Dest.Music.route) {
                MusicScreen(onOpenPlayer = { nav.navigate("player") })
            }
            composable(Dest.Settings.route) { SettingsScreen() }
            composable("podcast/{feedArg}") { entry ->
                val feedUrl = decodeArg(entry.arguments?.getString("feedArg").orEmpty())
                PodcastDetailScreen(
                    feedUrl = feedUrl,
                    onBack = { nav.popBackStack() },
                    onOpenPlayer = { nav.navigate("player") }
                )
            }
            composable("player") {
                if (playerState.kind == MediaKind.Music || playerState.kind == MediaKind.Radio)
                    MusicPlayerScreen(onBack = { nav.popBackStack() })
                else
                    PlayerScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
