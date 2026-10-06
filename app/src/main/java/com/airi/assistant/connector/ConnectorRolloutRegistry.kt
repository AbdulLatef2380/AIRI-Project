package com.airi.assistant.connector

/** Delivery waves for the remaining connector catalog. */
enum class ConnectorRolloutBatch {
    LIVE_ADAPTERS,
    MICROSOFT,
    COMMUNICATION,
    FILES,
    PRODUCTIVITY,
    DEVELOPMENT,
    AUTOMATION,
    DESIGN_AND_MEETINGS,
}

enum class ConnectorAdapterReadiness {
    LIVE,
    CATALOG_ONLY,
    CONFIGURATION_REQUIRED,
}

data class ConnectorRolloutEntry(
    val connectorId: String,
    val provider: String,
    val batch: ConnectorRolloutBatch,
    val authMode: ConnectorAuthMode,
    val requiredAdapter: String,
    val readiness: ConnectorAdapterReadiness,
    val officialDocsUrl: String?,
) {
    val canStartAuthorization: Boolean
        get() = readiness == ConnectorAdapterReadiness.LIVE

    val blockedReason: String?
        get() = when (readiness) {
            ConnectorAdapterReadiness.LIVE -> null
            ConnectorAdapterReadiness.CATALOG_ONLY ->
                "The official AIRI runtime adapter for $provider is not installed yet."
            ConnectorAdapterReadiness.CONFIGURATION_REQUIRED ->
                "The $provider adapter is installed but provider configuration is required."
        }
}

/**
 * Single source of truth for rollout ownership. It is intentionally derived
 * from the catalog so a new catalog item cannot silently skip a delivery wave.
 */
object ConnectorRolloutRegistry {
    /** Catalog surfaces with a registered executable adapter. Do not infer this
     * from a shared runtime id: google and microsoft_graph each serve both
     * implemented and catalog-only surfaces. */
    private val liveCatalogIds = setOf(
        "google_gmail", "google_calendar", "google_drive", "google_docs", "google_sheets", "google_contacts", "google_tasks",
        "microsoft_outlook", "microsoft_calendar", "microsoft_onedrive", "microsoft_teams", "microsoft_todo",
        "github", "telegram", "notion", "zapier", "gitlab", "linear", "slack", "discord", "asana", "todoist", "figma",
    )

    /** Non-catalog runtimes are evaluated independently from catalog readiness. */
    private val liveRuntimeIds = setOf(
        "remote_llm", "android_intent", "voice_mtmd", "clipboard", "device_apps", "contacts", "system_info",
    )

    private val microsoft = setOf(
        "microsoft_outlook", "microsoft_calendar", "microsoft_onedrive",
        "microsoft_teams", "microsoft_sharepoint", "microsoft_todo",
    )
    private val communication = setOf("slack", "discord")
    private val files = setOf("dropbox", "box")
    private val productivity = setOf("trello", "asana", "clickup", "monday", "todoist")
    private val development = setOf("gitlab", "bitbucket", "jira", "linear")
    private val automation = setOf("airtable")
    private val designAndMeetings = setOf("figma", "canva", "zoom", "google_docs", "google_sheets", "google_contacts", "google_tasks", "google_meet")

    fun forDefinition(definition: ConnectorDefinition): ConnectorRolloutEntry {
        val id = definition.id
        val runtimeId = definition.runtimeConnectorId()
        val batch = when (id) {
            in microsoft -> ConnectorRolloutBatch.MICROSOFT
            in communication -> ConnectorRolloutBatch.COMMUNICATION
            in files -> ConnectorRolloutBatch.FILES
            in productivity -> ConnectorRolloutBatch.PRODUCTIVITY
            in development -> ConnectorRolloutBatch.DEVELOPMENT
            in automation -> ConnectorRolloutBatch.AUTOMATION
            in designAndMeetings -> ConnectorRolloutBatch.DESIGN_AND_MEETINGS
            else -> ConnectorRolloutBatch.LIVE_ADAPTERS
        }
        val mode = ConnectorAuthStrategies.forMeta(definition.toConnectorMeta()).mode
        val readiness = when {
            id in liveCatalogIds -> ConnectorAdapterReadiness.LIVE
            id !in OfficialConnectorCatalog.all.map { it.id } && runtimeId in liveRuntimeIds -> ConnectorAdapterReadiness.LIVE
            definition.status == ConnectorAvailability.PARTIAL -> ConnectorAdapterReadiness.CONFIGURATION_REQUIRED
            else -> ConnectorAdapterReadiness.CATALOG_ONLY
        }
        return ConnectorRolloutEntry(
            connectorId = id,
            provider = definition.provider,
            batch = batch,
            authMode = mode,
            requiredAdapter = if (id in setOf("gitlab", "linear", "slack", "discord", "asana", "todoist", "figma")) {
                "ProviderTokenConnector"
            } else {
                "${definition.provider.replace(" ", "")}${definition.name.replace(" ", "")}Connector"
            },
            readiness = readiness,
            officialDocsUrl = definition.documentationUrl ?: definition.website,
        )
    }

    fun all(): List<ConnectorRolloutEntry> = OfficialConnectorCatalog.all.map(::forDefinition)

    fun get(id: String): ConnectorRolloutEntry? =
        OfficialConnectorCatalog.get(id)?.let(::forDefinition)

    fun forBatch(batch: ConnectorRolloutBatch): List<ConnectorRolloutEntry> =
        all().filter { it.batch == batch }

    fun missingAdapters(): List<ConnectorRolloutEntry> =
        all().filter { it.readiness != ConnectorAdapterReadiness.LIVE }
}
