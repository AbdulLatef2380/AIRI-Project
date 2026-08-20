package com.airi.assistant.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.regex.Pattern

/**
 * LanguageRuntimeManager — detects dominant text direction for a string
 * and provides utilities for mixed Arabic/English rendering.
 *
 * Does NOT change the global layout direction — it provides per-string
 * direction hints consumed by [BidiAwareMarkdownRenderer].
 */
object LanguageRuntimeManager {

    private val ARABIC_RANGE  = Pattern.compile("[\\u0600-\\u06FF\\u0750-\\u077F]+")
    private val ENGLISH_RANGE = Pattern.compile("[a-zA-Z]{3,}")

    enum class DominantDirection { RTL, LTR, MIXED, NEUTRAL }

    /** Analyse a string and return its dominant text direction. */
    fun analyseDirection(text: String): DominantDirection {
        if (text.isBlank()) return DominantDirection.NEUTRAL
        val hasArabic = ARABIC_RANGE.matcher(text).find()
        val hasEnglish = ENGLISH_RANGE.matcher(text).find()
        return when {
            hasArabic && !hasEnglish -> DominantDirection.RTL
            hasEnglish && !hasArabic -> DominantDirection.LTR
            hasArabic && hasEnglish  -> DominantDirection.MIXED
            else                     -> DominantDirection.NEUTRAL
        }
    }

    /** Map [DominantDirection] to Compose [LayoutDirection]. */
    fun toLayoutDirection(direction: DominantDirection): LayoutDirection = when (direction) {
        DominantDirection.RTL     -> LayoutDirection.Rtl
        DominantDirection.LTR     -> LayoutDirection.Ltr
        DominantDirection.MIXED   -> LayoutDirection.Rtl   // Arabic-first for AIRI
        DominantDirection.NEUTRAL -> LayoutDirection.Rtl   // default app direction
    }

    /**
     * Wrap inline English words inside Arabic text with Unicode LTR isolate marks
     * so BiDi algorithm renders them correctly without flipping punctuation.
     *
     * U+2066 = LTR Isolate, U+2069 = Pop Directional Isolate
     */
    fun isolateLatinRuns(text: String): String {
        if (!text.contains(Regex("[a-zA-Z]"))) return text
        return text.replace(Regex("([a-zA-Z0-9][a-zA-Z0-9 _\\-./]*[a-zA-Z0-9])")) {
            "\u2066${it.value}\u2069"
        }
    }

    /** Strip all directional Unicode control characters for plain-text contexts. */
    fun stripBidiMarks(text: String): String =
        text.replace(Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]"), "")
}

/** CompositionLocal providing the current text direction hint. */
val LocalTextDirectionHint = staticCompositionLocalOf {
    LanguageRuntimeManager.DominantDirection.NEUTRAL
}

/** Wrap a composable subtree with an explicit [LayoutDirection] derived from [text]. */
@Composable
fun DirectionalContent(text: String, content: @Composable () -> Unit) {
    val direction = LanguageRuntimeManager.analyseDirection(text)
    val layoutDir = LanguageRuntimeManager.toLayoutDirection(direction)
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDir,
        LocalTextDirectionHint provides direction,
        content = content
    )
}
