package com.musicdl.android

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.test.platform.app.InstrumentationRegistry
import com.musicdl.core.model.SongInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerCardAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    private fun openPlayer(onClosed: () -> Unit = {}) {
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            MusicdlTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box {
                        Text("Библиотека под плеером")
                        if (visible) PlayerCardOverlay(onClosed = { visible = false; onClosed() }) { close ->
                            FullPlayer(MusicUiState(player = PlayerUiState(
                                song = SongInfo(songName = "Карточка"), durationMs = 180_000L)),
                                MutableStateFlow(0f), false, close, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},  {}, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun closeButtonKeepsCardAliveUntilItShrinksAndSlidesAway() {
        var closed = 0
        openPlayer { closed++ }
        val card = compose.onNodeWithTag("player-card")
        val expanded = card.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Свернуть плеер").performClick()
        compose.mainClock.advanceTimeBy(144)
        val moving = card.fetchSemanticsNode().boundsInRoot
        assertTrue("Card must move down: $expanded -> $moving", moving.top > expanded.top)
        assertTrue("Card must shrink: $expanded -> $moving", moving.width < expanded.width)
        assertEquals(0, closed)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        card.assertDoesNotExist()
        compose.onNodeWithText("Библиотека под плеером").assertIsDisplayed()
        assertEquals(1, closed)
    }

    @Test fun shortDragReturnsToPlaceAndLongDragDismisses() {
        var closed = false
        openPlayer { closed = true }
        val card = compose.onNodeWithTag("player-card")
        val expanded = card.fetchSemanticsNode().boundsInRoot
        card.performTouchInput {
            down(Offset(width * 0.5f, height * 0.15f))
            moveBy(Offset(0f, height * 0.08f))
            advanceEventTime(600)
        }
        val dragging = card.fetchSemanticsNode().boundsInRoot
        assertTrue("Card must follow the finger: $expanded -> $dragging", dragging.top > expanded.top)
        card.performTouchInput { up() }
        compose.waitForIdle()
        assertFalse(closed)
        assertEquals(expanded.top, card.fetchSemanticsNode().boundsInRoot.top, 2f)
        card.performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.15f), Offset(width * 0.5f, height * 0.72f), durationMillis = 600)
        }
        compose.waitForIdle()
        card.assertDoesNotExist()
        assertTrue(closed)
    }

    @Test fun horizontalCoverGestureAndLyricsBackKeepTheirPriority() {
        openPlayer()
        val cover = compose.onNodeWithContentDescription("Обложка трека")
        val expanded = cover.fetchSemanticsNode().boundsInRoot
        cover.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(expanded.width * 0.25f, cover.fetchSemanticsNode().boundsInRoot.width, 2f)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(expanded.width, cover.fetchSemanticsNode().boundsInRoot.width, 2f)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("player-card").assertDoesNotExist()
    }
}
