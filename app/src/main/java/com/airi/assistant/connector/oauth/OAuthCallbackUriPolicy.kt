package com.airi.assistant.connector.oauth

/**
 * Structural allowlist for the OAuth redirect handled by MainActivity.
 * Query values are validated by OAuthStateRegistry and the connector manager;
 * this policy deliberately never reads or logs code/state values.
 */
object OAuthCallbackUriPolicy {
    const val SCHEME = "airi"
    const val HOST = "oauth"
    const val PATH = "/callback"

    fun accepts(scheme: String?, host: String?, path: String?, fragment: String?): Boolean =
        scheme == SCHEME && host == HOST && path == PATH && fragment == null
}
