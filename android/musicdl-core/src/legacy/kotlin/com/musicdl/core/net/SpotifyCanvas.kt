package com.musicdl.core.net

import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The README wraps the payload in data; the actual upstream controller returns it directly. */
object SpotifyCanvas {
    fun videoUrl(body: String, trackId: String): String? {
        if (!trackId.matches(Regex("[A-Za-z0-9]{22}"))) return null
        return runCatching {
            val root = JsonUtil.parseObject(body) ?: return null
            val data = root["data"] as? JsonObject ?: root
            (data["canvasesList"] as? JsonArray)?.asSequence()?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                if (obj["trackUri"]?.jsonPrimitive?.content != "spotify:track:$trackId") return@mapNotNull null
                obj["canvasUrl"]?.jsonPrimitive?.content?.takeIf {
                    val uri = java.net.URI(it)
                    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
                }
            }?.firstOrNull()
        }.getOrNull()
    }
}
