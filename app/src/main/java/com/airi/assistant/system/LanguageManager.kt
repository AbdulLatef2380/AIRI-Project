package com.airi.assistant.system

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LanguageManager {
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_ARABIC = "ar"
    const val LANGUAGE_CHINESE = "zh"
    const val LANGUAGE_SPANISH = "es"

    private const val PREFS_NAME = "airi_language_settings"
    private const val KEY_LANGUAGE = "selected_language"
    private const val KEY_WARNING_PREFIX = "warning_shown_"

    val supportedLanguages = listOf(
        LanguageOption(LANGUAGE_ENGLISH, "English", "English", ""),
        LanguageOption(LANGUAGE_ARABIC, "Arabic", "العربية", ""),
        LanguageOption(LANGUAGE_CHINESE, "Chinese", "中文", ""),
        LanguageOption(LANGUAGE_SPANISH, "Spanish", "Español", "")
    )

    fun getCurrentLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANGUAGE_ENGLISH)
            ?.takeIf { code -> supportedLanguages.any { it.code == code } }
            ?: LANGUAGE_ENGLISH
    }

    fun saveLanguage(context: Context, languageCode: String) {
        val safeCode = languageCode.takeIf { code -> supportedLanguages.any { it.code == code } } ?: LANGUAGE_ENGLISH
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, safeCode)
            .apply()
    }

    fun applyLanguage(activity: Activity, languageCode: String) {
        saveLanguage(activity, languageCode)
        applyLocale(activity, languageCode)
        activity.recreate()
    }

    fun applyLocale(context: Context): Context {
        return applyLocale(context, getCurrentLanguage(context))
    }

    fun applyLocale(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
            context
        }
    }

    fun shouldShowPerformanceWarning(context: Context, languageCode: String): Boolean {
        if (languageCode == LANGUAGE_ENGLISH) return false
        return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WARNING_PREFIX + languageCode, false)
    }

    fun markPerformanceWarningShown(context: Context, languageCode: String) {
        if (languageCode == LANGUAGE_ENGLISH) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WARNING_PREFIX + languageCode, true)
            .apply()
    }

    fun getLanguageOption(languageCode: String): LanguageOption {
        return supportedLanguages.firstOrNull { it.code == languageCode } ?: supportedLanguages.first()
    }
}

data class LanguageOption(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val flag: String
) {
    val displayName: String
        get() = "$flag $nativeName"
}
