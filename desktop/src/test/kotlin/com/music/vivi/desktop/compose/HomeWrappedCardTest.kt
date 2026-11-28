package com.music.vivi.desktop.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import com.music.vivi.desktop.presentation.screens.HomeScreen
import com.music.vivi.desktop.ui.theme.PixelPlayerTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HomeWrappedCardTest {

    @Test
    fun wrappedCardAppearsOnlyWhileTheSettingIsOn() {
        val showWrapped = mutableStateOf(true)
        runComposeUiTest {
            setContent {
                PixelPlayerTheme {
                    HomeScreen(
                        songs = listOf(
                            DesktopUiHarness.song(
                                id = "wrapped-home",
                                title = "Wrapped Home Track",
                                lastPlayedTimestamp = System.currentTimeMillis(),
                                path = "C:/fixture/wrapped-home.mp3"
                            )
                        ),
                        favoriteSongs = emptyList(),
                        showWrappedOnHome = showWrapped.value,
                        currentSong = null,
                        isPlaying = false,
                        onSongSelect = {},
                        onShuffleAll = {},
                        onNavigateToLibrary = {}
                    )
                }
            }

            onNode(hasScrollAction()).performScrollToNode(hasText("VIVI Wrapped"))
            onNodeWithText("VIVI Wrapped").assertExists()
            runOnUiThread { showWrapped.value = false }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("VIVI Wrapped").fetchSemanticsNodes().isEmpty()
            }
        }
    }
}
