package com.music.vivi.desktop.presentation.components

import androidx.compose.ui.focus.FocusRequester
import kotlin.test.Test

class FocusRequesterSafeTest {

    @Test
    fun aDetachedRequesterDoesNotCrashTheWindow() {
        // Before the guard this call threw "FocusRequester is not initialized"
        // when the target was already out of composition (cache cleanup,
        // dialog closed, player returning early).
        FocusRequester().requestFocusSafely()
    }
}
