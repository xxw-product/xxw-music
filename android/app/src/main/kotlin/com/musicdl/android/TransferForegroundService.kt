package com.musicdl.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps long Yandex/playlist transfers alive after the activity is backgrounded.
 * The existing transfer engine is reused; this service only owns its lifetime and notification.
 */
class TransferForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private var viewModel: SearchViewModel? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Подготовка переноса…", 0, false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        running = true
        val vm = SearchViewModel(application)
        viewModel = vm

        scope.launch {
            vm.state.collectLatest { state ->
                val downloading = state.saves.values.count { it == SaveState.RUNNING }
                val text = when {
                    state.isImporting && state.importProgress.isNotBlank() -> state.importProgress
                    downloading > 0 -> "Скачивание · активно $downloading"
                    else -> "Перенос выполняется…"
                }
                val notification = notification(text, 0, false)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        }

        val finished: () -> Unit = {
            scope.launch {
                val state = vm.state.value
                val result = state.linkImportResult ?: state.importReport?.let {
                    "Готово: ${it.exact} точных, ${it.doubtful} под вопросом, ${it.unmatched} не найдено"
                } ?: "Перенос завершён"
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(result, 100, true),
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }

        when (intent.action) {
            ACTION_YANDEX -> vm.importYandexMusicInternal(finished)
            ACTION_PLAYLIST -> vm.importPlaylistLinkInternal(
                intent.getStringExtra(EXTRA_LINK).orEmpty(),
                intent.getBooleanExtra(EXTRA_DOWNLOAD, false),
                intent.getStringExtra(EXTRA_SYNC_ID),
                finished,
            )
            else -> finished()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String, progress: Int, finished: Boolean): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.musicdl.android.R.mipmap.ic_launcher)
            .setContentTitle(if (finished) "XXW Music · готово" else "XXW Music · текущее состояние")
            .setContentText(text)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 4107,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Перенос музыки",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Фоновый перенос плейлистов и треков" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        viewModel = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "music_transfer"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_YANDEX = "com.musicdl.android.transfer.YANDEX"
        private const val ACTION_PLAYLIST = "com.musicdl.android.transfer.PLAYLIST"
        private const val EXTRA_LINK = "link"
        private const val EXTRA_DOWNLOAD = "download"
        private const val EXTRA_SYNC_ID = "sync_id"

        fun startYandex(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).setAction(ACTION_YANDEX)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startPlaylist(context: Context, link: String, download: Boolean, syncId: String?) {
            val intent = Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_PLAYLIST)
                .putExtra(EXTRA_LINK, link)
                .putExtra(EXTRA_DOWNLOAD, download)
                .putExtra(EXTRA_SYNC_ID, syncId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
