package com.airi.assistant.brain

import android.util.Log

object BrainManager {

    fun processScreen(screenText: String) {

        Log.d("AIRI_BRAIN", "Analyzing screen")

        val command = IntentEngine.resolve(screenText)

        if (command != null) {

            Log.d("AIRI_BRAIN", "Command detected: $command")

            IntentEngine.execute(command)
        }
    }
}
