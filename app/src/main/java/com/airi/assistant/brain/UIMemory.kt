package com.airi.assistant.brain

import android.content.Context
import android.content.SharedPreferences

object UIMemory {

    private const val PREF_NAME = "airi_ui_memory"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun rememberNode(
        context: Context,
        label: String,
        identifier: String
    ) {

        prefs(context)
            .edit()
            .putString(label.lowercase(), identifier)
            .apply()
    }

    fun recallNode(
        context: Context,
        label: String
    ): String? {

        return prefs(context)
            .getString(label.lowercase(), null)
    }
}
