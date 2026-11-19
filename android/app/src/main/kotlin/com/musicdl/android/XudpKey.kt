package com.musicdl.android

import java.security.MessageDigest
import java.util.Base64

internal object XudpKey {
    /** SHA-256 (32 bytes) as raw URL-safe base64, the format `xray.xudp.basekey` requires. */
    fun derive(seed: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()))
}
