package com.airi.assistant.connector

/** Canonical catalog-to-runtime identity; aliases must not be reintroduced in feature code. */
data class ConnectorRuntimeDescriptor(
    val catalogId: String,
    val runtimeId: String,
    val provider: String,
)

object ConnectorRuntimeDescriptors {
    private val shared = mapOf(
        "google_gmail" to ConnectorRuntimeDescriptor("google_gmail", "google", "Google"),
        "google_calendar" to ConnectorRuntimeDescriptor("google_calendar", "google", "Google"),
        "google_drive" to ConnectorRuntimeDescriptor("google_drive", "google", "Google"),
        "google_docs" to ConnectorRuntimeDescriptor("google_docs", "google", "Google"),
        "google_sheets" to ConnectorRuntimeDescriptor("google_sheets", "google", "Google"),
        "google_contacts" to ConnectorRuntimeDescriptor("google_contacts", "google", "Google"),
        "google_tasks" to ConnectorRuntimeDescriptor("google_tasks", "google", "Google"),
        "microsoft_outlook" to ConnectorRuntimeDescriptor("microsoft_outlook", "microsoft_graph", "Microsoft"),
        "microsoft_calendar" to ConnectorRuntimeDescriptor("microsoft_calendar", "microsoft_graph", "Microsoft"),
        "microsoft_onedrive" to ConnectorRuntimeDescriptor("microsoft_onedrive", "microsoft_graph", "Microsoft"),
        "microsoft_teams" to ConnectorRuntimeDescriptor("microsoft_teams", "microsoft_graph", "Microsoft"),
        "microsoft_todo" to ConnectorRuntimeDescriptor("microsoft_todo", "microsoft_graph", "Microsoft"),
        "notion" to ConnectorRuntimeDescriptor("notion", "notion_mcp", "Notion"),
    )

    fun forCatalogId(catalogId: String): ConnectorRuntimeDescriptor =
        shared[catalogId] ?: ConnectorRuntimeDescriptor(catalogId, catalogId, "")

    fun runtimeIdFor(catalogId: String): String = forCatalogId(catalogId).runtimeId
}
