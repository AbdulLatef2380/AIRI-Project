package com.airi.assistant.connector.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * ContactsConnector — look up device contacts for messaging / scheduling context.
 *
 * Actions:
 *  - search  → params["query"] — find contacts by name/number
 *  - list    → list up to 20 contacts alphabetically
 *
 * Requires READ_CONTACTS permission.
 * Permission-aware: connect() sets healthy=false with a clear message when denied.
 * Integration: registered in [ConnectorBootstrap.installDefaults].
 */
class ContactsConnector(private val appContext: Context) : Connector {

    override val id          = "contacts"
    override val name        = "Contacts"
    override val description = "Search device contacts for messaging and scheduling."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(ConnectorState(connected = false))

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("contacts", "phone", "people", "messaging", "schedule")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val granted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        _state.value = if (granted) {
            ConnectorState(connected = true, healthy = true,
                statusLine = "Contacts accessible",
                lastUpdatedMs = System.currentTimeMillis())
        } else {
            ConnectorState(connected = false, healthy = false,
                statusLine = "READ_CONTACTS permission not granted",
                errorMessage = "READ_CONTACTS permission denied. Grant in Settings → AIRI → Permissions.")
        }
        return _state.value
    }

    override suspend fun disconnect() {
        _state.value = _state.value.copy(connected = false)
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        if (!_state.value.connected) {
            return ConnectorOutput.Failure("not_connected",
                "Grant READ_CONTACTS permission and tap Connect")
        }
        return withContext(Dispatchers.IO) {
            when (input.action) {
                "search" -> {
                    val query = input.params["query"]
                        ?: return@withContext ConnectorOutput.Failure(
                            "missing_param", "Required param 'query' not provided")
                    val contacts = queryContacts(
                        selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        args      = arrayOf("%$query%"),
                        limit     = 10
                    )
                    ConnectorOutput.Success(
                        text = if (contacts.isEmpty()) "No contacts found for '$query'"
                               else "Found ${contacts.size}: ${contacts.first()}",
                        data = mapOf("count" to contacts.size.toString(),
                                     "contacts" to contacts.joinToString("; "))
                    )
                }
                "list" -> {
                    val contacts = queryContacts(limit = 20)
                    ConnectorOutput.Success(
                        text = "Contacts: ${contacts.size}",
                        data = mapOf("count" to contacts.size.toString(),
                                     "contacts" to contacts.joinToString("; "))
                    )
                }
                else -> ConnectorOutput.Failure("unknown_action",
                    "Unknown action '${input.action}'. Valid: search, list")
            }
        }
    }

    private fun queryContacts(
        selection: String? = null,
        args:      Array<String>? = null,
        limit:     Int = 20
    ): List<String> {
        val results = mutableListOf<String>()
        val cursor = appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT $limit"
        )
        cursor?.use {
            val ni = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pi = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name   = it.getString(ni) ?: continue
                val number = it.getString(pi) ?: ""
                results.add("$name ($number)")
            }
        }
        return results
    }
}
