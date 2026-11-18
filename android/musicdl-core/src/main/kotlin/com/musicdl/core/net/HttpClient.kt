package com.musicdl.core.net

import com.musicdl.core.util.LoggerHandle
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Port of `BaseMusicClient.get` / `post` and the session handling around them.
 *
 * The Python version re-creates a `requests.Session` per call unless `maintain_session`
 * is set, retries `max_retries` times and swallows errors into a logged failure.
 * Same contract here, on OkHttp.
 */
class HttpClient(
    private val logger: LoggerHandle = LoggerHandle.SILENT,
    private val maxRetries: Int = 3,
    private val maintainSession: Boolean = false,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val defaultCookies: Map<String, String> = emptyMap(),
    private val timeoutSeconds: Long = 20,
) {
    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        // A retry loop around a hung server must still end; without a total call budget a thread
        // could stay parked for minutes and starve the coroutine pool.
        .callTimeout(timeoutSeconds + 5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .retryOnConnectionFailure(true)
        .build()

    var headers: Map<String, String> = defaultHeaders
        private set

    var cookies: Map<String, String> = defaultCookies
        private set

    fun updateHeaders(extra: Map<String, String>) {
        headers = headers + extra
    }

    fun updateCookies(extra: Map<String, String>) {
        cookies = cookies + extra
        cookieJar.putAll(extra)
    }

    /** GET returning a [Response], or null when every retry failed. Caller closes it. */
    fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        tag: String = "get",
    ): Response? = execute("GET", url, query, null, extraHeaders, tag)

    fun postForm(
        url: String,
        form: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        tag: String = "post",
    ): Response? {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute("POST", url, emptyMap(), body, extraHeaders, tag)
    }

    fun postJson(
        url: String,
        json: String,
        extraHeaders: Map<String, String> = emptyMap(),
        tag: String = "post",
    ): Response? {
        val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), json)
        return execute("POST", url, emptyMap(), body, extraHeaders, tag)
    }

    fun head(url: String, extraHeaders: Map<String, String> = emptyMap(), tag: String = "head"): Response? =
        execute("HEAD", url, emptyMap(), null, extraHeaders, tag)

    private fun execute(
        method: String,
        url: String,
        query: Map<String, String>,
        body: RequestBody?,
        extraHeaders: Map<String, String>,
        tag: String,
    ): Response? {
        var lastError: Throwable? = null
        repeat(maxRetries.coerceAtLeast(1)) { attempt ->
            if (!maintainSession && attempt > 0) cookieJar.clearTransient()
            val built = buildUrl(url, query) ?: return null
            val builder = Request.Builder().url(built)
            headers.forEach { (k, v) -> builder.header(k, v) }
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            if (cookies.isNotEmpty() && extraHeaders.keys.none { it.equals("Cookie", true) }) {
                builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            when (method) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                "POST" -> builder.post(body ?: FormBody.Builder().build())
            }
            val response = runCatching { client.newCall(builder.build()).execute() }.getOrElse { err ->
                lastError = err
                logger.error("HttpClient.$tag >>> $url (Error: ${err.message})")
                return@repeat
            }
            if (response.isSuccessful) return response
            logger.error("HttpClient.$tag >>> $url (Error: HTTP ${response.code})")
            response.close()
        }
        lastError?.let { logger.error("HttpClient.$tag >>> $url exhausted $maxRetries retries (${it.message})") }
        return null
    }

    private fun buildUrl(url: String, query: Map<String, String>): HttpUrl? {
        val base = url.toHttpUrlOrNull() ?: return null
        if (query.isEmpty()) return base
        val builder = base.newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private class MemoryCookieJar : CookieJar {
        private val store = LinkedHashMap<String, Cookie>()

        fun putAll(cookies: Map<String, String>) {
            cookies.forEach { (k, v) ->
                val parsed = Cookie.parse("https://localhost/".toHttpUrlOrNull()!!, "$k=$v")
                if (parsed != null) store[k] = parsed
            }
        }

        fun clearTransient() = store.clear()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store[it.name] = it }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.values.toList()
    }
}

/** Response body as text, or null. */
fun Response.textOrNull(): String? = runCatching { body?.string() }.getOrNull()
