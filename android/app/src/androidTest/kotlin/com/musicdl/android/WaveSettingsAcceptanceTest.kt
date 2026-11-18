package com.musicdl.android

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.musicdl.core.model.SongInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WaveSettingsAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test fun waveCardRendersAndOpensGenreControls() {
        var weights by mutableStateOf(false)
        compose.setContent { MusicdlTheme { androidx.compose.material3.Surface {
            WaveCard(MusicUiState(), weights, { weights = !weights }, {}, { _, _ -> })
        } } }
        compose.onNodeWithText("Моя волна").assertIsDisplayed()
        compose.onNodeWithText("Жанры").performClick()
        compose.onNodeWithText("Электроника").assertIsDisplayed()
    }

    /** Every test restores the user's preferences, including keys it does not know about. */
    private fun isolated(block: () -> Unit) {
        val preferences = listOf("wave_prefs", "musicdl_library", "musicdl_downloads")
            .map { app.getSharedPreferences(it, 0) }
        val snapshots = preferences.map { it.all.toMap() }
        try {
            preferences.forEach { it.edit().clear().commit() }
            block()
        } finally {
            preferences.zip(snapshots).forEach { (prefs, entries) ->
                val edit = prefs.edit().clear()
                entries.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                edit.commit()
            }
        }
    }

    @Test fun settingsPersistAndResetPreservesLibrary() = isolated {
        val library = LibraryStore(app)
        val taste = WaveStore(app)
        val song = SongInfo(source = "SoundCloudMusicClient", identifier = "settings-test", songName = "Трек", singers = "Автор", genre = "Jazz")
        library.toggleLike(song)
        library.savePlaylists(listOf(MusicPlaylist("settings-test", "Коллекция", listOf(song))))
        taste.record(song)
        taste.putCoefficient("jazz", 1.8f)
        val store = ViewModelStore()
        lateinit var model: SearchViewModel
        compose.runOnIdle { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[SearchViewModel::class.java] }
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                MusicdlTheme { androidx.compose.foundation.lazy.LazyColumn {
                    item { SettingsContent(state, model) }
                } }
            }
            compose.onNodeWithContentDescription("Автоскачивание").performClick()
            compose.onNodeWithText("500 МБ", useUnmergedTree = true).performClick()
            compose.runOnIdle {
                assertTrue(LibraryStore(app).autoDownload)
                assertEquals(500L * DownloadStore.MB, DownloadStore(app).limitBytes)
            }
            compose.onNodeWithText("Сбросить мою волну").performScrollTo().performClick()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(1, taste.listening().sumOf { it.plays })
            compose.onNodeWithText("Сбросить мою волну").performScrollTo().performClick()
            compose.onNodeWithText("Сбросить", useUnmergedTree = true).performClick()
            compose.runOnIdle {
                assertTrue(taste.listening().isEmpty())
                assertEquals(Genres.DEFAULT_WEIGHT, taste.coefficients()["jazz"])
                assertEquals(listOf(song), library.likes())
                assertEquals(listOf(song), library.playlists().single().tracks)
                assertTrue(library.autoDownload)
            }
        } finally { compose.runOnIdle { store.clear() } }
    }

    /** The old limit was "10 tracks"; the quota is bytes, so fifteen tiny files all fit. */
    @Test fun automaticDownloadsAreLimitedByBytesNotByTrackCount() = isolated {
        MockWebServer().use { server ->
            repeat(24) { server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(ByteArray(256) { 1 }))) }
            server.start()
            val baseUrl = server.url("/").toString()
            val store = ViewModelStore()
            val prefix = "xxw-auto-${System.nanoTime()}"
            lateinit var model: SearchViewModel
            compose.runOnIdle {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[SearchViewModel::class.java]
                model.setDownloadLimit(500L * DownloadStore.MB)
                model.setAutoDownload(true)
                repeat(15) { index -> model.toggleLike(SongInfo(source = "SoundCloudMusicClient", identifier = "$prefix-$index",
                    songName = "$prefix-$index", singers = "Test", genre = "Electronic", tags = "House", ext = "wav",
                    downloadUrl = "${baseUrl}$index.wav")) }
            }
            try {
                compose.waitUntil(30_000) {
                    model.state.value.downloads.size == 15 && model.state.value.saves.values.none { it == SaveState.RUNNING }
                }
                assertEquals(15, model.state.value.downloads.size)
                assertTrue(model.state.value.downloadUsedBytes > 0L)
                assertEquals(500L * DownloadStore.MB, model.state.value.downloadLimitBytes)
                compose.runOnIdle {
                    model.save(SongInfo(source = "SoundCloudMusicClient", identifier = "$prefix-manual", songName = "$prefix-manual",
                        genre = "Jazz", tags = "Soul", ext = "wav", downloadUrl = "${baseUrl}manual.wav"))
                }
                compose.waitUntil(10_000) { model.state.value.saves["SoundCloudMusicClient:$prefix-manual"] == SaveState.DONE }
                assertEquals(16, model.state.value.downloads.size)
                assertEquals(16, server.requestCount)
                compose.runOnIdle { model.setAutoDownload(false) }
                assertFalse(LibraryStore(app).autoDownload)
            } finally {
                compose.runOnIdle { store.clear() }
                DownloadStore(app).clearAll()
                LibraryStore(app).savedFiles().map(::File).filter { it.name.startsWith(prefix) }.forEach { it.delete() }
            }
        }
    }

    @Test fun cachedOfflineMusicDoesNotTurnIntoALikeAutomatically() = isolated {
        val library = LibraryStore(app)
        val file = File.createTempFile("xxw-offline-test", ".wav", app.cacheDir)
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val song = SongInfo(source = "SoundCloudMusicClient", identifier = "cache-test", genre = "Jazz", tags = "Soul")
            library.saveDownload(song, file.absolutePath, addToLikes = false)
            assertTrue(library.likes().isEmpty())
            assertEquals(file.absolutePath, LibraryStore(app).localFileFor(song)?.absolutePath)
        } finally { file.delete() }
    }
}
