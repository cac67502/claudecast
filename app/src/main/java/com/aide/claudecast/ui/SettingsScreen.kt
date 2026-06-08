package com.aide.claudecast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aide.claudecast.ServiceLocator
import com.aide.claudecast.ui.theme.ThemePref
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val settings = ServiceLocator.settings

    val speed: StateFlow<Float> get() = _speed
    private val _speed = MutableStateFlow(1.0f)
    val theme: StateFlow<ThemePref> get() = _theme
    private val _theme = MutableStateFlow(ThemePref.Dark)

    init {
        viewModelScope.launch { settings.defaultSpeed.collect { _speed.value = it } }
        viewModelScope.launch { settings.themePref.collect { _theme.value = it } }
    }

    fun setSpeed(s: Float) {
        viewModelScope.launch {
            settings.setSpeed(s)
            ServiceLocator.player.setDefaultSpeed(s)
        }
    }

    fun setTheme(p: ThemePref) {
        viewModelScope.launch { settings.setTheme(p) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val speed by vm.speed.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("Default playback speed", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f).forEach { s ->
                    FilterChip(
                        selected = kotlin.math.abs(s - speed) < 0.01f,
                        onClick = { vm.setSpeed(s) },
                        label = { Text("${"%.1f".format(s)}x") }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text("Theme", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePref.entries.forEach { p ->
                    FilterChip(
                        selected = theme == p,
                        onClick = { vm.setTheme(p) },
                        label = { Text(p.name) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text("About", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Discovery via the iTunes Search directory; episodes and all Podcasting 2.0 " +
                    "metadata are read straight from each show's RSS feed. No account or API key needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "ClaudeCast v1.0  •  Podcasting 2.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
