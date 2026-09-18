package com.airi.assistant.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's chosen theme mode across process restarts.
 *
 * Four modes are supported:
     *  - [ThemeMode.DARK]   — dark graphite surfaces
 *  - [ThemeMode.LIGHT]  — always light
 *  - [ThemeMode.SYSTEM] — follows the OS dark/light setting
 *  - [ThemeMode.AMOLED] — pure black background for OLED power saving ()
 */
enum class ThemeMode { DARK, LIGHT, SYSTEM, AMOLED }

class ThemePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readFromDisk())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    var mode: ThemeMode
        get() = _themeMode.value
        set(value) {
            prefs.edit()
                .putString(KEY_THEME_MODE, value.name)
                .putBoolean(KEY_THEME_MODE_EXPLICIT, true)
                .apply()
            _themeMode.value = value
        }

    private fun readFromDisk(): ThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, null)
        val explicit = prefs.getBoolean(KEY_THEME_MODE_EXPLICIT, false)
        // AMOLED was the historical implicit default. Treat that legacy value as
        // SYSTEM unless the user explicitly selected AMOLED, so old installs do
        // not remain visually stuck in the dark palette after the theme fix.
        if (raw == ThemeMode.AMOLED.name && !explicit) return ThemeMode.SYSTEM
        return raw?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    companion object {
        private const val PREFS_FILE      = "airi_theme_prefs"
        private const val KEY_THEME_MODE  = "theme_mode"
        private const val KEY_THEME_MODE_EXPLICIT = "theme_mode_explicit"

        /** App-wide singleton so Compose collectors share the same StateFlow. */
        @Volatile private var instance: ThemePreferences? = null
        fun get(context: Context): ThemePreferences =
            instance ?: synchronized(this) {
                instance ?: ThemePreferences(context.applicationContext).also { instance = it }
            }
    }
}
