package com.musicdl.android

import android.app.Application

class MusicdlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A fresh process cannot have a live tunnel (the service is not sticky): clear a stale
        // «connected» left behind by a killed process so the UI never shows a false state.
        getSharedPreferences(MusicVpnService.PREFS, MODE_PRIVATE).edit()
            .putString(MusicVpnService.KEY_STATE, MusicVpnService.STATE_DISCONNECTED)
            .putString(MusicVpnService.KEY_MESSAGE, "").apply()
    }
}
