package com.airi.assistant.connector

/** Declarative contract for a provider adapter that is not live yet. */
data class ProviderAdapterContract(
    val catalogId: String,
    val provider: String,
    val authMode: ConnectorAuthMode,
    val requiredScopes: List<String>,
    val healthEndpoint: String,
    val adapterId: String,
    val officialDocsUrl: String,
    val readOnlyFirst: Boolean = true,
) {
    val isExecutable: Boolean = false
}

/**
 * Batch contract only. This object describes what an adapter must implement;
 * it intentionally does not execute HTTP, OAuth, or provider logic.
 */
object RemainingProviderAdapterContracts {
    val all: List<ProviderAdapterContract> = listOf(
        ProviderAdapterContract("google_docs", "Google", ConnectorAuthMode.OAUTH2_PKCE, listOf("https://www.googleapis.com/auth/documents.readonly"), "https://docs.googleapis.com/v1/documents", "google_workspace", "https://developers.google.com/docs/api"),
        ProviderAdapterContract("google_sheets", "Google", ConnectorAuthMode.OAUTH2_PKCE, listOf("https://www.googleapis.com/auth/spreadsheets.readonly"), "https://sheets.googleapis.com/v4/spreadsheets", "google_workspace", "https://developers.google.com/sheets/api"),
        ProviderAdapterContract("google_contacts", "Google", ConnectorAuthMode.OAUTH2_PKCE, listOf("https://www.googleapis.com/auth/contacts.readonly"), "https://people.googleapis.com/v1/people/me", "google_workspace", "https://developers.google.com/people"),
        ProviderAdapterContract("google_tasks", "Google", ConnectorAuthMode.OAUTH2_PKCE, listOf("https://www.googleapis.com/auth/tasks.readonly"), "https://tasks.googleapis.com/tasks/v1/users/@me/lists", "google_workspace", "https://developers.google.com/tasks"),
        ProviderAdapterContract("google_meet", "Google", ConnectorAuthMode.OAUTH2_PKCE, listOf("https://www.googleapis.com/auth/meetings.space.created"), "https://meet.googleapis.com/v2/spaces", "google_workspace", "https://developers.google.com/meet/api"),
        ProviderAdapterContract("microsoft_onedrive", "Microsoft", ConnectorAuthMode.OAUTH2_PKCE, listOf("Files.Read", "offline_access"), "https://graph.microsoft.com/v1.0/me/drive", "microsoft_graph", "https://learn.microsoft.com/en-us/graph/api/driveitem-list-children"),
        ProviderAdapterContract("microsoft_teams", "Microsoft", ConnectorAuthMode.OAUTH2_PKCE, listOf("Team.ReadBasic.All", "offline_access"), "https://graph.microsoft.com/v1.0/me/joinedTeams", "microsoft_graph", "https://learn.microsoft.com/en-us/graph/api/user-list-joinedteams"),
        ProviderAdapterContract("microsoft_sharepoint", "Microsoft", ConnectorAuthMode.OAUTH2_PKCE, listOf("Sites.Read.All", "offline_access"), "https://graph.microsoft.com/v1.0/sites/root", "microsoft_graph", "https://learn.microsoft.com/en-us/graph/api/resources/sharepoint"),
        ProviderAdapterContract("microsoft_todo", "Microsoft", ConnectorAuthMode.OAUTH2_PKCE, listOf("Tasks.Read", "offline_access"), "https://graph.microsoft.com/v1.0/me/todo/lists", "microsoft_graph", "https://learn.microsoft.com/en-us/graph/api/resources/todotask"),
        ProviderAdapterContract("gitlab", "GitLab", ConnectorAuthMode.OAUTH2_PKCE, listOf("read_api", "offline_access"), "https://gitlab.com/api/v4/user", "gitlab", "https://docs.gitlab.com/api/"),
        ProviderAdapterContract("bitbucket", "Bitbucket", ConnectorAuthMode.OAUTH2_PKCE, listOf("account", "repository"), "https://api.bitbucket.org/2.0/user", "bitbucket", "https://developer.atlassian.com/cloud/bitbucket/rest/"),
        ProviderAdapterContract("jira", "Atlassian", ConnectorAuthMode.OAUTH2_PKCE, listOf("read:jira-work", "offline_access"), "https://api.atlassian.com/me", "atlassian", "https://developer.atlassian.com/cloud/jira/platform/rest/v3/"),
        ProviderAdapterContract("linear", "Linear", ConnectorAuthMode.API_KEY, listOf("read"), "https://api.linear.app/graphql", "linear", "https://developers.linear.app/docs/graphql/working-with-the-graphql-api"),
        ProviderAdapterContract("slack", "Slack", ConnectorAuthMode.OAUTH2_PKCE, listOf("channels:read", "chat:write", "users:read"), "https://slack.com/api/auth.test", "slack", "https://api.slack.com/authentication/oauth-v2"),
        ProviderAdapterContract("discord", "Discord", ConnectorAuthMode.OAUTH2_PKCE, listOf("identify", "guilds"), "https://discord.com/api/v10/users/@me", "discord", "https://discord.com/developers/docs/topics/oauth2"),
        ProviderAdapterContract("trello", "Trello", ConnectorAuthMode.OAUTH2_PKCE, listOf("read"), "https://api.trello.com/1/members/me", "trello", "https://developer.atlassian.com/cloud/trello/rest/"),
        ProviderAdapterContract("asana", "Asana", ConnectorAuthMode.OAUTH2_PKCE, listOf("default"), "https://app.asana.com/api/1.0/users/me", "asana", "https://developers.asana.com/docs/oauth"),
        ProviderAdapterContract("clickup", "ClickUp", ConnectorAuthMode.OAUTH2_PKCE, listOf("read"), "https://api.clickup.com/api/v2/user", "clickup", "https://developer.clickup.com/docs/authentication"),
        ProviderAdapterContract("monday", "Monday.com", ConnectorAuthMode.OAUTH2_PKCE, listOf("me:read"), "https://api.monday.com/v2", "monday", "https://developer.monday.com/api-reference/docs/authentication"),
        ProviderAdapterContract("todoist", "Todoist", ConnectorAuthMode.OAUTH2_PKCE, listOf("data:read"), "https://api.todoist.com/api/v1/user", "todoist", "https://developer.todoist.com/guides/"),
        ProviderAdapterContract("dropbox", "Dropbox", ConnectorAuthMode.OAUTH2_PKCE, listOf("account_info.read", "files.metadata.read"), "https://api.dropboxapi.com/2/users/get_current_account", "dropbox", "https://www.dropbox.com/developers/reference/getting-started"),
        ProviderAdapterContract("box", "Box", ConnectorAuthMode.OAUTH2_PKCE, listOf("root_readonly"), "https://api.box.com/2.0/users/me", "box", "https://developer.box.com/guides/authentication/"),
        ProviderAdapterContract("figma", "Figma", ConnectorAuthMode.OAUTH2_PKCE, listOf("file_read"), "https://api.figma.com/v1/me", "figma", "https://www.figma.com/developers/api#auth"),
        ProviderAdapterContract("canva", "Canva", ConnectorAuthMode.OAUTH2_PKCE, listOf("asset:read", "design:content:read"), "https://api.canva.com/rest/v1/user", "canva", "https://www.canva.dev/docs/connect/"),
        ProviderAdapterContract("airtable", "Airtable", ConnectorAuthMode.OAUTH2_PKCE, listOf("data.records:read"), "https://api.airtable.com/v0/meta/whoami", "airtable", "https://airtable.com/developers/web/api/oauth-reference"),
        ProviderAdapterContract("zoom", "Zoom", ConnectorAuthMode.OAUTH2_PKCE, listOf("user:read"), "https://api.zoom.us/v2/users/me", "zoom", "https://developers.zoom.us/docs/integrations/oauth/"),
    )

    private val byId = all.associateBy { it.catalogId }

    fun get(catalogId: String): ProviderAdapterContract? = byId[catalogId]
}
