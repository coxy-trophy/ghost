package com.coxy.ghost

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Exists for one line, and that line has to run before anything resolves a resource.
 *
 * The appearance chosen in System is applied here rather than in `MainActivity.onCreate`. Setting it
 * there — even before `super.onCreate` — left the app in a state where half its resources came from
 * one mode and half from the other: the page painted cream from the re-resolved theme while every
 * colour came from `values-night`, so her replies were near-white text on paper and the user's
 * bubbles were inverted. Nothing crashed and nothing logged; it was simply unreadable.
 *
 * `Application.onCreate` runs once, before any Activity or Service exists, which is early enough for
 * the whole process to agree about which mode it is in.
 *
 * The value is read from SharedPreferences rather than from [GhostPrefs] because this cannot wait on
 * a coroutine — see [MainActivity.savedNightMode].
 */
class GhostApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(MainActivity.savedNightMode(this))
    }
}
