package com.musicdl.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * One compact, ongoing status notification for long-running XXW operations.
 * One UI can surface ongoing notifications in the Now Bar when the device/build supports it.
 */
object NowBarStatus {
    private const val CHANNEL_ID = "xxw_status"
    private const val NOTIFICATION_ID = 4110

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            manager(context).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "XXW Music · состояние",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Переносы, скачивания и другие длительные операции XXW Music"
                }
            )
        }
    }

    private fun base(context: Context, title: String, text: String, ongoing: Boolean): NotificationCompat.Builder {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 4110,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
    }

    fun show(context: Context, title: String, text: String, indeterminate: Boolean = true) {
        manager(context).notify(
            NOTIFICATION_ID,
            base(context, title, text, true)
                .setProgress(100, 0, indeterminate)
                .build()
        )
    }

    fun complete(context: Context, text: String) {
        manager(context).notify(
            NOTIFICATION_ID,
            base(context, "XXW Music", text, false)
                .setProgress(0, 0, false)
                .build()
        )
    }

    fun clear(context: Context) {
        manager(context).cancel(NOTIFICATION_ID)
    }
}
