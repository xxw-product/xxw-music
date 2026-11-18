package com.musicdl.core

import com.musicdl.core.source.MusicClientRegistry
import com.musicdl.core.util.TextUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the helpers the newly ported clients lean on and the registry surface,
 * so a rename or a dropped registration fails here instead of on a device.
 */
class PortedSourcesTest {

    @Test
    fun cleanLrcDropsTimelineAndMetadata() {
        val raw = "[ti:Demo]\n[ar:Artist]\n[00:01.20]first line\n[00:05.00]second line\n"
        assertEquals("first line\nsecond line", TextUtils.cleanLrc(raw))
        assertNull(TextUtils.cleanLrc("   "))
    }

    @Test
    fun extractDurationUsesLastStamp() {
        val lrc = "[00:10.00]a\n[01:05.50]b"
        assertEquals(65L, TextUtils.extractDurationSecondsFromLrc(lrc))
        assertEquals(0L, TextUtils.extractDurationSecondsFromLrc(null))
    }

    @Test
    fun parseDurationTextHandlesEveryShapeTheScrapersUse() {
        assertEquals(65L, TextUtils.parseDurationText("01:05"))
        assertEquals(3725L, TextUtils.parseDurationText("01:02:05"))
        assertEquals(3725L, TextUtils.parseDurationText("1小时2分钟5秒"))
        assertEquals(95L, TextUtils.parseDurationText("1分35秒"))
        assertEquals(42L, TextUtils.parseDurationText("42"))
        assertEquals(0L, TextUtils.parseDurationText(""))
    }

    @Test
    fun base64ToleratesUrlSafeAndMissingPadding() {
        val decoded = TextUtils.decodeBase64("aGVsbG8")?.let { String(it) }
        assertEquals("hello", decoded)
        assertEquals("http://x/y_a-b", TextUtils.decodeBase64("aHR0cDovL3gveV9hLWI")?.let { String(it) })
        assertNull(TextUtils.decodeBase64("!!!not base64!!!"))
    }

    @Test
    fun registryContainsOnlySupportedProviders() {
        org.junit.Assert.assertEquals(
            setOf("QobuzMusicClient", "DeezerMusicClient", "SoundCloudMusicClient", "YandexMusicClient", "VKMusicClient"),
            MusicClientRegistry.registeredModules,
        )
        org.junit.Assert.assertEquals(MusicClientRegistry.registeredModules,
            com.musicdl.core.source.MusicClient.DEFAULT_MUSIC_SOURCES.toSet())
        org.junit.Assert.assertNull(MusicClientRegistry.build("SpotifyMusicClient", com.musicdl.core.source.MusicClientConfig()))
    }
}
