package com.airi.assistant.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's chosen theme mode across process restarts.
 *
 * Three modes are supported:
 *  - [ThemeMode.DARK]   — always dark (default, matches original app behaviour)
 *  - [ThemeMode.LIGHT]  — always light
 *  - [ThemeMode.SYSTEM] — follows the OS dark/light setting
 *
 * Stored in a dedicated SharedPreferences file so it can be cleared
 * independently of execution or privacy prefs. Exposes [themeMode] as a
 * [StateFlow] so Compose collectors recompose immediately when the value
 * changes without requiring a process restart.
 */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

class ThemePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readFromDisk())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    var mode: ThemeMode
        get() = _themeMode.value
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
            _themeMode.value = value
        }

    private fun readFromDisk(): ThemeMode =
        prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK

    companion object {
        private const val PREFS_FILE      = "airi_theme_prefs"
        private const val KEY_THEME_MODE  = "theme_mode"

        /** App-wide singleton so Compose collectors share the same StateFlow. */
        @Volatile private var instance: ThemePreferences? = null
        fun get(context: Context): ThemePreferences =
            instance ?: synchronized(this) {
                instance ?: ThemePreferences(context.applicationContext).also { instance = it }
            }
    }
}
