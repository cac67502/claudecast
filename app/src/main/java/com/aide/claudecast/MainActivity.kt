package com.aide.claudecast

import android.Manifest
import android.os.Build
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aide.claudecast.playback.MediaKind
import com.aide.claudecast.ui.ClaudeCastRoot
import com.aide.claudecast.ui.PipState
import com.aide.claudecast.ui.theme.ClaudeCastTheme
import com.aide.claudecast.ui.theme.ThemePref

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val pref by ServiceLocator.settings.themePref
                .collectAsStateWithLifecycle(initialValue = ThemePref.Dark)
            ClaudeCastTheme(pref = pref) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ClaudeCastRoot()
                }
            }
        }
    }

    /** Pressing Home while TV is playing shrinks it to a small floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = ServiceLocator.player.state.value
        if (s.kind == MediaKind.Tv && s.isPlaying) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.active = isInPictureInPictureMode
    }
}
