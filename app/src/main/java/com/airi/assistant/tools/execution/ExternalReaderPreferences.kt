package com.airi.assistant.tools.execution

import android.content.Context

/**
 * Report 3, S7: Jina Reader (r.jina.ai) fetches pages from Jina's servers, so the
 * requested URL, including any token or identifier in its query string, is
 * disclosed to a third party. The reader is therefore opt-in. When it is off,
 * `fetch_url` uses the direct on-device fetch, which still passes the redirect
 * and address checks in [SearchTool].
 */
object ExternalReaderPreferences {
    private const val PREFS = "airi_external_reader"
    private const val KEY_JINA_ENABLED = "jina_reader_enabled"

    /** Privacy-first default: nothing leaves the device for a third-party reader until the user opts in. */
    const val DEFAULT_JINA_ENABLED = false

    fun isJinaReaderEnabled(context: Context): Boolean =
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_JINA_ENABLED, DEFAULT_JINA_ENABLED)
        }.getOrDefault(DEFAULT_JINA_ENABLED)

    fun setJinaReaderEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_JINA_ENABLED, enabled)
                .apply()
        }
    }
}
