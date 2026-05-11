package com.airi.assistant.connector.local

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContactsConnector — read Android Contacts via ContentResolver.
 *
 * ## Supported actions
 * | action          | params              | notes                        |
 * |-----------------|---------------------|------------------------------|
 * | `search`        | `query`             | Name or phone fuzzy search   |
 * | `get_contact`   | `contact_id`        | Full detail for one contact  |
 * | `list_all`      | `limit` (default 50)| Alphabetical listing         |
 *
 * Requires: `READ_CONTACTS`
 */
class ContactsConnector(private val context: Context) : Connector {

    override val id          = "contacts"
    override val name        = "Contacts"
    override val description = "Search and read Android device contacts"
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(ConnectorState(connected = false))
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()
    override fun meta() = ConnectorMeta(id, name, description, type,
        tags = listOf("contacts", "phone", "people", "address book"))

    override suspend fun connect(): ConnectorState {
        val granted = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val s = ConnectorState(
            connected  = granted,
            statusLine = if (granted) "Contacts access granted" else "READ_CONTACTS required"
        )
        _state.value = s
        return s
    }

    override suspend fun disconnect() {
        _state.value = ConnectorState(connected = false)
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        try {
            when (input.action) {
                "search"      -> search(input.params["query"] ?: input.text)
                "get_contact" -> getContact(input.params)
                "list_all"    -> listAll(input.params["limit"]?.toIntOrNull() ?: 50)
                else -> ConnectorOutput.Failure("unknown_action",
                    "ContactsConnector does not support: ${input.action}")
            }
        } catch (e: SecurityException) {
            ConnectorOutput.Failure("permission_denied", "READ_CONTACTS not granted", retryable = false)
        } catch (e: Exception) {
            Log.e(TAG, "ContactsConnector error: ${e.message}")
            ConnectorOutput.Failure("contacts_error", e.message ?: "Unknown error", retryable = true)
        }
    }

    private fun search(query: String): ConnectorOutput {
        if (query.isBlank()) return ConnectorOutput.Failure("missing_param", "query must not be empty")

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args      = arrayOf("%$query%")

        val cursor = context.contentResolver.query(uri, projection, selection, args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
            ?: return ConnectorOutput.Failure("query_failed", "Contacts query returned null")

        val results = JSONArray()
        val seen    = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext() && results.length() < 20) {
                val cid  = it.getString(0)
                val name = it.getString(1) ?: continue
                val num  = it.getString(2) ?: ""
                val key  = "$cid-$num"
                if (key in seen) continue
                seen += key
                results.put(JSONObject().apply {
                    put("id",    cid)
                    put("name",  name)
                    put("phone", num)
                })
            }
        }
        return ConnectorOutput.Success(
            text = "Found ${results.length()} contacts matching '$query'",
            data = mapOf("contacts_json" to results.toString(), "count" to results.length().toString())
        )
    }

    private fun getContact(params: Map<String, String>): ConnectorOutput {
        val id = params["contact_id"]
            ?: return ConnectorOutput.Failure("missing_param", "contact_id required")

        // Phones
        val phones = JSONArray()
        val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val phoneSel = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        context.contentResolver.query(phoneUri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE),
            phoneSel, arrayOf(id), null
        )?.use { c ->
            while (c.moveToNext()) phones.put(JSONObject()
                .put("number", c.getString(0))
                .put("type",   c.getInt(1)))
        }

        // Emails
        val emails = JSONArray()
        val emailUri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val emailSel = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?"
        context.contentResolver.query(emailUri,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            emailSel, arrayOf(id), null
        )?.use { c ->
            while (c.moveToNext()) emails.put(c.getString(0) ?: continue)
        }

        // Name
        val nameUri = ContactsContract.Data.CONTENT_URI
        val nameSel = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
            "${ContactsContract.Data.MIMETYPE} = '${ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE}'"
        var displayName = "Unknown"
        context.contentResolver.query(nameUri,
            arrayOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME),
            nameSel, arrayOf(id), null
        )?.use { c ->
            if (c.moveToFirst()) displayName = c.getString(0) ?: "Unknown"
        }

        val result = JSONObject()
            .put("id",     id)
            .put("name",   displayName)
            .put("phones", phones)
            .put("emails", emails)

        return ConnectorOutput.Success(
            text = result.toString(2),
            data = mapOf("contact_json" to result.toString())
        )
    }

    private fun listAll(limit: Int): ConnectorOutput {
        val uri  = ContactsContract.Contacts.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        val sort = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit"
        val cursor = context.contentResolver.query(uri, proj, null, null, sort)
            ?: return ConnectorOutput.Failure("query_failed", "Could not query contacts")

        val results = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                results.put(JSONObject()
                    .put("id",   it.getString(0))
                    .put("name", it.getString(1) ?: ""))
            }
        }
        return ConnectorOutput.Success(
            text = "${results.length()} contacts",
            data = mapOf("contacts_json" to results.toString())
        )
    }

    companion object { private const val TAG = "AIRI_ContactsConnector" }
}
