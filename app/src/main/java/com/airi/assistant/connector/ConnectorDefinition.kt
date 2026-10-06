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
        requiredScopes: List<String> = emptyList(),
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
        requiredScopes = requiredScopes,
        permissions = capabilities.map { it.permission }.distinct(),
        status = ConnectorAvailability.PARTIAL,
        tags = tags.ifEmpty { listOf(name.lowercase()) },
        limitations = limitations + "Provider sandbox and credentialed-device evidence are still required before Ready.",
    )

    private fun tokenReadOnly(
        id: String,
        name: String,
        category: String,
        auth: ConnectorAuthenticationType,
        provider: String = name,
        website: String,
        docs: String,
        capabilityId: String,
        capabilityDescription: String,
        credentialNote: String,
        tags: List<String>,
    ) = partial(
        id = id,
        name = name,
        category = category,
        auth = auth,
        provider = provider,
        website = website,
        docs = docs,
        capabilities = listOf(ConnectorCapability(capabilityId, capabilityDescription)),
        tags = tags,
        limitations = listOf("Read-only first slice; $credentialNote"),
    )

    val all: List<ConnectorDefinition> = listOf(
        partial("google_gmail", "Gmail", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://mail.google.com", capabilities = listOf(ConnectorCapability("email.read", "Read authorized mail")), requiredScopes = listOf(ConnectorProviderScopes.GOOGLE_GMAIL_READONLY), tags = listOf("email", "mail", "google")),
        partial("google_calendar", "Google Calendar", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://calendar.google.com", capabilities = listOf(ConnectorCapability("calendar.read", "Read authorized events")), requiredScopes = listOf(ConnectorProviderScopes.GOOGLE_CALENDAR_EVENTS_OWNED_READONLY), tags = listOf("calendar", "schedule", "google")),
        partial("google_drive", "Google Drive", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://drive.google.com", capabilities = listOf(ConnectorCapability("drive.read", "Search authorized files")), requiredScopes = listOf(ConnectorProviderScopes.GOOGLE_DRIVE_METADATA_READONLY), tags = listOf("files", "storage", "google")),
        partial("google_docs", "Google Docs", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://docs.google.com", docs = "https://developers.google.com/docs/api", capabilities = listOf(ConnectorCapability("documents.read", "Read an authorized document")), requiredScopes = listOf(ConnectorProviderScopes.GOOGLE_DOCS_READONLY), tags = listOf("documents", "google"), limitations = listOf("Read-only document content.")),
        partial("google_sheets", "Google Sheets", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://sheets.google.com", docs = "https://developers.google.com/sheets/api", capabilities = listOf(ConnectorCapability("spreadsheets.read", "Read an authorized range")), requiredScopes = listOf(ConnectorProviderScopes.GOOGLE_SHEETS_READONLY), tags = listOf("spreadsheets", "google"), limitations = listOf("Read-only cell values.")),
        partial("google_contacts", "Google Contacts", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://contacts.google.com", docs = "https://developers.google.com/people", capabilities = listOf(ConnectorCapability("contacts.read", "Read authorized contacts")), requiredScopes = listOf(ConnectorProviderScopes.GOOGLE_CONTACTS_READONLY), tags = listOf("contacts", "google"), limitations = listOf("Read-only contact listing.")),
        partial("google_tasks", "Google Tasks", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://tasks.google.com", docs = "https://developers.google.com/tasks", capabilities = listOf(ConnectorCapability("tasks.read", "Read authorized tasks")), requiredScopes = listOf(ConnectorProviderScopes.GOOGLE_TASKS_READONLY), tags = listOf("tasks", "google"), limitations = listOf("Read-only default task list.")),
        soon("google_meet", "Google Meet", "Google", ConnectorAuthenticationType.OAUTH2, "Google", "https://meet.google.com", tags = listOf("meetings", "google")),
        partial("microsoft_outlook", "Outlook Mail", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://outlook.live.com", docs = "https://learn.microsoft.com/en-us/graph/api/user-list-messages", capabilities = listOf(ConnectorCapability("outlook.mail.read", "Read the signed-in user's mail")), requiredScopes = listOf(ConnectorProviderScopes.MICROSOFT_USER_READ, ConnectorProviderScopes.MICROSOFT_MAIL_READ_BASIC), tags = listOf("email", "mail", "microsoft"), limitations = listOf("Requires an Entra public client id and the registered AIRI redirect URI.")),
        partial("microsoft_calendar", "Outlook Calendar", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://outlook.live.com/calendar", docs = "https://learn.microsoft.com/en-us/graph/api/user-list-events", capabilities = listOf(ConnectorCapability("outlook.calendar.read", "Read the signed-in user's calendar")), requiredScopes = listOf(ConnectorProviderScopes.MICROSOFT_USER_READ, ConnectorProviderScopes.MICROSOFT_CALENDARS_READ_BASIC), tags = listOf("calendar", "microsoft"), limitations = listOf("Requires an Entra public client id and the registered AIRI redirect URI.")),
        partial("microsoft_onedrive", "OneDrive", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://onedrive.live.com", docs = "https://learn.microsoft.com/en-us/graph/api/driveitem-list-children", capabilities = listOf(ConnectorCapability("onedrive.files.read", "List authorized OneDrive files")), requiredScopes = listOf(ConnectorProviderScopes.MICROSOFT_USER_READ, ConnectorProviderScopes.MICROSOFT_FILES_READ), tags = listOf("files", "storage", "microsoft"), limitations = listOf("Read-only first slice: root and selected folder listing only.")),
        partial("microsoft_teams", "Microsoft Teams", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://teams.microsoft.com", docs = "https://learn.microsoft.com/en-us/graph/api/user-list-joinedteams", capabilities = listOf(ConnectorCapability("teams.read", "List teams the signed-in user has joined")), requiredScopes = listOf(ConnectorProviderScopes.MICROSOFT_USER_READ, ConnectorProviderScopes.MICROSOFT_TEAM_READ_BASIC_ALL), tags = listOf("communication", "microsoft"), limitations = listOf("Read-only first slice: joined team metadata only.", "Microsoft Graph joined-team listing is not supported for personal Microsoft accounts.")),
        soon("microsoft_sharepoint", "SharePoint", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://www.sharepoint.com", tags = listOf("files", "microsoft")),
        partial("microsoft_todo", "Microsoft To Do", "Microsoft", ConnectorAuthenticationType.OAUTH2, "Microsoft", "https://to-do.live.com", docs = "https://learn.microsoft.com/en-us/graph/api/resources/todotask", capabilities = listOf(ConnectorCapability("todo.read", "Read the signed-in user's To Do tasks")), requiredScopes = listOf(ConnectorProviderScopes.MICROSOFT_USER_READ, ConnectorProviderScopes.MICROSOFT_TASKS_READ), tags = listOf("tasks", "microsoft"), limitations = listOf("Read-only task lists and tasks.")),
        partial("github", "GitHub", "Development", ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, website = "https://github.com", docs = "https://docs.github.com", capabilities = listOf(ConnectorCapability("repositories.read", "Read repositories"), ConnectorCapability("issues.read", "Read issues"), ConnectorCapability("issues.create", "Create issues", ConnectorPermissionLevel.WRITE, true)), tags = listOf("git", "code", "development")),
        tokenReadOnly("gitlab", "GitLab", "Development", ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, website = "https://gitlab.com", docs = "https://docs.gitlab.com/api/user/", capabilityId = "projects.read", capabilityDescription = "List authorized projects", credentialNote = "requires a GitLab token with read_api scope", tags = listOf("git", "code")),
        tokenReadOnly("figma", "Figma", "Design", ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, website = "https://www.figma.com", docs = "https://www.figma.com/developers/api#auth", capabilityId = "identity.read", capabilityDescription = "Read the authorized Figma identity", credentialNote = "requires a Figma personal access token", tags = listOf("design", "files")),
        soon("jira", "Jira", "Development", ConnectorAuthenticationType.OAUTH2_AND_API, provider = "Atlassian", website = "https://www.atlassian.com/software/jira", tags = listOf("issues", "development")),
        tokenReadOnly("linear", "Linear", "Development", ConnectorAuthenticationType.API_KEY, website = "https://linear.app", docs = "https://linear.app/developers/graphql", capabilityId = "viewer.read", capabilityDescription = "Read the authorized Linear identity", credentialNote = "requires a Linear API key", tags = listOf("issues", "development")),
        tokenReadOnly("slack", "Slack", "Communication", ConnectorAuthenticationType.API_KEY, website = "https://slack.com", docs = "https://api.slack.com/methods/auth.test", capabilityId = "workspace.read", capabilityDescription = "Read the authorized Slack workspace identity", credentialNote = "requires an OAuth access token with identity/read scopes", tags = listOf("chat", "messaging")),
        tokenReadOnly("discord", "Discord", "Communication", ConnectorAuthenticationType.API_KEY, website = "https://discord.com", docs = "https://discord.com/developers/docs/resources/user", capabilityId = "guilds.read", capabilityDescription = "List guilds visible to the bot", credentialNote = "requires a Discord bot token", tags = listOf("chat", "messaging")),
        partial("telegram", "Telegram", "Communication", ConnectorAuthenticationType.API_KEY, website = "https://telegram.org", docs = "https://core.telegram.org/bots/api", capabilities = listOf(ConnectorCapability("messages.send", "Send a message", ConnectorPermissionLevel.WRITE, true)), tags = listOf("chat", "messaging")),
        partial("notion", "Notion", "Productivity", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://www.notion.so", docs = "https://developers.notion.com", capabilities = listOf(ConnectorCapability("pages.read", "Read authorized pages"), ConnectorCapability("pages.create", "Create a page", ConnectorPermissionLevel.WRITE, true)), tags = listOf("knowledge", "notes")),
        soon("trello", "Trello", "Productivity", ConnectorAuthenticationType.OAUTH2, website = "https://trello.com", tags = listOf("boards", "tasks")),
        tokenReadOnly("asana", "Asana", "Productivity", ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, website = "https://asana.com", docs = "https://developers.asana.com/reference/users-me", capabilityId = "profile.read", capabilityDescription = "Read the authorized Asana profile", credentialNote = "requires an Asana personal access token", tags = listOf("tasks", "projects")),
        soon("clickup", "ClickUp", "Productivity", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://clickup.com", tags = listOf("tasks", "projects")),
        soon("monday", "Monday.com", "Productivity", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://monday.com", tags = listOf("tasks", "projects")),
        tokenReadOnly("todoist", "Todoist", "Productivity", ConnectorAuthenticationType.API_KEY, website = "https://todoist.com", docs = "https://developer.todoist.com/rest/v2/", capabilityId = "tasks.read", capabilityDescription = "List authorized Todoist tasks", credentialNote = "requires a Todoist API token", tags = listOf("tasks", "todo")),
        soon("dropbox", "Dropbox", "Files", ConnectorAuthenticationType.OAUTH2, website = "https://www.dropbox.com", tags = listOf("files", "storage")),
        soon("box", "Box", "Files", ConnectorAuthenticationType.OAUTH2, website = "https://www.box.com", tags = listOf("files", "storage")),
        soon("canva", "Canva", "Design", ConnectorAuthenticationType.OAUTH2, website = "https://www.canva.com", tags = listOf("design")),
        soon("airtable", "Airtable", "Automation", ConnectorAuthenticationType.OAUTH2_AND_API, website = "https://www.airtable.com", tags = listOf("data", "automation")),
        partial("zapier", "Zapier", "Automation", ConnectorAuthenticationType.OAUTH2, website = "https://zapier.com", docs = "https://docs.zapier.com/powered-by-zapier/api-reference/oauth-scopes", requiredScopes = listOf(ConnectorProviderScopes.ZAPIER_ZAP_READ), tags = listOf("automation", "webhook")),
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
    runtimeId = runtimeConnectorId(),
    catalogId = id,
)

/** Catalog surfaces that share one first-class provider adapter. */
fun ConnectorDefinition.runtimeConnectorId(): String = ConnectorRuntimeDescriptors.runtimeIdFor(id)

fun ConnectorMeta.withCatalogDefinition(definition: ConnectorDefinition): ConnectorMeta = copy(
    id = definition.id,
    name = definition.displayName,
    description = definition.description,
    type = definition.connectorType,
    runtimeId = definition.runtimeConnectorId(),
    catalogId = definition.id,
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
