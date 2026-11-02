package com.eyescroll.app

import android.content.Context

/** Shared live state between the tracking service and the UI. */
object EyeState {
    @Volatile var running = false
    @Volatile var faceFound = false
    @Volatile var down = 0f
    @Volatile var up = 0f
    @Volatile var baseDown = 0f
    @Volatile var baseUp = 0f
    @Volatile var calibrateUntil = 0L
    @Volatile var error: String? = null
    @Volatile var lastAction = "-"
}

object Prefs {
    fun sp(c: Context) = c.getSharedPreferences("eyescroll", Context.MODE_PRIVATE)
    fun threshold(c: Context) = sp(c).getFloat("threshold", 0.20f)
    fun onlyInstagram(c: Context) = sp(c).getBoolean("only_ig", true)
}
