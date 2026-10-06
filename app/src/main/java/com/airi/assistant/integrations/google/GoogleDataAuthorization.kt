package com.airi.assistant.integrations.google

import android.app.PendingIntent
import com.airi.assistant.connector.ConnectorProviderScopes
import com.google.android.gms.common.api.Scope

/**
 * User-data authorization is intentionally distinct from sign-in identity.
 *
 * Google ID tokens authenticate the user to AIRI/Firebase. Google API calls
 * require a short-lived OAuth access token, issued only after the user grants
 * the scopes represented here. Tokens are never persisted by this contract;
 * Google Identity Services owns its local cache and expiry handling.
 */
sealed interface GoogleDataAuthorization {
    data object Authorized : GoogleDataAuthorization
    data class ConsentRequired(val pendingIntent: PendingIntent) : GoogleDataAuthorization
    data object Cancelled : GoogleDataAuthorization
    data object Unavailable : GoogleDataAuthorization
}

/** Maps only the read-only action scopes currently allowed by the user's surface profiles. */
object GoogleDataScopes {
    fun fromActionScopes(scopes: Set<String>): List<Scope> = scopes
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .map(::Scope)

    val knownReadOnlyScopes: Set<String> = setOf(
        ConnectorProviderScopes.GOOGLE_GMAIL_READONLY,
        ConnectorProviderScopes.GOOGLE_CALENDAR_EVENTS_OWNED_READONLY,
        ConnectorProviderScopes.GOOGLE_DRIVE_METADATA_READONLY,
        ConnectorProviderScopes.GOOGLE_DOCS_READONLY,
        ConnectorProviderScopes.GOOGLE_SHEETS_READONLY,
        ConnectorProviderScopes.GOOGLE_CONTACTS_READONLY,
        ConnectorProviderScopes.GOOGLE_TASKS_READONLY,
    )
}
