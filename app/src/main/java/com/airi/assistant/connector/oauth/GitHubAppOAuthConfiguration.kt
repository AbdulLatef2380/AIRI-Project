package com.airi.assistant.connector.oauth

/** Public, non-secret configuration for the GitHub App installation flow. */
object GitHubAppOAuthConfiguration {
    const val CONNECTOR_ID = "github"
    const val CALLBACK_URI = "airi://oauth/callback"
    const val BROKER_BASE_URL = "https://github-broker.example.com"

    fun isConfigured(): Boolean = BROKER_BASE_URL.startsWith("https://") &&
        !BROKER_BASE_URL.contains("example.com")
}
