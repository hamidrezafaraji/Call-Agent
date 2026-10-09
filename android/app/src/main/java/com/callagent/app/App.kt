package com.callagent.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Prefs(this).isActivated) SyncWorker.schedule(this)
    }
}
