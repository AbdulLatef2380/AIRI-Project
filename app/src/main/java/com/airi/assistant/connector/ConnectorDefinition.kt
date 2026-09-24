package com.airi.assistant.connector

/** Honest readiness states for catalog entries. A catalog entry is not an executable connector. */
enum class ConnectorAvailability {
    READY,
    PARTIAL,
    COMING_SOON,
}

enum class ConnectorAuthenticationType {
    OAUTH2,
    API_KEY,
    PERSONAL_ACCESS_TOKEN,
    OAUTH2_AND_API,
    MCP,
    WEBHOOK,
    LOCAL,
    NONE,
}

enum class ConnectorPermissionLevel {
    READ,
    WRITE,
    DESTRUCTIVE,
    ADMIN,
}

data class ConnectorCapability(
    val id: String,
    val description: String,
    val permission: ConnectorPermissionLevel = ConnectorPermissionLevel.READ,
    val requiresConfirmation: Boolean = permission != ConnectorPermissionLevel.READ,
)

/**
 * Provider-facing catalog metadata. This is deliberately separate from [Connector]
 * so Coming Soon entries cannot accidentally be invoked as live integrations.
 */
data class ConnectorDefinition(
    val id: String,
    val name: String,
    val displayName: String = name,
    val description: String,
    val longDescription: String = description,
    val category: String,
    val provider: String,
    val icon: String? = null,
    val version: String = "1.0",
    val author: String = "AIRI",
    val website: String? = null,
    val privacyPolicyUrl: String? = null,
    val documentationUrl: String? = null,
    val connectorType: ConnectorType,
    val authenticationType: ConnectorAuthenticationType,
    val capabilities: List<ConnectorCapability> = emptyList(),
    val requiredScopes: List<String> = emptyList(),
    val optionalScopes: List<String> = emptyList(),
    val permissions: List<ConnectorPermissionLevel> = emptyList(),
    val status: ConnectorAvailability,
    val enabled: Boolean = status != ConnectorAvailability.COMING_SOON,
    val requiresUserConfirmation: Boolean = capabilities.any { it.requiresConfirmation },
    val supportedPlatforms: Set<String> = setOf("android"),
    val supportedActions: List<String> = emptyList(),
    val supportedTriggers: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/**
 * Central catalog. Entries marked Coming Soon are descriptive only and are never
 * registered as executable connectors until a real adapter and tests exist.
 */
object OfficialConnectorCatalog {
    private fun soon(
        id: String,
        name: String,
        category: String,
        auth: ConnectorAuthenticationType,
        provider: String = name,
        website: String,
        tags: List<String> = emptyList(),
    ) = ConnectorDefinition(
        id = id,
        name = name,
        description = "$name integration for AIRI.",
        longDescription = "The AIRI connector for $name will expose only capabilities verified against the provider's official API.",
        category = category,
        provider = provider,
        website = website,
        connectorType = ConnectorType.APP,
        authenticationType = auth,
        status = ConnectorAvailability.COMING_SOON,
        tags = tags.ifEmpty { listOf(name.lowercase()) },
        limitations = listOf("Official provider credentials and an AIRI adapter are required before this connector can be enabled."),
    )

    private fun partial(
        id: String,
        name: String,
        category: String,
        auth: ConnectorAuthenticationType,
        provider: String = name,
        website: String,
        docs: String? = null,
        capabilities: List<ConnectorCapability> = emptyList(),
        tags: List<String> = emptyList(),
        limitations: List<String> = emptyList(),
    ) = ConnectorDefinition(
        id = id,
        name = name,
        description = "$name integration for AIRI.",
        longDescription = "A real AIRI adapter exists, but production readiness still requires provider configuration and the validation gates listed below.",
        category = category,
        provider = provider,
        website = website,
        documentationUrl = docs,
        connectorType = ConnectorType.APP,
        authenticationType = auth,
        capabilities = capabilities,
        permissions = capabilities.map { it.permission }.distinct(),
        status = ConnectorAvailability.PARTIAL,
        tags = tags.ifEmpty { listOf(name.lowercase()) },
        limitations = limitations + "Provider sandbox and credentialed-device evidence are still required before Ready.",
    )

    val all: List<ConnectorDefinition> = listOf(
        partial("google_gmail", "Gmail", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://mail.google.com", capabilities = listOf(ConnectorCapability("email.read", "Read authorized mail")), tags = listOf("email", "mail", "google")),
        partial("google_calendar", "Google Calendar", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://calendar.google.com", capabilities = listOf(ConnectorCapability("calendar.read", "Read authorized events")), tags = listOf("calendar", "schedule", "google")),
        partial("google_drive", "Google Drive", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://drive.google.com", capabilities = listOf(ConnectorCapability("drive.read", "Search authorized files")), tags = listOf("files", "storage", "google")),
        soon("google_docs", "Google Docs", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://docs.google.com", tags = listOf("documents", "google")),
        soon("google_sheets", "Google Sheets", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://sheets.google.com", tags = listOf("spreadsheets", "google")),
        soon("google_contacts", "Google Contacts", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://contacts.google.com", tags = listOf("contacts", "google")),
        soon("google_tasks", "Google Tasks", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://tasks.google.com", tags = listOf("tasks", "google")),
        soon("google_meet", "Google Meet", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://meet.google.com", tags = listOf("meetings", "google")),
        soon("microsoft_outlook", "Outlook Mail", "Microsoft", "Microsoft".let { ConnectorAuthenticationType.OAUTH2 }, "Microsoft", "https://outlook.live.com", tags = listOf("email", "mail", "microsoft")),
        soon("microsoft_calendar", "Outlook Calendar", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://outlook.live.com/calendar", tags = listOf("calendar", "microsoft")),
        soon("microsoft_onedrive", "OneDrive", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://onedrive.live.com", tags = listOf("files", "storage", "microsoft")),
        soon("microsoft_teams", "Microsoft Teams", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://teams.microsoft.com", tags = listOf("communication", "microsoft")),
        soon("microsoft_sharepoint", "SharePoint", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://www.sharepoint.com", tags = listOf("files", "microsoft")),
        soon("microsoft_todo", "Microsoft To Do", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://to-do.live.com", tags = listOf("tasks", "microsoft")),
        partial("github", "GitHub", "Development", ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, website = "https://github.com", docs = "https://docs.github.com", capabilities = listOf(ConnectorCapability("repositories.read", "Read repositories"), ConnectorCapability("issues.read", "Read issues"), ConnectorCapability("issues.create", "Create issues", ConnectorPermissionLevel.WRITE, true)), tags = listOf("git", "code", "development")),
        soon("gitlab", "GitLab", "Development", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://gitlab.com", tags = listOf("git", "code")),
        soon("bitbucket", "Bitbucket", "Development", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://bitbucket.org", tags = listOf("git", "code")),
        soon("jira", "Jira", "Development", ConnectorAuthenticationType.OAUTH2_AND_API, provider = "Atlassian", website = "https://www.atlassian.com/software/jira", tags = listOf("issues", "development")),
        soon("linear", "Linear", "Development", ConnectorAuthenticationType.API_KEY, website = "https://linear.app", tags = listOf("issues", "development")),
        soon("slack", "Slack", "Communication", ConnectorAuthenticationType.OAUTH2, website = "https://slack.com", tags = listOf("chat", "messaging")),
        soon("discord", "Discord", "Communication", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://discord.com", tags = listOf("chat", "messaging")),
        partial("telegram", "Telegram", "Communication", ConnectorAuthenticationType.API_KEY, website = "https://telegram.org", docs = "https://core.telegram.org/bots/api", capabilities = listOf(ConnectorCapability("messages.send", "Send a message", ConnectorPermissionLevel.WRITE, true)), tags = listOf("chat", "messaging")),
        partial("notion", "Notion", "Productivity", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://www.notion.so", docs = "https://developers.notion.com", capabilities = listOf(ConnectorCapability("pages.read", "Read authorized pages"), ConnectorCapability("pages.create", "Create a page", ConnectorPermissionLevel.WRITE, true)), tags = listOf("knowledge", "notes")),
        soon("trello", "Trello", "Productivity", ConnectorAuthenticationType.OAUTH2, website = "https://trello.com", tags = listOf("boards", "tasks")),
        soon("asana", "Asana", "Productivity", ConnectorAuthenticationType.OAUTH2, website = "https://asana.com", tags = listOf("tasks", "projects")),
        soon("clickup", "ClickUp", "Productivity", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://clickup.com", tags = listOf("tasks", "projects")),
        soon("monday", "Monday.com", "Productivity", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://monday.com", tags = listOf("tasks", "projects")),
        soon("todoist", "Todoist", "Productivity", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://todoist.com", tags = listOf("tasks", "todo")),
        soon("dropbox", "Dropbox", "Files", ConnectorAuthenticationType.OAUTH2, website = "https://www.dropbox.com", tags = listOf("files", "storage")),
        soon("box", "Box", "Files", ConnectorAuthenticationType.OAUTH2, website = "https://www.box.com", tags = listOf("files", "storage")),
        soon("figma", "Figma", "Design", ConnectorAuthenticationType.OAUTH2, website = "https://www.figma.com", tags = listOf("design")),
        soon("canva", "Canva", "Design", ConnectorAuthenticationType.OAUTH2, website = "https://www.canva.com", tags = listOf("design")),
        soon("airtable", "Airtable", "Automation", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://www.airtable.com", tags = listOf("data", "automation")),
        partial("zapier", "Zapier", "Automation", ConnectorAuthenticationType.OAUTH2, website = "https://zapier.com", docs = "https://platform.zapier.com", tags = listOf("automation", "webhook")),
        soon("zoom", "Zoom", "Meetings", ConnectorAuthenticationType.OAUTH2, website = "https://zoom.us", tags = listOf("meetings", "video")),
    )

    private val byId = all.associateBy { it.id }
    fun get(id: String): ConnectorDefinition? = byId[id]
    fun search(query: String): List<ConnectorDefinition> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter { definition ->
            listOf(definition.id, definition.name, definition.displayName, definition.description, definition.category, definition.provider)
                .any { it.contains(q, ignoreCase = true) } || definition.tags.any { it.contains(q, ignoreCase = true) } || definition.capabilities.any { it.id.contains(q, ignoreCase = true) }
        }
    }
}

fun ConnectorDefinition.toConnectorMeta(): ConnectorMeta = ConnectorMeta(
    id = id,
    name = displayName,
    description = description,
    type = connectorType,
    iconUrl = icon,
    tags = tags,
    provider = provider,
    category = category,
    authenticationType = authenticationType,
    capabilities = capabilities,
    availability = status,
    website = website,
    privacyPolicyUrl = privacyPolicyUrl,
    documentationUrl = documentationUrl,
)

fun ConnectorMeta.withCatalogDefinition(definition: ConnectorDefinition): ConnectorMeta = copy(
    provider = definition.provider,
    category = definition.category,
    authenticationType = definition.authenticationType,
    capabilities = definition.capabilities,
    availability = definition.status,
    website = definition.website ?: website,
    privacyPolicyUrl = definition.privacyPolicyUrl ?: privacyPolicyUrl,
    documentationUrl = definition.documentationUrl ?: documentationUrl,
    tags = (tags + definition.tags).distinct(),
)
