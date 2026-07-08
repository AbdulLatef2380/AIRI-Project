package com.airi.assistant.ai.remote

import android.content.Context
import android.content.SharedPreferences

object RemoteModelRegistry {

    private const val PREFS_NAME = "airi_remote_models"
    private const val KEY_MODELS  = "remote_models_json"
    private const val KEY_ACTIVE  = "active_remote_model_id"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateStaleModelNames()
    }

    /**
     * One-time idempotent migration: replace the stale OpenRouter model ID
     * google/gemini-2.0-flash-001 with the current supported ID
     * google/gemini-2.0-flash-exp:free in every persisted [RemoteModel] entry.
     *
     * Runs synchronously inside [init] before any caller can read the registry.
     * Safe to call on subsequent launches — exits immediately if no stale entries exist.
     */
    private fun migrateStaleModelNames() {
        val stale       = "google/gemini-2.0-flash-001"
        val replacement = "google/gemini-2.0-flash-exp:free"
        val list = getAll()
        // B-07: Skip custom user-defined endpoints; only migrate first-party model names.
        val affected = list.filter { it.name == stale && !it.isCustomEndpoint }
        if (affected.isEmpty()) return
        val migrated = list.map { m ->
            if (m.name == stale && !m.isCustomEndpoint) m.copy(name = replacement) else m
        }
        prefs.edit().putString(KEY_MODELS, serializeList(migrated)).apply()
        android.util.Log.i("AIRI_Registry",
            "migrateStaleModelNames: replaced $stale → $replacement in ${affected.size} entry(s): " +
            affected.joinToString { it.id })
    }

    fun getAll(): List<RemoteModel> {
        val json = prefs.getString(KEY_MODELS, "[]") ?: "[]"
        return parseList(json)
    }

    fun add(model: RemoteModel) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == model.id }
        list.add(model)
        prefs.edit().putString(KEY_MODELS, serializeList(list)).apply()
    }

    fun remove(modelId: String) {
        val list = getAll().filter { it.id != modelId }
        prefs.edit().putString(KEY_MODELS, serializeList(list)).apply()
        if (getActiveId() == modelId) clearActive()
    }

    fun getActiveId(): String = prefs.getString(KEY_ACTIVE, "") ?: ""

    fun setActive(modelId: String) {
        prefs.edit().putString(KEY_ACTIVE, modelId).apply()
    }

    fun clearActive() {
        prefs.edit().remove(KEY_ACTIVE).apply()
    }

    fun getActive(): RemoteModel? =
        getAll().firstOrNull { it.id == getActiveId() }

    private fun serializeList(list: List<RemoteModel>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"id\":\"${m.id}\",")
            sb.append("\"name\":\"${m.name.replace("\"","\\\"")}\",")
            sb.append("\"serverUrl\":\"${m.serverUrl.replace("\"","\\\"")}\",")
            sb.append("\"apiKey\":\"${m.apiKey.replace("\"","\\\"")}\",")
            sb.append("\"isActive\":${m.isActive},")
            // B-07: persist isCustomEndpoint so migration guards survive app restart.
            // Without this, the field reloads as false (default), making every custom
            // endpoint vulnerable to stale-name migration on the NEXT launch.
            sb.append("\"isCustomEndpoint\":${m.isCustomEndpoint}")
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun parseList(json: String): List<RemoteModel> {
        return runCatching {
            val models = mutableListOf<RemoteModel>()
            var idx = json.indexOf('{')
            while (idx >= 0 && idx < json.length) {
                val end = json.indexOf('}', idx)
                if (end < 0) break
                val obj = json.substring(idx + 1, end)
                models.add(parseObject(obj))
                idx = json.indexOf('{', end + 1)
            }
            models
        }.getOrElse { emptyList() }
    }

    private fun parseObject(obj: String): RemoteModel {
        fun field(name: String): String {
            val key  = "\"$name\":"
            val start = obj.indexOf(key)
            if (start < 0) return ""
            val after = obj.indexOf(":", start) + 1
            val trimmed = obj.substring(after).trimStart()
            return if (trimmed.startsWith("\"")) {
                val s = trimmed.indexOf("\"") + 1
                val e = findEnd(trimmed, s)
                trimmed.substring(s, e).replace("\\\"", "\"")
            } else {
                trimmed.split(",", "}").first().trim()
            }
        }
        return RemoteModel(
            id               = field("id"),
            name             = field("name"),
            serverUrl        = field("serverUrl"),
            apiKey           = field("apiKey"),
            isActive         = field("isActive") == "true",
            // B-07: default false for models persisted before this field was added.
            isCustomEndpoint = field("isCustomEndpoint") == "true"
        )
    }

    private fun findEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            when {
                s[i] == '\\' -> i += 2
                s[i] == '"'  -> return i
                else         -> i++
            }
        }
        return s.length
    }
}
