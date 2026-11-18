package com.musicdl.core

import com.musicdl.core.source.KuwoMusicClient
import com.musicdl.core.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class KuwoParsingTest {
    @Test fun `parses Kuwo single quoted response and escaped apostrophe`() {
        val root = KuwoMusicClient.parseSearch("""{'abslist':[{'MUSICRID':'MUSIC_123','SONGNAME':'Don\'t stop','ARTIST':'Artist'}]}""")
        assertEquals("Don't stop", JsonUtil.extract(root, listOf("abslist", 0, "SONGNAME")))
        assertEquals("MUSIC_123", JsonUtil.extract(root, listOf("abslist", 0, "MUSICRID")))
    }
    @Test fun `accepts strict JSON and rejects unterminated input`() {
        assertNotNull(KuwoMusicClient.parseSearch("""{"abslist":[]}"""))
        assertNull(KuwoMusicClient.parseSearch("{'abslist':'unterminated"))
    }
}
