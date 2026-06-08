package com.aide.claudecast

import android.app.Application

class ClaudeCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ServiceLocator.player.connect()
    }
}
