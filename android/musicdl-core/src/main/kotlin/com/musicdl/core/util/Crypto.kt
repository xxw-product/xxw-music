package com.musicdl.core.util

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Port of the signing/crypto helpers the sources need:
 * `neteaseutils.EapiCryptoUtils`, `kugouutils.KugouMusicClientUtils`, `qqutils` signing.
 */
object Crypto {

    fun md5Hex(input: String): String = md5Hex(input.toByteArray(Charsets.UTF_8))

    fun md5Hex(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }

    fun sha1HexUpper(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** PKCS#7 pad to the AES block size (16). */
    fun pkcs7Pad(data: ByteArray, blockSize: Int = 16): ByteArray {
        val pad = blockSize - data.size % blockSize
        return data + ByteArray(pad) { pad.toByte() }
    }

    fun aesEcbEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    fun aesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * Port of `EapiCryptoUtils.encryptparams` (Netease eapi).
     * The cipher applies PKCS#7 padding, so no manual padding here.
     */
    fun neteaseEapiEncrypt(urlPath: String, payloadJson: String, aesKey: String = "e82ckenh8dichen8"): String {
        val path = urlPath.replace("/eapi/", "/api/")
        val digest = md5Hex("nobody${path}use${payloadJson}md5forencrypt")
        val plain = "$path-36cd479b6b5-$payloadJson-36cd479b6b5-$digest"
        return hex(aesEcbEncrypt(plain.toByteArray(Charsets.UTF_8), aesKey.toByteArray(Charsets.UTF_8)))
    }

    /** Port of `KugouMusicClientUtils.signatureandroid`. */
    fun kugouSignatureAndroid(
        params: Map<String, String>,
        data: String = "",
        secret: String = "OIlwieks28dk2k092lksi2UIkp",
    ): String {
        val joined = params.keys.sorted().joinToString("") { key -> "$key=${params[key]}" }
        return md5Hex("$secret$joined$data$secret")
    }

    /** Port of `KugouMusicClientUtils.signatureweb`. */
    fun kugouSignatureWeb(
        params: Map<String, String>,
        secret: String = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt",
    ): String {
        val joined = params.keys.sorted().joinToString("") { key -> "$key=${params[key]}" }
        return md5Hex("$secret$joined$secret")
    }

    /** Port of `KugouMusicClientUtils.signkey` (kgcloudv2 download key). */
    fun kugouSignKey(
        hash: String,
        mid: String,
        userId: String,
        appId: String,
        secret: String = "57ae12eb6890223e355ccfcb74edf70d",
    ): String = md5Hex("$hash$secret$appId$mid${userId.ifEmpty { "0" }}")

    /** Port of `KugouMusicClientUtils.calculatemid`. */
    fun kugouCalculateMid(seed: String): String =
        java.math.BigInteger(md5Hex(seed), 16).toString()

    /** Port of `KugouMusicClientUtils.randomstring`. */
    fun kugouRandomString(length: Int = 16): String {
        val chars = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return (1..length).map { chars.random() }.joinToString("")
    }

    /**
     * Port of `QQMusicClientUtils.sign` (the `musics.fcg` request signature).
     * Only needed by the encrypted endpoint; the plain endpoint works without it.
     */
    fun qqSign(requestJson: String): String {
        val hash = sha1HexUpper(requestJson)
        val part1Indexes = listOf(23, 14, 6, 36, 16, 40, 7, 19).filter { it < 40 }
        val part2Indexes = listOf(16, 1, 32, 12, 19, 27, 8, 5)
        val scramble = listOf(89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179)
        val part1 = part1Indexes.joinToString("") { hash[it].toString() }
        val part2 = part2Indexes.joinToString("") { hash[it].toString() }
        val part3 = ByteArray(20) { i -> (scramble[i % scramble.size] xor (hash[i % hash.length].code and 0xFF)).toByte() }
        return (part1 + part2 + hex(part3)).lowercase()
    }

    /** Port of `QQMusicClientUtils.randomsearchid`. */
    fun qqRandomSearchId(): String {
        val t = (1..20).random().toLong() * 18014398509481984L
        val n = (0..4194304).random().toLong() * 4294967296L
        val r = (System.currentTimeMillis() % (24 * 60 * 60 * 1000))
        return (t + n + r).toString()
    }

    /** Port of `QianqianMusicClient._addsignandtstoparams`. */
    fun qianqianSign(params: MutableMap<String, String>, secret: String = "0b50b02fd0d73a9c4c8c3a781c30845f"): Map<String, String> {
        params["timestamp"] = (System.currentTimeMillis() / 1000).toString()
        val joined = params.keys.sorted().joinToString("&") { key -> "$key=${params[key]}" }
        params["sign"] = md5Hex(joined + secret)
        return params
    }

    /** Port of `MiguMusicClient._decryptresp` (custom magic-prefixed XOR stream). */
    fun miguDecrypt(raw: ByteArray, key: ByteArray = "Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP".toByteArray()): ByteArray {
        if (raw.size < 5) return raw
        val seed = raw[3].toInt() and 0xFF
        val out = ByteArray(raw.size - 4)
        for (i in 4 until raw.size) {
            out[i - 4] = ((raw[i].toInt() and 0xFF) + seed - (key[(i - 4) % key.size].toInt() and 0xFF)).toByte()
        }
        return out
    }

    fun miguHasMagic(raw: ByteArray): Boolean =
        raw.size >= 3 && raw[0] == 0xAB.toByte() && raw[1] == 0xCD.toByte() && raw[2] == 0x01.toByte()
}
