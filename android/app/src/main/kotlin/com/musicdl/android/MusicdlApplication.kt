package com.musicdl.android

import android.app.Application
import com.vk.id.VKID

class MusicdlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VKID.init(this)
    }
}
