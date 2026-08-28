package com.airi.assistant.integrations.google

import android.app.PendingIntent
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

/**
 * Connection-wide read-only scope bundle for the capabilities exposed in this
 * release. A user starts it deliberately from the integrations screen. OAuth
 * consent does not approve a write: Gmail sending and Calendar creation remain
 * disabled until each has a durable, user-reviewable approval path.
 */
object GoogleDataScopes {
    val connectionReadOnly: List<Scope> = listOf(
        Scope("https://www.googleapis.com/auth/gmail.readonly"),
        Scope("https://www.googleapis.com/auth/calendar.events.readonly"),
        Scope("https://www.googleapis.com/auth/drive.metadata.readonly")
    )
}
