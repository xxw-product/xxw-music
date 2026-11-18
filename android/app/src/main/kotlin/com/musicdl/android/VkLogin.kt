package com.musicdl.android

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val VK_AUTH_URL =
    "https://oauth.vk.com/authorize?client_id=2685278" +
        "&scope=audio,offline&redirect_uri=https%3A%2F%2Foauth.vk.com%2Fblank.html" +
        "&display=mobile&response_type=token&v=5.199"

/** Only accept credentials from the exact OAuth callback, never a lookalike URL. */
internal fun vkLoginCallback(url: String): Pair<String?, String?>? {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" || uri.host != "oauth.vk.com" || uri.path != "/blank.html" ||
        uri.port !in listOf(-1, 443) || uri.userInfo != null) return null
    val params = Uri.parse("https://callback.invalid/?${uri.fragment.orEmpty()}")
    val token = params.getQueryParameter("access_token")?.takeIf { it.isNotBlank() }
    val error = params.getQueryParameter("error")
    return token to if (error != null) "VK не разрешил вход. Повторите или используйте токен с доступом к музыке" else null
}

@Composable
internal fun VkLoginDialog(onToken: (String) -> Unit, onClose: () -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            kotlinx.coroutines.delay(30_000)
            if (loading && !handled) {
                loading = false
                failure = "Страница VK не завершила загрузку. Проверьте соединение и повторите вход"
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            browser?.apply { stopLoading(); destroy() }
            browser = null
            CookieManager.getInstance().flush()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler {
            if (browser?.canGoBack() == true) browser?.goBack() else onClose()
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрыть") }
                    Column(Modifier.weight(1f)) {
                        Text("Подключить VK", style = MaterialTheme.typography.titleMedium)
                        Text("Вход на сайте VK · пароль не сохраняется приложением",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                failure?.let { message ->
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Text("Если сайт не открывается, обновите Android System WebView и Chrome. Не меняйте сеть во время входа.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { failure = null; loading = true; browser?.loadUrl(VK_AUTH_URL) }) {
                            Text("Повторить вход")
                        }
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            browser = this
                            webChromeClient = android.webkit.WebChromeClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                private fun capture(url: String): Boolean {
                                    val result = vkLoginCallback(url) ?: return false
                                    if (handled) return true
                                    result.first?.let {
                                        handled = true
                                        stopLoading()
                                        onToken(it)
                                        return true
                                    }
                                    result.second?.let { failure = it; loading = false; return true }
                                    return false
                                }
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (!request.isForMainFrame) return false
                                    if (capture(request.url.toString())) return true
                                    // Do not let native-app deep links strand the embedded OAuth flow.
                                    if (request.url.scheme !in listOf("https", "http")) {
                                        failure = "VK предложил открыть внешнее приложение. Вернитесь и выберите вход на сайте"
                                        loading = false
                                        return true
                                    }
                                    return false
                                }
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (!capture(url)) { loading = true; failure = null }
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false
                                    capture(url)
                                    CookieManager.getInstance().flush()
                                }
                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    capture(url)
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) {
                                        loading = false
                                        failure = "Не удалось загрузить страницу VK. Проверьте интернет и повторите вход"
                                    }
                                }
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame) {
                                        loading = false
                                        failure = "Страница входа VK ответила HTTP ${response.statusCode}. Повторите позже"
                                    }
                                }
                                // SSL errors retain WebView's default cancellation; never bypass certificate checks.
                            }
                            loadUrl(VK_AUTH_URL)
                        }
                    },
                )
            }
        }
    }
}
