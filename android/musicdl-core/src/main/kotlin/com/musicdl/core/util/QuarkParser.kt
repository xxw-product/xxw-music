package com.musicdl.core.util

import com.musicdl.core.net.HttpClient
import com.musicdl.core.net.textOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Port of `musicdl/modules/utils/quarkparser.py::QuarkParser`.
 *
 * Resolves a `pan.quark.cn/s/<id>` share link into a direct download URL by walking the same
 * six-step share chain the Python code uses (token -> detail -> path_list -> save -> task ->
 * file/download). Quark needs the caller's own cookies; without them the chain fails and the
 * client reports no download URL instead of inventing one.
 */
object QuarkParser {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.71 Safari/537.36 Core/1.94.225.400 QQBrowser/12.2.5544.400"

    private val HEADERS = mapOf(
        "user-agent" to UA,
        "origin" to "https://pan.quark.cn",
        "referer" to "https://pan.quark.cn/",
        "accept-language" to "zh-CN,zh;q=0.9",
    )

    /** Returns the raw step payloads plus the direct URL, or `(emptyMap, null)` on failure. */
    fun parseFromUrl(
        url: String,
        cookies: Map<String, String>,
        passcode: String = "",
        maxTries: Int = 3,
    ): Pair<Map<String, JsonObject>, String?> {
        repeat(maxTries.coerceAtLeast(1)) {
            val attempt = runCatching { parseOnce(url, cookies, passcode) }
            attempt.getOrNull()?.let { return it }
        }
        return emptyMap<String, JsonObject>() to null
    }

    private fun parseOnce(
        url: String,
        cookies: Map<String, String>,
        passcode: String,
    ): Pair<Map<String, JsonObject>, String?> {
        val pwdId = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
            ?.takeIf { it.isNotBlank() } ?: error("not a quark share link: $url")
        val http = HttpClient(maintainSession = true, defaultHeaders = HEADERS, defaultCookies = cookies)
        val steps = LinkedHashMap<String, JsonObject>()
        val stamp = { System.currentTimeMillis().toString() }

        // 1. share/sharepage/token
        val tokenData = postJson(
            http,
            "https://drive-h.quark.cn/1/clouddrive/share/sharepage/token",
            mapOf("pr" to "ucpro", "fr" to "pc", "uc_param_str" to "", "__dt" to "596", "__t" to stamp()),
            buildJsonObject {
                put("pwd_id", JsonPrimitive(pwdId))
                put("passcode", JsonPrimitive(passcode))
            },
        ) ?: error("quark token step failed")
        steps["token_data"] = tokenData
        val stoken = JsonUtil.extract(tokenData, listOf("data", "stoken")) ?: error("quark stoken missing")

        // 2. share/sharepage/detail
        val detail = getJson(
            http,
            "https://drive-h.quark.cn/1/clouddrive/share/sharepage/detail",
            mapOf(
                "pr" to "ucpro", "fr" to "pc", "uc_param_str" to "", "ver" to "2", "pwd_id" to pwdId,
                "stoken" to stoken, "pdir_fid" to "0", "force" to "0", "_page" to "1", "_size" to "50",
                "_fetch_banner" to "1", "_fetch_share" to "1", "fetch_relate_conversation" to "1",
                "_fetch_total" to "1", "_sort" to "file_type:asc,file_name:asc", "__dt" to "1020", "__t" to stamp(),
            ),
        ) ?: error("quark detail step failed")
        steps["detail_data"] = detail
        val first = JsonUtil.extractElement(detail, listOf("data", "list")).quarkObjects().firstOrNull()
            ?: error("quark detail list empty")
        val fid = JsonUtil.str(first, "fid") ?: error("quark fid missing")
        val shareFidToken = JsonUtil.str(first, "share_fid_token") ?: error("quark share_fid_token missing")

        // 3. clouddrive/file/info/path_list
        val pathList = postJson(
            http,
            "https://drive-pc.quark.cn/1/clouddrive/file/info/path_list",
            mapOf("pr" to "ucpro", "fr" to "pc", "uc_param_str" to "", "__dt" to "1266", "__t" to stamp()),
            buildJsonObject {
                put("file_path", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("/来自：分享"))))
            },
        ) ?: error("quark path_list step failed")
        steps["path_list_data"] = pathList
        val toPdirFid = JsonUtil.extract(pathList, listOf("data", "0", "fid")) ?: error("quark target dir missing")

        // 4. share/sharepage/save
        val saveData = postJson(
            http,
            "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/save",
            mapOf("pr" to "ucpro", "fr" to "pc", "uc_param_str" to "", "__dt" to "5660", "__t" to stamp()),
            buildJsonObject {
                put("pwd_id", JsonPrimitive(pwdId))
                put("stoken", JsonPrimitive(stoken))
                put("pdir_fid", JsonPrimitive("0"))
                put("to_pdir_fid", JsonPrimitive(toPdirFid))
                put("fid_list", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(fid))))
                put("fid_token_list", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(shareFidToken))))
                put("scene", JsonPrimitive("link"))
            },
        ) ?: error("quark save step failed")
        steps["save_data"] = saveData
        val taskId = JsonUtil.extract(saveData, listOf("data", "task_id")) ?: error("quark task id missing")

        // 5. clouddrive/task (polled, like the Python retry loop)
        var fidEncrypt: String? = null
        for (retryIndex in 0 until 5) {
            val taskData = getJson(
                http,
                "https://drive-pc.quark.cn/1/clouddrive/task",
                mapOf(
                    "pr" to "ucpro", "fr" to "pc", "uc_param_str" to "", "task_id" to taskId,
                    "retry_index" to retryIndex.toString(), "__dt" to "6355", "__t" to stamp(),
                ),
            )
            if (taskData != null) {
                steps["task_data"] = taskData
                fidEncrypt = JsonUtil.extract(taskData, listOf("data", "save_as", "save_as_top_fids", "0"))
            }
            if (fidEncrypt != null) break
            Thread.sleep(120)
        }
        val encryptedFid = fidEncrypt ?: error("quark save task never completed")

        // 6. clouddrive/file/download
        val downloadData = postJson(
            http,
            "https://drive-pc.quark.cn/1/clouddrive/file/download",
            mapOf("pr" to "ucpro", "fr" to "pc", "uc_param_str" to "", "__dt" to "6743", "__t" to stamp()),
            buildJsonObject {
                put("fids", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(encryptedFid))))
            },
        ) ?: error("quark download step failed")
        steps["download_data"] = downloadData
        val downloadUrl = JsonUtil.extract(downloadData, listOf("data", "0", "download_url"))
        return steps to downloadUrl
    }

    private fun withQuery(base: String, params: Map<String, String>): String {
        val url = base.toHttpUrlOrNull() ?: return base
        val builder = url.newBuilder()
        params.forEach { (key, value) -> builder.setQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun postJson(
        http: HttpClient,
        base: String,
        params: Map<String, String>,
        payload: JsonObject,
    ): JsonObject? = runCatching {
        http.postJson(withQuery(base, params), payload.toString(), extraHeaders = HEADERS)
            ?.use { it.textOrNull() }
            ?.let { JsonUtil.parseObject(it) }
    }.getOrNull()

    private fun getJson(http: HttpClient, base: String, params: Map<String, String>): JsonObject? = runCatching {
        http.get(withQuery(base, params), extraHeaders = HEADERS)?.use { it.textOrNull() }
            ?.let { JsonUtil.parseObject(it) }
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement?.quarkObjects(): List<JsonObject> =
        (this as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
}
