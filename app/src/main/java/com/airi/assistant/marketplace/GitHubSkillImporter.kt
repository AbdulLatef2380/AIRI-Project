package com.airi.assistant.marketplace

import android.util.Log
import com.airi.assistant.ai.skills.SkillManifest
import com.airi.assistant.ai.skills.SkillPackageVerifier
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.SkillConfig
import com.airi.assistant.domain.customskill.SkillType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * GitHubSkillImporter — validates and imports AIRI skills from GitHub.
 *
 * Supported input formats:
 *  1. Raw URL to skill.json    (https://raw.githubusercontent.com/.../skill.json)
 *  2. GitHub repository URL    (https://github.com/user/repo) — auto-detects skill.json
 *  3. GitHub release URL       (https://github.com/user/repo/releases/...)
 *  4. Skill package archive    (ZIP containing skill.json — future)
 *
 * Validation pipeline:
 *  1. Fetch manifest
 *  2. Parse JSON → SkillManifest
 *  3. Validate required fields
 *  4. Check version semver format
 *  5. Validate dependency IDs
 *  6. Check permission safety (block dangerous permissions)
 *  7. Validate HTTPS endpoints
 *  8. Size limit check
 */
object GitHubSkillImporter {

    private const val TAG = "GitHubSkillImporter"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val SEMVER_REGEX = Regex("""^\d+\.\d+\.\d+(-[\w.]+)?(\+[\w.]+)?$""")

    private val BLOCKED_PERMISSIONS = setOf(
        "android.permission.SEND_SMS",
        "android.permission.CALL_PHONE",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS"
    )

    // ── Public API ────────────────────────────────────────────────────────────

    data class ImportResult(
        val success:    Boolean,
        val skill:      CustomSkill?         = null,
        val manifest:   SkillManifest?       = null,
        val errors:     List<String>         = emptyList(),
        val warnings:   List<String>         = emptyList()
    )

    /**
     * Import a skill from a URL.
     *
     * Accepts raw JSON URLs, GitHub repo URLs, and release URLs.
     * Runs full validation before returning a [CustomSkill] ready to persist.
     */
    suspend fun importFromUrl(url: String): ImportResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Importing skill from: $url")

        val rawUrl = resolveToRawUrl(url)
            ?: return@withContext ImportResult(false, errors = listOf(
                "Could not resolve to a valid skill.json URL.\n" +
                "Supported formats:\n" +
                "• https://raw.githubusercontent.com/user/repo/branch/skill.json\n" +
                "• https://github.com/user/repo (auto-detects skill.json at repo root)"
            ))

        val jsonString = try {
            fetchContent(rawUrl)
        } catch (e: Exception) {
            return@withContext ImportResult(false, errors = listOf("Failed to fetch skill: ${e.message}"))
        }

        if (jsonString.length > 200_000) {
            return@withContext ImportResult(false, errors = listOf("Skill manifest too large (max 200KB)"))
        }

        val json = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return@withContext ImportResult(false, errors = listOf("Invalid JSON: ${e.message}"))
        }

        val validation = validateJson(json)
        if (!validation.isValid) {
            return@withContext ImportResult(false, errors = validation.errors, warnings = validation.warnings)
        }

        val manifest = try {
            SkillManifest.fromJson(json)
        } catch (e: Exception) {
            return@withContext ImportResult(false, errors = listOf("Failed to parse manifest: ${e.message}"))
        }

        // Phase D: package integrity + version compatibility verification
        val verifyResult = SkillPackageVerifier.verify(jsonString, manifest)
        if (!verifyResult.passed) {
            Log.w(TAG, "SkillPackageVerifier rejected '${manifest.id}': ${verifyResult.errors}")
            return@withContext ImportResult(
                success  = false,
                errors   = verifyResult.errors,
                warnings = validation.warnings + verifyResult.warnings
            )
        }
        val mergedWarnings = validation.warnings + verifyResult.warnings

        val skill = manifestToCustomSkill(manifest, rawUrl)
        Log.i(TAG, "Skill imported successfully: ${manifest.id} v${manifest.version} (verifier passed, ${mergedWarnings.size} warnings)")

        ImportResult(
            success  = true,
            skill    = skill,
            manifest = manifest,
            warnings = mergedWarnings
        )
    }

    /**
     * Validate a raw JSON string as an AIRI skill manifest.
     */
    fun validateJsonString(jsonString: String): ValidationResult {
        val json = runCatching { JSONObject(jsonString) }.getOrElse {
            return ValidationResult(false, listOf("Invalid JSON: ${it.message}"))
        }
        return validateJson(json)
    }

    // ── URL resolution ─────────────────────────────────────────────────────────

    private fun resolveToRawUrl(input: String): String? {
        val url = input.trim()

        if (url.startsWith("https://raw.githubusercontent.com/") && url.endsWith(".json")) {
            return url
        }

        if (url.startsWith("https://github.com/")) {
            val parts = url.removePrefix("https://github.com/").split("/")
            if (parts.size >= 2) {
                val user   = parts[0]
                val repo   = parts[1]
                return "https://raw.githubusercontent.com/$user/$repo/main/skill.json"
            }
        }

        if (url.endsWith("skill.json") || url.endsWith(".json")) {
            return url
        }

        if (url.startsWith("https://")) {
            return "$url/skill.json"
        }

        return null
    }

    // ── Fetch ──────────────────────────────────────────────────────────────────

    private fun fetchContent(url: String): String {
        val request  = Request.Builder().url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "AIRI-Skill-Importer/1.0")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }
        return response.body?.string() ?: throw Exception("Empty response body")
    }

    // ── Validation ────────────────────────────────────────────────────────────

    data class ValidationResult(
        val isValid:  Boolean,
        val errors:   List<String>   = emptyList(),
        val warnings: List<String>   = emptyList()
    )

    private fun validateJson(json: JSONObject): ValidationResult {
        val errors   = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val id = json.optString("id")
        when {
            id.isBlank()   -> errors.add("'id' is required (unique slug, e.g. 'my_skill')")
            id.contains(" ") -> errors.add("'id' must not contain spaces — use underscores")
            id.length > 64 -> errors.add("'id' must be 64 characters or fewer")
        }

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
            version.isBlank()              -> errors.add("'version' is required (semver, e.g. 1.0.0)")
            !SEMVER_REGEX.matches(version) -> errors.add("'version' must be valid semver (e.g. 1.0.0, 2.1.0-beta)")
        }

        val author = json.optString("author")
        if (author.isBlank()) errors.add("'author' is required")

        val tools = json.optJSONArray("tools")
        if (tools == null || tools.length() == 0) {
            warnings.add("No 'tools' defined. Add tool definitions so the agent can invoke this skill automatically.")
        } else if (tools.length() > 20) {
            errors.add("A skill can have at most 20 tools")
        }

        val perms = json.optJSONArray("permissions")
        if (perms != null) {
            (0 until perms.length()).forEach { i ->
                val perm = perms.getString(i)
                if (BLOCKED_PERMISSIONS.contains(perm)) {
                    errors.add("Permission '$perm' is blocked for security. Remove it to proceed.")
                }
            }
        }

        val endpoint = json.optString("endpoint")
        if (endpoint.isNotBlank() && !endpoint.startsWith("https://")) {
            errors.add("'endpoint' must use HTTPS (plain HTTP is not allowed)")
        }

        if (!json.has("id")) warnings.add("Skill does not declare an 'id'. One will be auto-generated.")
        if (json.optString("license").isBlank()) warnings.add("No 'license' specified — consider adding a SPDX identifier (e.g. MIT).")
        if (json.optString("repository_url").isBlank()) warnings.add("No 'repository_url' — helps with trust and discoverability.")

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    private fun manifestToCustomSkill(manifest: SkillManifest, sourceUrl: String): CustomSkill {
        val endpoint = manifest.tools.firstOrNull()?.let { tool ->
            manifest.toJson().optString("endpoint").ifBlank { null }
        } ?: sourceUrl

        return CustomSkill(
            id          = manifest.id.ifBlank { UUID.randomUUID().toString() },
            name        = manifest.name,
            description = manifest.description,
            type        = if (endpoint.contains("webhook")) SkillType.WEBHOOK else SkillType.API,
            config      = SkillConfig(
                endpoint     = endpoint,
                method       = "POST",
                bodyTemplate = buildDefaultBodyTemplate(manifest)
            ),
            createdAt   = System.currentTimeMillis()
        )
    }

    private fun buildDefaultBodyTemplate(manifest: SkillManifest): String {
        val params = manifest.tools.firstOrNull()?.parameters?.keys?.joinToString(", ") { key ->
            "\"$key\": \"{{$key}}\""
        } ?: "\"input\": \"{{input}}\""
        return "{$params}"
    }
}
