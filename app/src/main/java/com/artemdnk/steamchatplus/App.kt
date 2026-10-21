package com.artemdnk.steamchatplus

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.createChannels(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
