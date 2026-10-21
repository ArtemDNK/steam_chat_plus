package com.artemdnk.steamchatplus

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.createChannels(this)
        SteamState.addIncomingListener { Notifier.syncIncoming(this, it) }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
