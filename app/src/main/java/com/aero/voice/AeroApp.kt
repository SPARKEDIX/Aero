package com.aero.voice

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Applies the saved color theme before any Activity is created. */
class AeroApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Theme.apply(this)

        // Track whether the main app UI is actually visible. A transparent
        // activity (AssistantProxyActivity) does NOT stop MainActivity, so this
        // stays true while the app is on screen — letting us ignore assistant
        // invokes (power button long-press) that happen inside the app.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity is MainActivity) mainVisible = true
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity is MainActivity) mainVisible = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        @Volatile
        var instance: AeroApp? = null
            private set

        /** True while the main app UI is visible on screen. */
        @Volatile
        var mainVisible = false
    }
}
