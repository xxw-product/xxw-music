package com.musicdl.android

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.musicdl.core.lyrics.LyricLine
import com.musicdl.core.lyrics.Lyrics
import com.musicdl.core.model.SongInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Acceptance on Android, including the real ViewModel and Media3 transport. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun seekRailSupportsTapDragAccessibilityAndDisabledState() {
        var position by mutableStateOf(0.25f)
        var enabled by mutableStateOf(true)
        val committed = mutableListOf<Float>()
        compose.setContent { MusicdlTheme {
            VinylSeekBar(position, enabled, { position = it }, { committed += position })
        } }
        val rail = compose.onNodeWithContentDescription("Позиция воспроизведения")
        rail.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.75f, center.y)) }
        compose.runOnIdle { assertTrue(committed.last() in 0.70f..0.80f) }
        rail.performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.20f, center.y),
                androidx.compose.ui.geometry.Offset(width * 0.60f, center.y))
        }
        compose.runOnIdle { assertTrue(committed.last() in 0.50f..0.70f) }
        rail.performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.75f, center.y),
                androidx.compose.ui.geometry.Offset(width * 0.10f, center.y))
        }
        compose.runOnIdle { assertTrue(committed.last() < 0.20f) }
        rail.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { action -> action(0.9f) }
        compose.runOnIdle { assertEquals(0.9f, committed.last(), 0.001f); enabled = false }
        val count = committed.size
        rail.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(count, committed.size) }
    }

    @Test fun playerPaletteFollowsArtworkHue() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val blue = coverPalette(bitmap)
            bitmap.eraseColor(android.graphics.Color.RED)
            val red = coverPalette(bitmap)
            assertTrue(blue.accent.blue > blue.accent.red)
            assertTrue(red.accent.red > red.accent.blue)
            assertTrue(blue.background.blue > blue.background.red)
        } finally { bitmap.recycle() }
    }

    @Test fun coverGestureShrinksToQuarterAndCanBeReversed() {
        val state = MusicUiState(player = PlayerUiState(song = SongInfo(songName = "Тестовый трек"), positionMs = 2000),
            lyrics = Lyrics(lines = listOf(LyricLine(0, "Первая строка"), LyricLine(1000, "Активная строка"), LyricLine(5000, "Следующая строка"))))
        compose.setContent {
            MusicdlTheme {
                FullPlayer(state, MutableStateFlow(0f), false, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        val cover = compose.onNodeWithContentDescription("Обложка трека")
        val expanded = cover.fetchSemanticsNode().boundsInRoot
        saveRender("player-expanded")
        cover.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val collapsed = cover.fetchSemanticsNode().boundsInRoot
        saveRender("player-lyrics")
        assertEquals(expanded.width * 0.25f, collapsed.width, 2f)
        assertEquals(expanded.height * 0.25f, collapsed.height, 2f)
        compose.onNodeWithText("Активная строка").assertIsDisplayed()
        cover.performTouchInput { swipeRight(durationMillis = 100) }
        compose.waitForIdle()
        assertEquals(expanded.width, cover.fetchSemanticsNode().boundsInRoot.width, 2f)
    }

    @Test fun collapsedCoverRotatesWhilePlayingFreezesOnPauseAndReturnsToFront() {
        var playing by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                MusicdlTheme {
                    FullPlayer(MusicUiState(player = PlayerUiState(song = SongInfo(songName = "Rotation"), isPlaying = playing)),
                        MutableStateFlow(0f), true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
            val cover = compose.onNodeWithContentDescription("Обложка трека")
            val front = cover.fetchSemanticsNode().boundsInRoot.width
            compose.mainClock.advanceTimeBy(3000)
            val turned = cover.fetchSemanticsNode().boundsInRoot.width
            assertTrue("A playing collapsed cover should turn in 3D", kotlin.math.abs(front - turned) > 10f)
            compose.runOnIdle { playing = false }
            compose.mainClock.advanceTimeBy(100)
            val paused = cover.fetchSemanticsNode().boundsInRoot.width
            compose.mainClock.advanceTimeBy(2000)
            assertEquals(paused, cover.fetchSemanticsNode().boundsInRoot.width, 1f)
            compose.runOnIdle { playing = true }
            compose.mainClock.advanceTimeBy(1000)
            assertTrue(kotlin.math.abs(paused - cover.fetchSemanticsNode().boundsInRoot.width) > 5f)
            compose.onNodeWithContentDescription("Текст песни").performClick()
            compose.mainClock.advanceTimeBy(2000)
            assertTrue(cover.fetchSemanticsNode().boundsInRoot.width > front * 3.9f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun timedLyricsFollowPlaybackAndSeekBackToTheActiveLine() {
        var position by mutableStateOf(0L)
        val lines = (0..14).map { LyricLine(it * 2000L, "Строка $it") }
        compose.setContent {
            MusicdlTheme {
                FullPlayer(MusicUiState(player = PlayerUiState(song = SongInfo(songName = "Timed lyrics"),
                    positionMs = position, durationMs = 30000), lyrics = Lyrics(lines = lines)),
                    MutableStateFlow(0f), true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        val firstCenter = compose.onNodeWithText("Строка 0").fetchSemanticsNode().boundsInRoot.center.y
        compose.runOnIdle { position = 20000 }
        compose.onNodeWithText("Строка 10").assertIsDisplayed()
        assertEquals(firstCenter, compose.onNodeWithText("Строка 10").fetchSemanticsNode().boundsInRoot.center.y, 8f)
        compose.runOnIdle { position = 2000 }
        compose.onNodeWithText("Строка 1").assertIsDisplayed()
        assertEquals(firstCenter, compose.onNodeWithText("Строка 1").fetchSemanticsNode().boundsInRoot.center.y, 8f)
    }

    @Test fun playlistsSurviveStoreReconstructionWithoutLosingTrackMetadata() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val library = LibraryStore(app)
        val previous = library.playlists()
        val track = SongInfo(source = "acceptance", identifier = "persisted", songName = "Сохранённый трек",
            singers = "Исполнитель", downloadUrl = "file:///fixture.wav", durationSeconds = 123,
            genre = "Hip-Hop", tags = "rap underground", description = "[00:01]Text",
            downloadHeaders = mapOf("Referer" to "https://example.com"))
        val playlist = MusicPlaylist("acceptance:playlist", "Мой плейлист", listOf(track))
        try {
            library.savePlaylists(previous + playlist)
            val restored = LibraryStore(app).playlists().single { it.id == playlist.id }
            assertEquals(playlist, restored)
        } finally { library.savePlaylists(previous) }
    }

    @Test fun longPlaylistKeepsCurrentTrackAcrossRestore() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val library = LibraryStore(app)
        val previous = library.playback()
        val tracks = (0 until 300).map { SongInfo(source = "acceptance", identifier = "$it", songName = "Трек $it") }
        try {
            library.savePlayback(tracks, 250, 1234)
            val restored = LibraryStore(app).playback()!!
            assertEquals(300, restored.queue.size)
            assertEquals("250", restored.queue[restored.index].identifier)
            assertEquals(1234L, restored.positionMs)
        } finally {
            if (previous == null) library.clearPlayback()
            else library.savePlayback(previous.queue, previous.index, previous.positionMs)
        }
    }

    @Test fun playlistCanBeCreatedFilledReorderedRenamedCopiedAndDeletedThroughUi() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val library = LibraryStore(app)
        val previousLists = library.playlists()
        val previousResults = library.lastResults()
        val previousPlayback = library.playback()
        val store = ViewModelStore()
        val tracks = listOf("Первый приёмочный", "Второй приёмочный").mapIndexed { index, title ->
            SongInfo(source = "SoundCloudMusicClient", identifier = "ui-$index", songName = title, singers = "Тест")
        }
        try {
            library.saveResults(tracks)
            lateinit var model: SearchViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[SearchViewModel::class.java]
            }
            compose.setContent {
                val state by model.state.collectAsState()
                MusicdlTheme { PlaylistLibrary(state, model, onClose = {}) }
            }
            compose.onNodeWithContentDescription("Создать плейлист").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("В дорогу")
            compose.onNodeWithText("Сохранить").performClick()
            compose.onNodeWithText("В дорогу").performClick()
            compose.onNodeWithContentDescription("Добавить треки").performClick()
            compose.onNodeWithText("+ Первый приёмочный · Тест").performClick()
            compose.onNodeWithText("+ Второй приёмочный · Тест").performClick()
            compose.onNodeWithText("Готово").performClick()
            compose.onAllNodesWithContentDescription("Действия с треком")[0].performClick()
            compose.onNodeWithText("Ниже").performClick()
            compose.runOnIdle { assertEquals(tracks.reversed(), library.playlists().single { it.name == "В дорогу" }.tracks) }
            compose.onNodeWithContentDescription("Переименовать").performClick()
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.onNode(hasSetTextAction()).performTextInput("Вечерняя дорога")
            compose.onNodeWithText("Сохранить").performClick()
            saveRender("playlist-filled", captureWindow = true)
            compose.onNodeWithContentDescription("Создать копию").performClick()
            compose.onNodeWithContentDescription("Удалить плейлист").performClick()
            compose.onNodeWithText("Удалить").performClick()
            compose.onNodeWithText("Вечерняя дорога · копия").assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(library.playlists().any { it.name == "Вечерняя дорога" })
                assertEquals(tracks.reversed(), library.playlists().single { it.name == "Вечерняя дорога · копия" }.tracks)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            library.savePlaylists(previousLists)
            library.saveResults(previousResults)
            if (previousPlayback == null) library.clearPlayback()
            else library.savePlayback(previousPlayback.queue, previousPlayback.index, previousPlayback.positionMs)
        }
    }

    private fun saveRender(name: String, captureWindow: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.waitForIdle()
        if (captureWindow) InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 3000)
        val image = if (captureWindow) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        else compose.onNode(isRoot() and SemanticsMatcher("non-empty root") {
            it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
        }).captureToImage().asAndroidBitmap()
        File(context.cacheDir, "acceptance-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun cardTapNextAndNotificationNextUseTheCapturedList() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("musicdl_library", 0)
        val backup = prefs.all.toMap()
        val store = ViewModelStore()
        val files = (1..3).map { wav(app.cacheDir, "queue-acceptance-$it.wav", 15) }
        try {
            val tracks = files.mapIndexed { i, file -> SongInfo(source = "acceptance", identifier = "$i",
                songName = "Локальный трек $i", downloadUrl = file.toURI().toString(), ext = "wav", durationSeconds = 15) }
            val library = LibraryStore(app)
            library.saveResults(tracks)
            library.tab = FeedTab.SEARCH
            library.savePlayback(tracks, 0, 4000)
            lateinit var model: SearchViewModel
            compose.setContent { MusicdlTheme { androidx.compose.material3.Text("Проверка очереди") } }
            compose.runOnIdle {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[SearchViewModel::class.java]
                assertEquals(4000L, model.state.value.player.positionMs)
                model.togglePlayPause()
            }
            compose.waitUntil(10_000) { model.state.value.player.isPlaying }
            compose.runOnIdle {
                assertTrue(model.state.value.player.positionMs >= 3500L)
                model.togglePlayPause()
                app.stopService(android.content.Intent(app, PlaybackService::class.java))
            }
            compose.waitUntil(10_000) { PlayerHolder.session == null }
            compose.runOnIdle { model.togglePlayPause() }
            compose.waitUntil(10_000) { model.state.value.player.isPlaying }
            compose.runOnIdle {
                model.playOrToggle(tracks[0])
                assertEquals(3, model.state.value.queueCount)
                model.playNext()
                assertEquals("1", model.state.value.player.song?.identifier)
                model.toggleRepeat()
                model.playNext()
                assertEquals("2", model.state.value.player.song?.identifier)
            }
            compose.waitUntil(10_000) { PlayerHolder.session != null }
            compose.runOnIdle {
                PlayerHolder.session!!.player.seekToNext()
                assertEquals("0", model.state.value.player.song?.identifier)
                model.toggleRepeat()
            }
            compose.waitUntil(10_000) { model.state.value.player.durationMs > 0 && !model.state.value.player.isBuffering }
            compose.runOnIdle { model.seekTo(0.995f) }
            compose.waitUntil(10_000) { model.state.value.player.song?.identifier == "1" }
            compose.runOnIdle {
                assertEquals(2, model.state.value.queuePosition)
                model.stop()
            }
        } finally {
            compose.runOnIdle { store.clear() }
            val edit = prefs.edit().clear()
            backup.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            edit.commit()
            files.forEach { it.delete() }
        }
    }

    private fun wav(directory: File, name: String, seconds: Int): File {
        val dataSize = 8000 * seconds * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
        }.array()
        return File(directory, name).apply { outputStream().use { it.write(header); it.write(ByteArray(dataSize)) } }
    }
}
