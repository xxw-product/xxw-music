package com.musicdl.core.source

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun pagedSearchUrls(
    endpoint: String,
    query: Map<String, String>,
    total: Int,
    perPage: Int,
    limitParam: String = "limit",
    offsetParam: String = "offset",
): List<String> {
    if (total <= 0 || perPage <= 0) return emptyList()
    return (0 until total step perPage).map { offset ->
        endpoint.toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> setQueryParameter(key, value) }
            setQueryParameter(limitParam, minOf(perPage, total - offset).toString())
            setQueryParameter(offsetParam, offset.toString())
        }.build().toString()
    }
}

internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

