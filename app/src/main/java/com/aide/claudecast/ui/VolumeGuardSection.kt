package com.aide.claudecast.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aide.claudecast.volumeguard.VolumeGuardPrefs
import com.aide.claudecast.volumeguard.VolumeGuardService

/** Settings section for the built-in Volume Guard. */
@Composable
fun VolumeGuardSection() {
    val context = LocalContext.current
    // Bumped on every resume so status reflects changes made in system settings.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }

    val granted = remember(tick) {
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, VolumeGuardService::class.java))
    }
    val unrestricted = remember(tick) {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }
    val enabled = remember(tick) { VolumeGuardPrefs.enabled(context) }
    val saves = remember(tick) { VolumeGuardPrefs.saves(context) }
    val log = remember(tick) { VolumeGuardPrefs.log(context) }

    Text("Volume Guard", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Text(
        "Taps Undo on Android's \"Volume lowered\" alert and puts your volume back. " +
            "Works for any app, not just ClaudeCast.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Guard on", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = enabled, onCheckedChange = {
            VolumeGuardPrefs.setEnabled(context, it); tick++
        })
    }

    Text(
        "Notification access: " + (if (granted) "on" else "OFF, needed") +
            "\nBattery: " + (if (unrestricted) "unrestricted" else "may be paused by Android") +
            "\nVolume drops caught: $saves",
        style = MaterialTheme.typography.bodyMedium,
        color = if (granted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) { Text("Notification access") }
        if (!unrestricted) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
            }) { Text("Battery") }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Activity log", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { VolumeGuardPrefs.clearLog(context); tick++ }) { Text("Clear") }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
            Text(
                log.ifBlank { "Nothing yet." },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
