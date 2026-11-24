package com.aero.voice

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Theme mapping. Dark + System both resolve to the dark theme, Light stays as before. */
object Theme {
    fun mode(ctx: Context): Int = when (Prefs.theme(ctx)) {
        Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }

    fun apply(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(mode(ctx))
    }
}
