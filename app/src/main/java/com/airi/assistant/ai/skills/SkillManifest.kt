package com.airi.assistant.ai.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * SkillManifest — the canonical in-memory representation of a skill.json file.
 *
 * Every installable skill must have a manifest that describes its identity,
 * capabilities, security requirements, and runtime wiring. The manifest is the
 * single source of truth for the skill engine: registration, execution, memory
 * access, model routing, and permission enforcement all derive from it.
 *
 * Extended manifest format (skill.json):
 * {
 *   "id":                "web_search",
 *   "name":              "Web Search",
 *   "description":       "Search the web...",
 *   "version":           "1.0.0",
 *   "author":            "AIRI Official",
 *   "category":          "SEARCH",
 *   "is_official":       true,
 *   "icon_emoji":        "🔍",
 *   "permissions":       ["INTERNET"],
 *   "memory_access":     "READ_WRITE",
 *   "model_access":      "NONE",
 *   "dependencies":      [],
 *   "airi_min_version":  "1.0.0",
 *   "airi_target_version": "1.0.0",
 *   "created_at":        1700000000000,
 *   "updated_at":        1700000000000,
 *   "signature":         null,
 *   "checksum":          "sha256hex...",
 *   "support_url":       "https://...",
 *   "changelog":         "v1.1: ...",
 *   "homepage":          "https://...",
 *   "tools": [ ... ],
 *   "configuration": { ... },
 *   "entrypoint":        "com.airi.assistant.ai.skills.impl.WebSearchSkill",
 *   "repository_url":    "https://github.com/airi-assistant/skills",
 *   "license":           "MIT"
 * }
 */
data class SkillManifest(
    val id:                String,
    val name:              String,
    val description:       String,
    val version:           String,
    val author:            String,
    val category:          String             = "UTILITY",
    val isOfficial:        Boolean            = false,
    val iconEmoji:         String             = "🔧",
    val permissions:       List<String>       = emptyList(),
    val memoryAccess:      SkillMemoryAccess  = SkillMemoryAccess.NONE,
    val modelAccess:       SkillModelAccess   = SkillModelAccess.NONE,
    val dependencies:      List<String>       = emptyList(),
    val tools:             List<ToolDef>      = emptyList(),
    val configuration:     Map<String, ConfigField> = emptyMap(),
    val entrypoint:        String?            = null,
    val repositoryUrl:     String?            = null,
    val license:           String             = "MIT",
    val tags:              List<String>       = emptyList(),
    // ── Extended fields (Phase C) ─────────────────────────────────────────────
    /** Minimum AIRI app version required to run this skill (semver). */
    val airiMinVersion:    String             = "1.0.0",
    /** Recommended target AIRI version (informational). */
    val airiTargetVersion: String             = "1.0.0",
    /** Unix-ms timestamp when this skill was first published. */
    val createdAt:         Long               = 0L,
    /** Unix-ms timestamp of the most recent update. */
    val updatedAt:         Long               = 0L,
    /** Optional Ed25519 signature of the canonical manifest JSON (future enforcement). */
    val signature:         String?            = null,
    /** SHA-256 hex checksum of the raw skill.json. Verified by [SkillPackageVerifier]. */
    val checksum:          String?            = null,
    /** URL to the skill's support / issue tracker. */
    val supportUrl:        String?            = null,
    /** Short changelog text for the current version. */
    val changelog:         String?            = null,
    /** Skill homepage (marketing / documentation landing page). */
    val homepage:          String?            = null
) {
    data class ToolDef(
        val name:        String,
        val description: String,
        val parameters:  Map<String, ParamDef> = emptyMap()
    )

    data class ParamDef(
        val type:        String,
        val description: String  = "",
        val required:    Boolean = true
    )

    data class ConfigField(
        val type:     String,
        val label:    String,
        val required: Boolean = false,
        val secret:   Boolean = false,
        val default:  String? = null
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id",                   id)
        put("name",                 name)
        put("description",          description)
        put("version",              version)
        put("author",               author)
        put("category",             category)
        put("is_official",          isOfficial)
        put("icon_emoji",           iconEmoji)
        put("permissions",          JSONArray(permissions))
        put("memory_access",        memoryAccess.name)
        put("model_access",         modelAccess.name)
        put("dependencies",         JSONArray(dependencies))
        put("tools", JSONArray(tools.map { t ->
            JSONObject().apply {
                put("name",        t.name)
                put("description", t.description)
                put("parameters", JSONObject(t.parameters.mapValues { (_, p) ->
                    JSONObject().apply {
                        put("type",        p.type)
                        put("description", p.description)
                        put("required",    p.required)
                    }
                }))
            }
        }))
        put("license",              license)
        put("tags",                 JSONArray(tags))
        // Extended fields
        put("airi_min_version",     airiMinVersion)
        put("airi_target_version",  airiTargetVersion)
        if (createdAt > 0L) put("created_at", createdAt)
        if (updatedAt > 0L) put("updated_at", updatedAt)
        signature?.let  { put("signature",   it) }
        checksum?.let   { put("checksum",    it) }
        supportUrl?.let { put("support_url", it) }
        changelog?.let  { put("changelog",   it) }
        homepage?.let   { put("homepage",    it) }
        entrypoint?.let    { put("entrypoint",      it) }
        repositoryUrl?.let { put("repository_url",  it) }
    }

    companion object {
        fun fromJson(json: JSONObject): SkillManifest {
            fun parseTools(arr: JSONArray?): List<ToolDef> {
                if (arr == null) return emptyList()
                return (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    val params = mutableMapOf<String, ParamDef>()
                    t.optJSONObject("parameters")?.let { po ->
                        po.keys().forEach { key ->
                            val pj = po.getJSONObject(key)
                            params[key] = ParamDef(
                                type        = pj.optString("type", "string"),
                                description = pj.optString("description"),
                                required    = pj.optBoolean("required", true)
                            )
                        }
                    }
                    ToolDef(t.getString("name"), t.optString("description"), params)
                }
            }

            fun parseConfig(obj: JSONObject?): Map<String, ConfigField> {
                if (obj == null) return emptyMap()
                return obj.keys().asSequence().associate { key ->
                    val f = obj.getJSONObject(key)
                    key to ConfigField(
                        type     = f.optString("type", "string"),
                        label    = f.optString("label", key),
                        required = f.optBoolean("required"),
                        secret   = f.optBoolean("secret"),
                        default  = f.optString("default").ifBlank { null }
                    )
                }
            }

            fun parseStringList(arr: JSONArray?): List<String> {
                if (arr == null) return emptyList()
                return (0 until arr.length()).map { arr.getString(it) }
            }

            return SkillManifest(
                id                = json.getString("id"),
                name              = json.getString("name"),
                description       = json.optString("description"),
                version           = json.optString("version", "1.0.0"),
                author            = json.optString("author", "Unknown"),
                category          = json.optString("category", "UTILITY"),
                isOfficial        = json.optBoolean("is_official"),
                iconEmoji         = json.optString("icon_emoji", "🔧"),
                permissions       = parseStringList(json.optJSONArray("permissions")),
                memoryAccess      = runCatching {
                    SkillMemoryAccess.valueOf(json.optString("memory_access", "NONE").uppercase())
                }.getOrDefault(SkillMemoryAccess.NONE),
                modelAccess       = runCatching {
                    SkillModelAccess.valueOf(json.optString("model_access", "NONE").uppercase())
                }.getOrDefault(SkillModelAccess.NONE),
                dependencies      = parseStringList(json.optJSONArray("dependencies")),
                tools             = parseTools(json.optJSONArray("tools")),
                configuration     = parseConfig(json.optJSONObject("configuration")),
                entrypoint        = json.optString("entrypoint").ifBlank { null },
                repositoryUrl     = json.optString("repository_url").ifBlank { null },
                license           = json.optString("license", "MIT"),
                tags              = parseStringList(json.optJSONArray("tags")),
                // Extended fields
                airiMinVersion    = json.optString("airi_min_version", "1.0.0"),
                airiTargetVersion = json.optString("airi_target_version", "1.0.0"),
                createdAt         = json.optLong("created_at", 0L),
                updatedAt         = json.optLong("updated_at", 0L),
                signature         = json.optString("signature").ifBlank { null },
                checksum          = json.optString("checksum").ifBlank { null },
                supportUrl        = json.optString("support_url").ifBlank { null },
                changelog         = json.optString("changelog").ifBlank { null },
                homepage          = json.optString("homepage").ifBlank { null }
            )
        }

        fun fromJsonString(jsonString: String): SkillManifest? =
            runCatching { fromJson(JSONObject(jsonString)) }.getOrNull()
    }
}
