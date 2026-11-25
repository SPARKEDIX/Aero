package com.aero.voice

import android.app.Application

/** Applies the saved color theme before any Activity is created. */
class AeroApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Theme.apply(this)
    }

    companion object {
        @Volatile
        var instance: AeroApp? = null
            private set
    }
}
