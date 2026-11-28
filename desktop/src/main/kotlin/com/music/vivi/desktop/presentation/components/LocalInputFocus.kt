package com.music.vivi.desktop.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged

class InputFocusTracker(private val onActiveChange: (Boolean) -> Unit) {
    private var activeCount = 0

    fun registerActive() {
        activeCount++
        if (activeCount == 1) {
            onActiveChange(true)
        }
    }

    fun unregisterActive() {
        if (activeCount > 0) {
            activeCount--
            if (activeCount == 0) {
                onActiveChange(false)
            }
        }
    }

    fun onFocusChanged(isFocused: Boolean) {
        if (isFocused) {
            registerActive()
        } else {
            unregisterActive()
        }
    }
}

val LocalInputFocusTracker = compositionLocalOf { InputFocusTracker {} }

/**
 * A focus request that is safe when the target left composition between the
 * click/effect and the request. A detached [FocusRequester] throws
 * `IllegalStateException`; losing focus is never a reason to crash the window.
 */
fun FocusRequester.requestFocusSafely() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // The field is not mounted (dialog closed, screen replaced, startup).
    }
}

@Composable
fun Modifier.trackInputFocus(): Modifier {
    val tracker = LocalInputFocusTracker.current
    return this.then(
        remember(tracker) {
            var wasFocused = false
            Modifier.onFocusChanged { state ->
                if (state.isFocused != wasFocused) {
                    wasFocused = state.isFocused
                    if (wasFocused) {
                        tracker.registerActive()
                    } else {
                        tracker.unregisterActive()
                    }
                }
            }
        }
    )
}

@Composable
fun TrackDialogInput(isInputOpen: Boolean = true) {
    val tracker = LocalInputFocusTracker.current
    DisposableEffect(isInputOpen, tracker) {
        if (isInputOpen) {
            tracker.registerActive()
        }
        onDispose {
            if (isInputOpen) {
                tracker.unregisterActive()
            }
        }
    }
}
