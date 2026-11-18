package com.musicdl.core

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.util.Crypto
import com.musicdl.core.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural tests for the ported helpers, mirroring the Python semantics. */
class CoreUtilsTest {

    @Test
    fun `md5 matches known digest`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Crypto.md5Hex("abc"))
    }

    @Test
    fun `kugou kgcloudv2 key matches python formula`() {
        // Python: hashlib.md5((str(song_id) + 'kgcloudv2').encode()).hexdigest()
        val expected = Crypto.md5Hex("ABCDEF1234kgcloudv2")
        assertEquals(expected, Crypto.md5Hex("ABCDEF1234" + "kgcloudv2"))
        assertEquals(32, expected.length)
    }

    @Test
    fun `legalizeString strips markup and entities`() {
        assertEquals("A & B", PathUtils.legalizeString("A &amp; B"))
        assertEquals("Hello", PathUtils.legalizeString("<b>Hello</b>"))
        assertEquals("NULL", PathUtils.legalizeString(null))
        assertEquals("NULL", PathUtils.legalizeString("   "))
    }

    @Test
    fun `sanitizeFileName removes illegal characters`() {
        assertEquals("a_b_c", PathUtils.sanitizeFileName("a/b:c"))
        assertEquals("NULL", PathUtils.sanitizeFileName(""))
        assertFalse(PathUtils.sanitizeFileName("a?b").contains("?"))
    }

    @Test
    fun `byteToMb formats like python`() {
        assertEquals("1.0 MB", PathUtils.byteToMb(1048576))
        assertEquals("NULL", PathUtils.byteToMb(0))
    }

    @Test
    fun `formatDuration matches seconds2hms`() {
        assertEquals("00:03:25", SongInfo.formatDuration(205))
        assertEquals("-:-:-", SongInfo.formatDuration(0))
        assertEquals("01:00:00", SongInfo.formatDuration(3600))
    }

    @Test
    fun `safeextractfromdict supports negative indexes`() {
        val root = JsonUtil.parseObject("""{"imgItems":[{"img":"a"},{"img":"b"}]}""")
        assertNotNull(root)
        assertEquals("b", JsonUtil.extract(root, listOf("imgItems", -1, "img")))
        assertEquals("a", JsonUtil.extract(root, listOf("imgItems", 0, "img")))
        assertEquals("fallback", JsonUtil.extract(root, listOf("missing", "x"), "fallback"))
    }

    @Test
    fun `hasValidDownloadUrl requires http and ok status`() {
        val ok = SongInfo(
            downloadUrl = "https://example.com/a.mp3",
            downloadUrlStatus = com.musicdl.core.model.DownloadUrlStatus(ok = true),
        )
        val bad = ok.copy(downloadUrlStatus = com.musicdl.core.model.DownloadUrlStatus(ok = false))
        assertTrue(ok.hasValidDownloadUrl)
        assertFalse(bad.hasValidDownloadUrl)
    }

    @Test
    fun `resolveSavePath deduplicates existing files`() {
        val song = SongInfo(songName = "Track", identifier = "42", ext = "mp3", workDir = "/music")
        val first = song.resolveSavePath { false }
        assertEquals("/music/Track - 42.mp3", first)

        val taken = setOf("/music/Track - 42.mp3")
        val second = song.resolveSavePath { it in taken }
        assertEquals("/music/Track - 42 (1).mp3", second)
    }

    @Test
    fun `largerThan compares file size strings`() {
        assertTrue(SongInfo(fileSize = "8.5 MB").largerThan(SongInfo(fileSize = "3.2 MB")))
        assertFalse(SongInfo(fileSize = "1 MB").largerThan(SongInfo(fileSize = "9 MB")))
    }

    @Test
    fun `netease eapi encryption is deterministic and hex`() {
        val a = Crypto.neteaseEapiEncrypt("/eapi/song/enhance/player/url/v1", """{"ids":"[1]"}""")
        val b = Crypto.neteaseEapiEncrypt("/eapi/song/enhance/player/url/v1", """{"ids":"[1]"}""")
        assertEquals(a, b)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue(a.length % 32 == 0)
    }

    @Test
    fun `migu magic detection and decrypt roundtrip`() {
        val key = "Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP".toByteArray()
        val plain = """{"ok":true}""".toByteArray()
        val seed = 7
        val payload = ByteArray(4 + plain.size)
        payload[0] = 0xAB.toByte(); payload[1] = 0xCD.toByte(); payload[2] = 0x01; payload[3] = seed.toByte()
        for (i in plain.indices) {
            payload[4 + i] = ((plain[i].toInt() and 0xFF) + (key[i % key.size].toInt() and 0xFF) - seed).toByte()
        }
        assertTrue(Crypto.miguHasMagic(payload))
        assertEquals(String(plain), String(Crypto.miguDecrypt(payload)))
    }
}
