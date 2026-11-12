package com.airi.assistant.community

import android.content.Context
import android.util.Log
import com.airi.assistant.marketplace.MarketplaceSkill
import com.airi.assistant.tools.execution.SearchSourcePolicy
import com.airi.assistant.agent.browser.HostAddressPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * CommunitySkillHub — manage community-created skills: import, export,
 * verify, sandbox-test, and score.
 *
 * ── IMPORT PATHS ─────────────────────────────────────────────────────────────
 *  - [importFromUrl] — fetch skill.json from a raw URL (GitHub, CDN, etc.)
 *  - [importFromJson] — import a skill.json pasted directly as a string
 *  - [importFromFile] — import a local skill.json file from the device
 *
 * ── EXPORT ───────────────────────────────────────────────────────────────────
 *  - [exportToJson] — serialize an installed skill to sharable JSON
 *  - [exportToFile] — write the JSON to a file in the app's Downloads directory
 *
 * ── VERIFICATION & SANDBOX ───────────────────────────────────────────────────
 *  - [verify] — runs static analysis: schema check + security pattern scan
 *  - [sandboxTest] — executes the skill in a dry-run sandbox environment
 *  - [getTrustBreakdown] — delegates to [TrustScoringEngine] for a full report
 */
class CommunitySkillHub(private val context: Context) {

    companion object {
        private const val TAG        = "CommunitySkillHub"
        private const val PREFS_NAME = "airi_community_skills"
        private const val KEY_SKILLS = "community_skills_v1"
        private const val MAX_MANIFEST_BYTES = 1_048_576L
    }

    private val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(HostAddressPolicy.PublicOnlyDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _skills    = MutableStateFlow<List<CommunitySkill>>(emptyList())
    private val _isLoading = MutableStateFlow(false)

    val skills:    StateFlow<List<CommunitySkill>> = _skills.asStateFlow()
    val isLoading: StateFlow<Boolean>              = _isLoading.asStateFlow()

    init { _skills.value = loadAll() }

    // ── Import ────────────────────────────────────────────────────────────────

    suspend fun importFromUrl(url: String): ImportResult = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val allowedUrl = allowlistedHttpsUrl(url)
            if (allowedUrl == null) {
                return@withContext ImportResult.Error("URL must use HTTPS for security.")
            }
            val request  = Request.Builder().url(allowedUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    return@withContext ImportResult.Error("Skill import redirects are not followed; use the final public HTTPS URL.")
                }
                if (!response.isSuccessful) {
                    return@withContext ImportResult.Error("Download failed: HTTP ${response.code}")
                }
                if ((response.body?.contentLength() ?: -1L) > MAX_MANIFEST_BYTES) {
                    return@withContext ImportResult.Error("Skill manifest exceeds the size limit.")
                }
                val json = readBoundedManifest(response.body)
                    ?: return@withContext ImportResult.Error("Skill manifest is empty or exceeds the size limit.")
                importFromJson(json, sourceUrl = allowedUrl)
            }
        } catch (e: Exception) {
            ImportResult.Error("Import failed; the source could not be fetched safely.")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun importFromJson(jsonString: String, sourceUrl: String? = null): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (jsonString.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
                return@withContext ImportResult.Error("Skill manifest exceeds the size limit.")
            }
            val json = runCatching { JSONObject(jsonString) }.getOrElse {
                return@withContext ImportResult.Error("Invalid JSON: ${it.message}")
            }

            // Security scan first
            val scanResult = securityScan(jsonString)
            if (scanResult.isNotEmpty()) {
                return@withContext ImportResult.SecurityBlocked(
                    "Security scan failed. Blocked patterns: ${scanResult.joinToString(", ")}"
                )
            }

            val skill = CommunitySkill(
                id          = UUID.randomUUID().toString(),
                name        = json.optString("name", "Unnamed Skill"),
                description = json.optString("description"),
                version     = json.optString("version", "1.0.0"),
                publisher   = CommunitySkill.Publisher(
                    id          = json.optString("author_id", UUID.randomUUID().toString()),
                    displayName = json.optString("author", "Unknown"),
                    isVerified  = false
                ),
                rawJson     = jsonString,
                sourceUrl   = sanitizeSourceReference(sourceUrl),
                importedAtMs = System.currentTimeMillis()
            )
            val updated = (_skills.value + skill).distinctBy { it.id }
            saveAll(updated)
            _skills.value = updated
            ImportResult.Success(skill)
        } catch (e: Exception) {
            ImportResult.Error("Parse error: ${e.message}")
        }
    }

    suspend fun importFromFile(file: File): ImportResult = withContext(Dispatchers.IO) {
        try {
            val content = file.readText(Charsets.UTF_8)
            importFromJson(content, sourceUrl = null)
        } catch (e: Exception) {
            ImportResult.Error("File read error: ${e.message}")
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportToJson(skill: CommunitySkill): String = skill.rawJson

    fun exportToFile(skill: CommunitySkill): File? {
        return try {
            val dir  = File(context.getExternalFilesDir(null), "skills").also { it.mkdirs() }
            val file = File(dir, "${skill.name.replace(" ","_").lowercase()}_${skill.version}.json")
            file.writeText(skill.rawJson, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            Log.e(TAG, "exportToFile failed: ${e.message}")
            null
        }
    }

    // ── Verification ─────────────────────────────────────────────────────────

    fun verify(skill: CommunitySkill): VerificationResult {
        val errors    = mutableListOf<String>()
        val warnings  = mutableListOf<String>()
        val json = runCatching { JSONObject(skill.rawJson) }.getOrElse {
            return VerificationResult(false, listOf("Invalid JSON"), emptyList())
        }

        if (json.optString("name").isBlank())        errors.add("Missing: name")
        if (json.optString("description").isBlank()) errors.add("Missing: description")
        if (json.optString("version").isBlank())     errors.add("Missing: version")
        if (json.optString("author").isBlank())      errors.add("Missing: author")
        if (json.optJSONArray("actions") == null)    errors.add("Missing: actions array")

        val blockedPatterns = securityScan(skill.rawJson)
        blockedPatterns.forEach { errors.add("Security: blocked pattern '$it'") }

        if (json.optString("license").isBlank()) warnings.add("No license specified")
        if (json.optString("documentation_url").isBlank()) warnings.add("No documentation URL")

        return VerificationResult(
            passed   = errors.isEmpty(),
            errors   = errors,
            warnings = warnings
        )
    }

    /**
     * Sandbox dry-run: verify the skill endpoint responds correctly to a test request.
     * Only called for skills with `endpoint` defined — pure prompt skills are auto-passed.
     */
    suspend fun sandboxTest(skill: CommunitySkill): SandboxTestResult = withContext(Dispatchers.IO) {
        val json     = runCatching { JSONObject(skill.rawJson) }.getOrNull()
            ?: return@withContext SandboxTestResult(false, "Invalid JSON")
        val endpoint = json.optString("endpoint")

        if (endpoint.isBlank()) {
            return@withContext SandboxTestResult(true, "No endpoint — prompt-only skill, auto-passed.")
        }
        val allowedEndpoint = allowlistedHttpsUrl(endpoint)
        if (allowedEndpoint == null) {
            return@withContext SandboxTestResult(false, "Endpoint must use HTTPS.")
        }

        return@withContext try {
            val testPayload = JSONObject().apply {
                put("action",  "ping")
                put("sandbox", true)
            }.toString().toRequestBody("application/json".toMediaType())
            val request  = Request.Builder().url(allowedEndpoint).post(testPayload)
                .header("X-AIRI-Sandbox", "true").build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    SandboxTestResult(false, "Endpoint redirects are not followed; configure the final public HTTPS endpoint.")
                } else if (response.isSuccessful) {
                    SandboxTestResult(true, "Endpoint responded with HTTP ${response.code} ")
                } else {
                    SandboxTestResult(false, "Endpoint returned HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            SandboxTestResult(false, "Endpoint could not be reached safely.")
        }
    }

    fun getTrustBreakdown(skill: CommunitySkill): TrustScoringEngine.TrustBreakdown =
        TrustScoringEngine.score(skill)

    // ── Management ────────────────────────────────────────────────────────────

    fun remove(skillId: String) {
        val updated = _skills.value.filter { it.id != skillId }
        saveAll(updated)
        _skills.value = updated
    }

    fun getById(id: String): CommunitySkill? = _skills.value.firstOrNull { it.id == id }

    // ── Security scan ─────────────────────────────────────────────────────────

    private fun securityScan(jsonString: String): List<String> =
        SkillSecurityScanner.scan(jsonString)

    private fun allowlistedHttpsUrl(raw: String): String? {
        if (!raw.trim().startsWith("https://", ignoreCase = true)) return null
        return (SearchSourcePolicy.evaluateRead(raw) as? SearchSourcePolicy.Decision.ReadAllowed)
            ?.normalizedUrl
    }

    private fun sanitizeSourceReference(raw: String?): String? = runCatching {
        val uri = raw?.let { java.net.URI(it) } ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        val scheme = uri.scheme.lowercase()
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port.takeIf { it >= 0 && it != defaultPort }
            ?.let { ":$it" }.orEmpty()
        "$scheme://${uri.host}$port"
    }.getOrNull()

    private fun readBoundedManifest(body: okhttp3.ResponseBody?): String? {
        body ?: return null
        if (body.contentLength() > MAX_MANIFEST_BYTES) return null
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > MAX_MANIFEST_BYTES) return null
                output.write(buffer, 0, count)
            }
        }
        if (output.size() == 0) return null
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveAll(skills: List<CommunitySkill>) {
        val arr = JSONArray()
        skills.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SKILLS, arr.toString()).apply()
    }

    private fun loadAll(): List<CommunitySkill> {
        val raw = prefs.getString(KEY_SKILLS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { CommunitySkill.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class ImportResult {
        data class Success(val skill: CommunitySkill)           : ImportResult()
        data class Error(val message: String)                   : ImportResult()
        data class SecurityBlocked(val reason: String)          : ImportResult()
    }

    data class VerificationResult(
        val passed:   Boolean,
        val errors:   List<String>,
        val warnings: List<String>
    )

    data class SandboxTestResult(val passed: Boolean, val message: String)

}

/**
 * CommunitySkill — community-imported skill with full metadata.
 * Lighter than [MarketplaceSkill] — no reviews, no pricing, just the essentials.
 */
data class CommunitySkill(
    val id:           String,
    val name:         String,
    val description:  String,
    val version:      String,
    val publisher:    Publisher,
    val rawJson:      String      = "{}",
    val sourceUrl:    String?     = null,
    val importedAtMs: Long        = 0L,
    val stats:        Stats       = Stats(),
    val trust:        TrustSignals = TrustSignals()
) {
    data class Publisher(val id: String, val displayName: String, val isVerified: Boolean = false)

    data class Stats(
        val installCount:  Int   = 0,
        val reviewCount:   Int   = 0,
        val averageRating: Float = 0f
    )

    data class TrustSignals(
        val sandboxPassRate:    Float   = 0f,
        val securityScanPassed: Boolean = false,
        val isOpenSource:       Boolean = false
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("description", description)
        put("version", version); put("raw_json", rawJson)
        put("source_url", sourceUrl ?: JSONObject.NULL)
        put("imported_at_ms", importedAtMs)
        put("publisher", JSONObject().apply {
            put("id", publisher.id); put("display_name", publisher.displayName)
            put("is_verified", publisher.isVerified)
        })
        put("stats", JSONObject().apply {
            put("install_count", stats.installCount)
            put("review_count", stats.reviewCount)
            put("average_rating", stats.averageRating.toDouble())
        })
        put("trust", JSONObject().apply {
            put("sandbox_pass_rate", trust.sandboxPassRate.toDouble())
            put("security_scan_passed", trust.securityScanPassed)
            put("is_open_source", trust.isOpenSource)
        })
    }

    companion object {
        fun fromJson(json: JSONObject): CommunitySkill = CommunitySkill(
            id          = json.getString("id"),
            name        = json.optString("name"),
            description = json.optString("description"),
            version     = json.optString("version", "1.0.0"),
            rawJson     = json.optString("raw_json", "{}"),
            sourceUrl   = json.optString("source_url").ifBlank { null },
            importedAtMs = json.optLong("imported_at_ms"),
            publisher   = json.optJSONObject("publisher")?.let { p ->
                Publisher(p.getString("id"), p.getString("display_name"), p.optBoolean("is_verified"))
            } ?: Publisher("", "Unknown"),
            stats       = json.optJSONObject("stats")?.let { s ->
                Stats(s.optInt("install_count"), s.optInt("review_count"), s.optDouble("average_rating", 0.0).toFloat())
            } ?: Stats(),
            trust       = json.optJSONObject("trust")?.let { t ->
                TrustSignals(t.optDouble("sandbox_pass_rate", 0.0).toFloat(), t.optBoolean("security_scan_passed"), t.optBoolean("is_open_source"))
            } ?: TrustSignals()
        )
    }
}
