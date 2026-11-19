package com.musicdl.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

/** Per-app VPN service. Android routes only this package into the TUN interface. */
class MusicVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var coreController: Any? = null
    @Volatile private var starting = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId.isNullOrBlank()) {
                    publishState(STATE_ERROR, "Не выбран VPN-сервер")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Подключаем VPN…"))
                startTunnel(profileId)
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(profileId: String) {
        if (starting || coreController != null) return
        starting = true
        publishState(STATE_CONNECTING, "Подготавливаем защищённое подключение…")
        worker.execute {
            try {
                val profile = VpnSubscriptionRepository(this).getProfiles().firstOrNull { it.id == profileId }
                    ?: error("Сервер больше не найден. Обновите список подписок.")
                // Resolve the server now, while the tunnel does not exist yet. Xray would otherwise
                // resolve it through the tunnel, and its connection to the server would loop back in.
                val serverAddresses = resolveServer(profile.server)
                val serverIp = (serverAddresses.firstOrNull { it is Inet4Address } ?: serverAddresses.first())
                    .hostAddress.orEmpty()
                val config = XrayConfigFactory.build(profile, serverIp)
                val builder = Builder()
                    .setSession("XXW Music VPN · ${profile.name}")
                    .setMtu(1500)
                    .addAddress("10.88.0.1", 30)
                    .addAddress("fd00:88::1", 126)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    // Strict allow-list: all other packages continue using the physical network.
                    .addAllowedApplication(packageName)
                // Everything except the VPN server itself goes into the tunnel.
                VpnRoutes.routesExcluding(serverAddresses).forEach { builder.addRoute(it.address, it.prefix) }
                val established = builder.establish() ?: error("Android не разрешил создать VPN-интерфейс")
                tun = established
                val controller = createXrayController()
                coreController = controller
                val start = controller.javaClass.methods.firstOrNull {
                    it.name == "startLoop" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: error("В libv2ray отсутствует метод CoreController.startLoop(String, int)")
                start.invoke(controller, config, established.fd)
                val running = controller.javaClass.methods.firstOrNull {
                    it.name.equals("getIsRunning", true) || it.name.equals("isRunning", true)
                }?.invoke(controller) as? Boolean ?: true
                if (!running) error("Xray не запустил туннель")
                // «Подключено» is only shown after a real request went through the server.
                val delay = measureDelay(controller)
                saveActiveProfile(profileId)
                val label = "Подключено · ${profile.name} · $delay мс"
                publishState(STATE_CONNECTED, label)
                updateNotification(label)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to start Xray VPN", error)
                stopCoreQuietly()
                runCatching { tun?.close() }
                tun = null
                coreController = null
                publishState(STATE_ERROR,
                    (error as? java.lang.reflect.InvocationTargetException)?.targetException?.message
                        ?: error.message ?: "Ошибка запуска VPN")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                starting = false
            }
        }
    }

    private fun resolveServer(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).toList().ifEmpty { error("пустой ответ DNS") }
    } catch (error: Exception) {
        throw IllegalStateException("Не удалось определить адрес сервера $host. Проверьте интернет.", error)
    }

    /** Round-trip through the proxy outbound; fails the connection if the server does not answer. */
    private fun measureDelay(controller: Any): Long {
        val method = controller.javaClass.methods.firstOrNull {
            it.name == "measureDelay" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        } ?: return 0L
        return try {
            method.invoke(controller, "https://www.gstatic.com/generate_204") as? Long ?: 0L
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(
                "Сервер не ответил на проверку: ${error.targetException?.message ?: "нет ответа"}. " +
                    "Проверьте, что сервер и ссылка рабочие.", error)
        }
    }

    /** Uses the exact gomobile API from AndroidLibXrayLite; reflection keeps the app buildable
     * before the generated AAR is added. Connection fails clearly until libv2ray.aar is present. */
    private fun createXrayController(): Any {
        val seq = try {
            Class.forName("go.Seq")
        } catch (_: ClassNotFoundException) {
            error("В эту сборку не вошла библиотека Xray (libv2ray.aar). Установите сборку из GitHub Actions.")
        }
        seq.getMethod("setContext", Context::class.java).invoke(null, applicationContext)
        val lib = Class.forName("libv2ray.Libv2ray")
        lib.getMethod("initCoreEnv", String::class.java, String::class.java)
            .invoke(null, "", xudpBaseKey())
        val callbackType = Class.forName("libv2ray.CoreCallbackHandler")
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, _ ->
            when (method.name.lowercase()) {
                "startup", "shutdown", "onemitstatus" -> 0L
                "tostring" -> "XXW Music Xray callback"
                "hashcode" -> System.identityHashCode(this).toLong()
                "equals" -> false
                else -> 0L
            }
        }
        return lib.getMethod("newCoreController", callbackType).invoke(null, callback)
    }

    private fun androidId(): String = runCatching {
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty().ifBlank { "xxw-music-android" }

    /** Xray wants exactly 32 bytes encoded as unpadded base64url; this one is stable per device. */
    private fun xudpBaseKey(): String = XudpKey.derive(androidId() + packageName)

    private fun stopTunnel() {
        worker.execute {
            stopCoreQuietly()
            coreController = null
            runCatching { tun?.close() }
            tun = null
            saveActiveProfile(null)
            publishState(STATE_DISCONNECTED, "VPN отключён")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopCoreQuietly() {
        val controller = coreController ?: return
        runCatching {
            controller.javaClass.methods.firstOrNull { it.name == "stopLoop" && it.parameterTypes.isEmpty() }
                ?.invoke(controller)
        }.onFailure { Log.w(TAG, "Xray stop failed", it) }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopCoreQuietly()
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, null) != STATE_ERROR) {
            publishState(STATE_DISCONNECTED, "VPN отключён")
        }
        runCatching { tun?.close() }
        tun = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun publishState(state: String, message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, state).putString(KEY_MESSAGE, message).apply()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE, state).putExtra(EXTRA_MESSAGE, message))
    }

    private fun saveActiveProfile(id: String?) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ACTIVE_PROFILE, id).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "XXW Music VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("XXW Music VPN").setContentText(text)
        .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val ACTION_START = "com.musicdl.android.vpn.START"
        const val ACTION_STOP = "com.musicdl.android.vpn.STOP"
        const val ACTION_STATE = "com.musicdl.android.vpn.STATE"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_ERROR = "error"
        const val PREFS = "vpn_runtime"
        const val KEY_STATE = "state"
        const val KEY_MESSAGE = "message"
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val KEY_SELECTED_PROFILE = "selected_profile"
        private const val CHANNEL_ID = "xxw_music_vpn"
        private const val NOTIFICATION_ID = 7312
        private const val TAG = "MusicVpnService"

        fun start(context: Context, profileId: String) {
            val intent = Intent(context, MusicVpnService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_PROFILE_ID, profileId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MusicVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
