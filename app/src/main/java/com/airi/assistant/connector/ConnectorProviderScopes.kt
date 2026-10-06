package com.airi.assistant.connector

/** Provider OAuth scope identifiers used by action declarations and consent requests. */
object ConnectorProviderScopes {
    const val GOOGLE_GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    const val GOOGLE_CALENDAR_EVENTS_OWNED_READONLY = "https://www.googleapis.com/auth/calendar.events.owned.readonly"
    const val GOOGLE_DRIVE_METADATA_READONLY = "https://www.googleapis.com/auth/drive.metadata.readonly"
    const val GOOGLE_DOCS_READONLY = "https://www.googleapis.com/auth/documents.readonly"
    const val GOOGLE_SHEETS_READONLY = "https://www.googleapis.com/auth/spreadsheets.readonly"
    const val GOOGLE_CONTACTS_READONLY = "https://www.googleapis.com/auth/contacts.readonly"
    const val GOOGLE_TASKS_READONLY = "https://www.googleapis.com/auth/tasks.readonly"

    const val MICROSOFT_USER_READ = "User.Read"
    const val MICROSOFT_MAIL_READ_BASIC = "Mail.ReadBasic"
    const val MICROSOFT_CALENDARS_READ_BASIC = "Calendars.ReadBasic"
    const val MICROSOFT_FILES_READ = "Files.Read"
    const val MICROSOFT_TEAM_READ_BASIC_ALL = "Team.ReadBasic.All"
    const val MICROSOFT_TASKS_READ = "Tasks.Read"
    const val MICROSOFT_SITES_READ = "Sites.Read.All"

    const val ZAPIER_ZAP_READ = "zap"

    /** OIDC/offline bootstrap and Graph /me health-check permissions, not data-action scopes. */
    val microsoftBootstrapScopes: Set<String> = setOf(
        "openid",
        "profile",
        "email",
        "offline_access",
        MICROSOFT_USER_READ,
    )

    fun microsoftAuthorizationScopes(actionScopes: Set<String>): Set<String> =
        microsoftBootstrapScopes + actionScopes
}
