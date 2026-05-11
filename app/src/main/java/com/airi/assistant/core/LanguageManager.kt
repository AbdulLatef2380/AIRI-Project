package com.airi.assistant.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * LanguageManager — centralised language/locale management for AIRI.
 *
 * Supports Arabic (ar) and English (en). The user's choice is persisted in
 * SharedPreferences and applied at Activity startup via [applyLocale].
 *
 * ## Usage
 * ```kotlin
 * // In Application.onCreate()
 * LanguageManager.init(this)
 *
 * // In Activity.attachBaseContext()
 * override fun attachBaseContext(base: Context) {
 *     super.attachBaseContext(LanguageManager.wrapContext(base))
 * }
 *
 * // Changing language at runtime (triggers UI restart)
 * LanguageManager.setLanguage(context, LanguageManager.Language.ARABIC)
 * ```
 *
 * ## RTL
 * Arabic is an RTL language. Setting locale to "ar" automatically flips
 * layout direction in Compose (LayoutDirection.Rtl) via the system
 * configuration. No additional code is needed in Compose UI.
 */
object LanguageManager {

    enum class Language(
        val code: String,
        val displayName: String,
        val nativeName: String,
        val isRtl: Boolean
    ) {
        ARABIC("ar", "Arabic", "العربية", true),
        ENGLISH("en", "English", "English", false);

        companion object {
            fun fromCode(code: String): Language =
                entries.firstOrNull { it.code == code } ?: ENGLISH
        }
    }

    private const val PREFS_NAME = "airi_language"
    private const val KEY_LANG   = "language_code"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    val currentLanguage: Language
        get() = Language.fromCode(
            if (::prefs.isInitialized) prefs.getString(KEY_LANG, Language.ARABIC.code) ?: Language.ARABIC.code
            else Language.ARABIC.code
        )

    val currentLocale: Locale
        get() = Locale(currentLanguage.code)

    val isRtl: Boolean get() = currentLanguage.isRtl

    // ── Write ─────────────────────────────────────────────────────────────────

    fun setLanguage(language: Language) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_LANG, language.code).apply()
    }

    // ── Context wrapping ──────────────────────────────────────────────────────

    /**
     * Wrap [base] context with the user's chosen locale so that Android
     * resolves string resources from the correct values-XX folder.
     *
     * Call this from `Activity.attachBaseContext`.
     */
    fun wrapContext(base: Context): Context {
        val locale = currentLocale
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Apply locale to a context directly (useful for non-Activity contexts).
     */
    fun applyLocale(context: Context): Context = wrapContext(context)

    // ── Available options ─────────────────────────────────────────────────────

    val availableLanguages: List<Language> = Language.entries.toList()

    fun toggleLanguage() {
        setLanguage(
            if (currentLanguage == Language.ARABIC) Language.ENGLISH else Language.ARABIC
        )
    }
}
