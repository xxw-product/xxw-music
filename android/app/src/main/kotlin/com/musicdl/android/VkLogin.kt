package com.musicdl.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.vk.id.VKID
import com.vk.id.AccessToken
import com.vk.id.VKIDAuthFail
import com.vk.id.auth.VKIDAuthCallback
import com.vk.id.auth.VKIDAuthParams

/**
 * VK ID authorization for XXW Music.
 *
 * The old implementation embedded oauth.vk.ru in a WebView and explicitly blocked
 * external VK app links. That is the reason the current flow can end on a blank page
 * after choosing "Войти через приложение". VK's current Android SDK owns the browser/app
 * hand-off and the redirect back into the Android package.
 */
@Composable
internal fun VkLoginDialog(onToken: (String) -> Unit, onClose: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(enabled = !busy) { onClose() }

    Dialog(
        onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Подключить VK", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Авторизация через VK ID",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IconButton(onClick = onClose, enabled = !busy) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                    }
                }

                failure?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            failure = null
                            VKID.instance.authorize(
                                lifecycleOwner = lifecycleOwner,
                                callback = object : VKIDAuthCallback {
                                    override fun onAuth(accessToken: AccessToken) {
                                        busy = false
                                        onToken(accessToken.token)
                                    }

                                    override fun onFail(fail: VKIDAuthFail) {
                                        busy = false
                                        failure = when (fail) {
                                            is VKIDAuthFail.Canceled -> "Вход отменён"
                                            else -> "VK ID не завершил авторизацию. Повторите вход."
                                        }
                                    }
                                },
                                params = VKIDAuthParams {
                                    // Required by the existing VKMusicClient for audio.search.
                                    scopes = setOf("audio", "offline")
                                },
                            )
                        },
                    ) {
                        Text("Войти через VK ID")
                    }
                }

                Text(
                    "Пароль вводится на стороне VK ID и не передаётся приложению.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
