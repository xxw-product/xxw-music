package com.musicdl.core.source

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Decoder for the stream links VK hands out for audio.
 *
 * Port of `decode_stream` / `decode_vk_base64` / `shuffle` / `shuffle_bigint` from
 * [vk-fast](https://github.com/eml3ss/vk-fast) (`src/web_audio.rs`). VK no longer always returns a
 * plain MP3 URL: a row can carry
 * `https://…/audio_api_unavailable.mp3?extra=<data>#<operations>`, where both halves are the VK
 * alphabet's base64 and the operations are a small stack of transformations that the website
 * applies in JavaScript. Without this step the "URL" is a placeholder and every download fails.
 *
 * The two shuffle variants are kept apart on purpose, exactly as in the reference implementation:
 * `s` uses the legacy JavaScript 32-bit number path, while `i` uses BigInt and XORs the seed with
 * the **signed-in account id**. Truncating that id to 32 bits corrupts every link that uses `i`,
 * which is why the viewer id is a parameter here.
 */
object VkStreamUrlDecoder {

    /** Marker VK puts in a URL that has to be decoded before it can be played. */
    const val MARKER = "audio_api_unavailable"

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN0PQRSTUVWXYZO123456789+/="
    private const val BASE64_ALPHABET = // first 64 entries, without the trailing padding
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN0PQRSTUVWXYZO123456789+/"
    private const val MAX_URL_LENGTH = 32 * 1024
    private const val MAX_OPERATIONS = 32

    /**
     * Returns a playable `https` link, or `null` when the input is not one and cannot be turned
     * into one. [viewer] is the signed-in account id (`users.get`), needed by the `i` operation.
     */
    fun decode(url: String, viewer: Long): String? {
        if (url.isEmpty() || url.length > MAX_URL_LENGTH) return null
        val decoded = if (url.contains(MARKER)) decodeMasked(url, viewer) ?: return null else url
        return decoded.takeIf(::isPlayable)
    }

    private fun decodeMasked(url: String, viewer: Long): String? {
        val extra = url.substringAfter("?extra=", "").takeIf { it.isNotEmpty() } ?: return null
        val separator = extra.indexOf('#')
        if (separator < 0) return null
        val data = vkBase64(extra.substring(0, separator)) ?: return null
        val encodedOperations = vkBase64(extra.substring(separator + 1)) ?: return null
        val commands = String(encodedOperations, Charsets.ISO_8859_1)
            .split('\t')
            .filter { it.isNotEmpty() }
        if (commands.size > MAX_OPERATIONS) return null
        // The website applies the operations in the order they were pushed, so replay in reverse.
        for (command in commands.asReversed()) {
            val parts = command.split('\u000B')
            if (parts.size > 2) return null
            val argument = parts.getOrNull(1)
            when (parts[0]) {
                "v" -> data.reverse()
                "x" -> {
                    val key = argument?.firstOrNull()?.code ?: return null
                    for (index in data.indices) data[index] = (data[index].toInt() xor key).toByte()
                }
                "r" -> {
                    val shift = argument?.toLongOrNull() ?: return null
                    rotate(data, shift)
                }
                "s" -> {
                    val seed = argument?.toLongOrNull() ?: return null
                    if (!legacyShuffle(data, seed)) return null
                }
                "i" -> {
                    val seed = argument?.toLongOrNull() ?: return null
                    shuffleBig(data, seed.toDouble().toLong() xor viewer.toDouble().toLong())
                }
                else -> return null
            }
        }
        return runCatching { String(data, Charsets.UTF_8) }.getOrNull()
    }

    /** VK's own base64: the usual layout, but over its own alphabet and without padding. */
    private fun vkBase64(input: String): ByteArray? {
        if (input.length > MAX_URL_LENGTH || input.length % 4 == 1) return null
        val output = ArrayList<Byte>(input.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (character in input) {
            if (character == '=') break
            val value = BASE64_ALPHABET.indexOf(character)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer shr bits) and 0xFF).toByte())
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return output.toByteArray()
    }

    private fun rotate(data: ByteArray, shift: Long) {
        for (index in data.indices) {
            val position = ALPHABET.indexOf(data[index].toInt().toChar())
            if (position < 0) continue
            // Kotlin's rem already returns a non-negative value for a positive divisor.
            data[index] = ALPHABET[Math.floorMod(position - shift, 65L).toInt()].code.toByte()
        }
    }

    /** Legacy `s`: every intermediate value is a JavaScript 32-bit number. */
    internal fun legacyShuffle(data: ByteArray, seedValue: Long): Boolean {
        val length = data.size
        if (length == 0) return true
        if (seedValue == Long.MIN_VALUE) return false
        var seed = kotlin.math.abs(seedValue)
        val indices = IntArray(length)
        for (position in length - 1 downTo 0) {
            val lhs = length * (position + 1)
            val rhs = (seed + position).toInt()
            seed = (lhs xor rhs).toLong() % length
            indices[position] = if (seed < 0) (length + seed).toInt() else seed.toInt()
        }
        swapByIndices(data, indices)
        return true
    }

    /** `i`: BigInt XOR of the operation argument and the account id, then the same shuffle. */
    private fun shuffleBig(data: ByteArray, seedValue: Long) {
        val length = data.size
        if (length == 0) return
        var seed = if (seedValue < 0) -seedValue else seedValue
        val indices = IntArray(length)
        for (position in length - 1 downTo 0) {
            seed = (length.toLong() * (position + 1) xor (seed + position)) % length
            indices[position] = seed.toInt()
        }
        swapByIndices(data, indices)
    }

    private fun swapByIndices(data: ByteArray, indices: IntArray) {
        val length = data.size
        for (position in 1 until length) {
            val other = indices[length - 1 - position]
            if (other !in data.indices) continue
            val swap = data[position]
            data[position] = data[other]
            data[other] = swap
        }
    }

    /**
     * Only a public `https` link on a real host is accepted: no credentials, no fragment, no
     * loopback/IP hosts, and never the placeholder that failed to decode.
     */
    private fun isPlayable(value: String): Boolean {
        if (value.any { it.isWhitespace() || it.isISOControl() }) return false
        if (value.contains(MARKER) || value.length > MAX_URL_LENGTH) return false
        if (value.contains('#')) return false
        val url = value.toHttpUrlOrNull() ?: return false
        if (url.scheme != "https" || url.port != 443) return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (!host.contains('.')) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        return true
    }
}
