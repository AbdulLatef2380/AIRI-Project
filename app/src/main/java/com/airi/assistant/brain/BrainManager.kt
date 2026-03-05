package com.airi.assistant.brain

import android.util.Log

object BrainManager {

    fun processScreen(screenText: String) {

        Log.d("AIRI_BRAIN", "Processing screen...")

        if (screenText.contains("YouTube", true)) {

            Log.d("AIRI_BRAIN", "Detected YouTube")

        }

        if (screenText.contains("Settings", true)) {

            Log.d("AIRI_BRAIN", "Detected Settings")

        }
    }
}
