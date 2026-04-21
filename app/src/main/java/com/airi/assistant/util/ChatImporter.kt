package com.airi.assistant.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Imports a chat JSON file (in the same shape produced by [ChatExporter]) into a list of
 * (role, content) pairs. The caller (ChatViewModel.importChatJson) is responsible for
 * persisting them into the active session via MemoryManager so they appear in the UI.
 *
 * Accepted shapes:
 *   1. Bare array:        [ { "role": "...", "content": "..." }, ... ]
 *   2. Wrapped object:    { "messages": [ ... ] }
 * Unknown roles default to "user". Empty / blank `content` entries are skipped silently.
 */
object ChatImporter {
    private const val TAG = "AIRI_STORAGE"

    data class ImportedMessage(val role: String, val content: String)

    fun createImportIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun importFromUri(context: Context, uri: Uri): List<ImportedMessage> {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IOException("Cannot open import URI for reading")

            val parsed = parse(raw)
            Log.i(TAG, "IMPORT_SUCCESS uri=$uri count=${parsed.size}")
            com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
                "IMPORT", true, "uri=$uri count=${parsed.size}"
            )
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "IMPORT_FAILED reason=${e.message}", e)
            com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
                "IMPORT", false, e.message ?: "unknown"
            )
            emptyList()
        }
    }

    private fun parse(raw: String): List<ImportedMessage> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        val array: JSONArray = when (trimmed.first()) {
            '[' -> JSONArray(trimmed)
            '{' -> {
                val obj = JSONObject(trimmed)
                when {
                    obj.has("messages") -> obj.getJSONArray("messages")
                    obj.has("chat")     -> obj.getJSONArray("chat")
                    else -> throw IOException("JSON object missing 'messages' / 'chat' array")
                }
            }
            else -> throw IOException("Unrecognised JSON shape (must start with [ or {)")
        }

        val out = mutableListOf<ImportedMessage>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val role = (item.optString("role").ifBlank { "user" }).lowercase()
            val content = item.optString("content").ifBlank { item.optString("text") }
            if (content.isBlank()) continue
            val normalisedRole = if (role == "assistant" || role == "ai" || role == "model") "assistant" else "user"
            out.add(ImportedMessage(normalisedRole, content))
        }
        return out
    }
}
