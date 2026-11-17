package com.aide.claudecast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aide.claudecast.ServiceLocator
import kotlinx.coroutines.launch

/** Cast icon for player screens. Highlighted while something is on the TV. */
@Composable
fun CastButton() {
    val cast by ServiceLocator.caster.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        if (cast.device != null) Icon(Icons.Rounded.CastConnected, "Casting", tint = MaterialTheme.colorScheme.primary)
        else Icon(Icons.Rounded.Cast, "Cast to TV")
    }
    if (open) CastDialog(onDismiss = { open = false })
}

@Composable
private fun CastDialog(onDismiss: () -> Unit) {
    val caster = ServiceLocator.caster
    val player = ServiceLocator.player
    val cast by caster.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (cast.device == null) caster.scan() }

    val device = cast.device
    if (device != null) {
        // Casting: remote-control view.
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.CastConnected, null) },
            title = { Text("Playing on ${device.name}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(cast.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    if (!cast.isLive && cast.durationMs > 0) {
                        var drag by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = drag ?: (cast.positionMs.toFloat() / cast.durationMs).coerceIn(0f, 1f),
                            onValueChange = { drag = it },
                            onValueChangeFinished = {
                                drag?.let { caster.seekTo((it * cast.durationMs).toLong()) }
                                drag = null
                            }
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(Format.clock(cast.positionMs), style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.weight(1f))
                            Text(Format.clock(cast.durationMs), style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (cast.isLive) {
                        Text("LIVE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledIconButton(onClick = { caster.togglePlay() }, modifier = Modifier.size(56.dp)) {
                        Icon(if (cast.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (cast.playing) "Pause" else "Play")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Use the TV remote for volume.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CastLog(cast.log)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val live = cast.isLive
                        val pos = caster.stop()
                        player.resumeAfterCast(pos, live)
                        onDismiss()
                    }
                }) { Text("Stop casting") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
        return
    }

    // Not casting: pick a TV.
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Cast, null) },
        title = { Text("Cast to TV") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                cast.devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            val item = player.castItem()
                            if (item == null) {
                                problem = "Songs stored on your phone can't be cast. Radio, podcasts, TV and movies can."
                                return@clickable
                            }
                            busy = true
                            problem = null
                            scope.launch {
                                val ok = caster.start(d, item)
                                busy = false
                                if (ok) { player.pause(); onDismiss() }
                            }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Tv, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(d.name)
                            if (d.model.isNotBlank()) Text(d.model, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (cast.scanning || busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (busy) "Starting on the TV…" else "Looking for TVs on your Wi-Fi…")
                    }
                }
                val msg = problem ?: cast.message
                if (msg.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                CastLog(cast.log)
            }
        },
        confirmButton = {
            TextButton(enabled = !cast.scanning, onClick = { caster.scan() }) { Text("Search again") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Collapsible step-by-step log, for troubleshooting a TV that won't play something. */
@Composable
private fun CastLog(lines: List<String>) {
    if (lines.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Show details") }
    if (open) {
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    lines.joinToString("\n"),
                    modifier = Modifier.heightIn(max = 220.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(8.dp),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            }
        }
    }
}
