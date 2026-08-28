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
 * Scope inventory for the capabilities implemented by [GoogleConnector].
 * A user starts this authorization deliberately from the integrations screen.
 */
object GoogleDataScopes {
    val all: List<Scope> = listOf(
        Scope("https://www.googleapis.com/auth/gmail.readonly"),
        Scope("https://www.googleapis.com/auth/gmail.send"),
        Scope("https://www.googleapis.com/auth/calendar.events"),
        Scope("https://www.googleapis.com/auth/drive.metadata.readonly")
    )
}
