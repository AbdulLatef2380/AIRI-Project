package com.airi.assistant.marketplace

import org.json.JSONObject

/**
 * SkillPublisher — validates and packages skill definitions for marketplace submission.
 *
 * ── MANIFEST SCHEMA ───────────────────────────────────────────────────────────
 * A publishable skill definition (skill.json) must contain:
 * {
 *   "name":        "My Skill",          // required, 3–80 chars
 *   "description": "Does X and Y",      // required, 10–500 chars
 *   "version":     "1.0.0",             // required, semver
 *   "author":      "Display Name",      // required
 *   "category":    "DEVELOPER",         // required, one of MarketplaceSkill.Category
 *   "actions": [                         // required, 1–20 actions
 *     {
 *       "name":        "action_name",
 *       "description": "What it does",
 *       "parameters": { "param1": "string" }
 *     }
 *   ],
 *   // Optional
 *   "tags":        ["tag1", "tag2"],
 *   "repository_url": "https://github.com/...",
 *   "documentation_url": "https://...",
 *   "endpoint":    "https://my-api.com/skill",
 *   "auth_type":   "api_key" | "oauth2" | "none",
 *   "license":     "MIT"
 * }
 *
 * ── SECURITY CHECKS ──────────────────────────────────────────────────────────
 *  - [validateManifest] rejects manifests that attempt to inject dangerous URLs
 *    (non-HTTPS endpoints), missing required fields, or exceed size limits.
 *  - [SkillSubmission] wraps the validated manifest with publisher metadata
 *    for the marketplace API.
 */
object SkillPublisher {

    private val SEMVER_REGEX = Regex("""^\d+\.\d+\.\d+(-[\w.]+)?(\+[\w.]+)?$""")
    private val HTTPS_REGEX  = Regex("""^https://.*""")

    data class PublishValidationResult(
        val isValid: Boolean,
        val errors:  List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class SkillSubmission(
        val manifest:        JSONObject,
        val publisherName:   String,
        val publisherId:     String,
        val repositoryUrl:   String?,
        val licenseId:       String,
        val termsAccepted:   Boolean,
        val isOpenSource:    Boolean
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("manifest",       manifest)
            put("publisher_name", publisherName)
            put("publisher_id",   publisherId)
            put("repository_url", repositoryUrl ?: JSONObject.NULL)
            put("license",        licenseId)
            put("terms_accepted", termsAccepted)
            put("is_open_source", isOpenSource)
        }
    }

    /**
     * Validate a skill manifest JSON string.
     * Returns a [ValidationResult] with all errors and warnings found.
     */
    fun validateManifest(jsonString: String): PublishValidationResult {
        val errors   = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val json = runCatching { JSONObject(jsonString) }.getOrElse {
            return PublishValidationResult(false, listOf("Invalid JSON: ${it.message}"))
        }

        // ── Required fields ──────────────────────────────────────────────────
        val name = json.optString("name")
        when {
            name.isBlank()   -> errors.add("'name' is required")
            name.length < 3  -> errors.add("'name' must be at least 3 characters")
            name.length > 80 -> errors.add("'name' must be 80 characters or fewer")
        }

        val desc = json.optString("description")
        when {
            desc.isBlank()    -> errors.add("'description' is required")
            desc.length < 10  -> errors.add("'description' must be at least 10 characters")
            desc.length > 500 -> errors.add("'description' must be 500 characters or fewer")
        }

        val version = json.optString("version")
        when {
            version.isBlank()               -> errors.add("'version' is required (semver e.g. 1.0.0)")
            !SEMVER_REGEX.matches(version)  -> errors.add("'version' must be valid semver (e.g. 1.0.0)")
        }

        val author = json.optString("author")
        if (author.isBlank()) errors.add("'author' is required")

        val category = json.optString("category")
        if (category.isBlank()) {
            errors.add("'category' is required")
        } else {
            val validCategories = MarketplaceSkill.Category.entries.map { it.name }
            if (category.uppercase() !in validCategories) {
                errors.add("'category' must be one of: ${validCategories.joinToString(", ")}")
            }
        }

        val actions = json.optJSONArray("actions")
        when {
            actions == null || actions.length() == 0 -> errors.add("'actions' array is required with at least 1 action")
            actions.length() > 20                     -> errors.add("A skill can have at most 20 actions")
        }

        // ── Security checks ──────────────────────────────────────────────────
        val endpoint = json.optString("endpoint")
        if (endpoint.isNotBlank() && !HTTPS_REGEX.matches(endpoint)) {
            errors.add("'endpoint' must use HTTPS (plain HTTP is not allowed)")
        }

        val repoUrl = json.optString("repository_url")
        if (repoUrl.isNotBlank() && !HTTPS_REGEX.matches(repoUrl)) {
            errors.add("'repository_url' must use HTTPS")
        }

        // ── Size limit ───────────────────────────────────────────────────────
        if (jsonString.length > 50_000) {
            errors.add("Manifest too large (max 50KB). Move large content to the documentation_url.")
        }

        // ── Warnings ─────────────────────────────────────────────────────────
        if (json.optString("license").isBlank()) warnings.add("No 'license' specified. SPDX identifier recommended (e.g. MIT).")
        if (json.optJSONArray("tags") == null)  warnings.add("Adding 'tags' helps users discover your skill.")
        if (json.optString("documentation_url").isBlank()) warnings.add("A 'documentation_url' improves trust and discoverability.")

        return PublishValidationResult(
            isValid  = errors.isEmpty(),
            errors   = errors,
            warnings = warnings
        )
    }

    /** Build a [SkillSubmission] from a validated manifest + publisher info. */
    fun buildSubmission(
        manifestJson:  String,
        publisherName: String,
        publisherId:   String,
        repositoryUrl: String? = null,
        licenseId:     String  = "MIT",
        isOpenSource:  Boolean = true,
        termsAccepted: Boolean
    ): SkillSubmission? {
        if (!termsAccepted) return null
        val json = runCatching { JSONObject(manifestJson) }.getOrNull() ?: return null
        return SkillSubmission(
            manifest      = json,
            publisherName = publisherName,
            publisherId   = publisherId,
            repositoryUrl = repositoryUrl,
            licenseId     = licenseId,
            termsAccepted = true,
            isOpenSource  = isOpenSource
        )
    }

    /** Sample skill.json template for the "Publish Skill" UI. */
    val TEMPLATE_JSON: String = """
{
  "name": "My Skill",
  "description": "Describe what your skill does in 1–2 sentences.",
  "version": "1.0.0",
  "author": "Your Name",
  "category": "UTILITY",
  "license": "MIT",
  "repository_url": "https://github.com/you/my-skill",
  "documentation_url": "https://github.com/you/my-skill#readme",
  "endpoint": "https://your-api.com/execute",
  "auth_type": "api_key",
  "tags": ["utility", "example"],
  "actions": [
    {
      "name": "run",
      "description": "Execute the primary action",
      "parameters": {
        "input": "string"
      }
    }
  ]
}
""".trimIndent()
}
