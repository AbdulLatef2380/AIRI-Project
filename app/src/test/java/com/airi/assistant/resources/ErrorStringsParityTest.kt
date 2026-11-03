package com.airi.assistant.resources

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report 1, item 37 (stage 20). The phone once showed an Arabic error with the
 * plural "إجابات كاملة" that no longer exists in the sources. These checks keep
 * the error strings identical in key set across the four shipped locales and
 * keep that stale wording from coming back.
 */
class ErrorStringsParityTest {
    private val locales = mapOf(
        "en" to "values",
        "ar" to "values-ar",
        "es" to "values-es",
        "zh" to "values-zh",
    )

    private fun resDir(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("res directory not found from ${File(".").absolutePath}")
    }

    private fun load(folder: String): Map<String, String> {
        val text = File(resDir(), "$folder/strings.xml").readText(Charsets.UTF_8)
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun everyLocaleDefinesTheSameStringKeys() {
        val base = load("values").keys
        locales.filterKeys { it != "en" }.forEach { (code, folder) ->
            val keys = load(folder).keys
            assertEquals("$code is missing keys", emptySet<String>(), base - keys)
            assertEquals("$code has keys that en lacks", emptySet<String>(), keys - base)
        }
    }

    @Test
    fun errorAndPartialResponseStringsAreNeverBlank() {
        val required = listOf(
            "err_agent_loop_timeout",
            "err_incomplete_response",
            "chat_partial_response_saved",
            "err_model_still_loading",
        )
        locales.forEach { (code, folder) ->
            val strings = load(folder)
            required.forEach { key ->
                assertTrue("$code/$key is blank", strings[key].orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun staleFullAnswersWordingIsGone() {
        locales.forEach { (code, folder) ->
            load(folder).forEach { (key, value) ->
                assertFalse("$code/$key still says \"إجابات كاملة\"", value.contains("إجابات كاملة"))
            }
        }
    }
}
