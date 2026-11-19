package com.musicdl.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Re-downloads every saved VPN subscription once an hour while the phone has a network. */
class SubscriptionRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(MusicVpnService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MusicVpnService.KEY_AUTO_UPDATE, true)) return Result.success()
        val repository = VpnSubscriptionRepository(applicationContext)
        val urls = runCatching { repository.getProfiles() }.getOrDefault(emptyList())
            .mapNotNull { it.subscriptionUrl }.distinct()
        if (urls.isEmpty()) return Result.success()
        var failed = 0
        urls.forEach { url -> runCatching { repository.refresh(url) }.onFailure { failed++ } }
        if (failed < urls.size) prefs.edit().putLong(MusicVpnService.KEY_LAST_REFRESH, System.currentTimeMillis()).apply()
        return if (failed == urls.size) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "vpn-subscription-refresh"

        /** Keeps the hourly job scheduled (or cancels it); the existing schedule is not reset on every launch. */
        fun apply(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) { manager.cancelUniqueWork(WORK_NAME); return }
            val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
