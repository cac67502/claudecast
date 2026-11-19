package com.aide.claudecast.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.aide.claudecast.TvMode

/**
 * On a TV, a text field should not grab the keyboard just because the remote lands on it,
 * or Up and Down can't move past it. The keyboard opens only when OK is pressed.
 * On a phone [readOnly] is always false and nothing changes.
 */
class TvTyping(val isTv: Boolean, private val keyboard: SoftwareKeyboardController?) {
    var editing by mutableStateOf(!isTv)
    val readOnly: Boolean get() = isTv && !editing

    /** Call when a search is submitted: closes the keyboard and frees the remote again. */
    fun finish() {
        if (!isTv) return
        editing = false
        keyboard?.hide()
    }
}

@Composable
fun rememberTvTyping(): TvTyping {
    val isTv = TvMode.isTv(LocalContext.current)
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(isTv) { TvTyping(isTv, keyboard) }
}

@Composable
fun Modifier.tvTyping(t: TvTyping): Modifier {
    if (!t.isTv) return this
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(t.editing) { if (t.editing) keyboard?.show() }
    return this
        .onFocusChanged { if (!it.hasFocus) t.editing = false }
        .onPreviewKeyEvent { ev ->
            val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
            if (!t.editing && ok) {
                if (ev.type == KeyEventType.KeyUp) t.editing = true
                true
            } else false
        }
}
