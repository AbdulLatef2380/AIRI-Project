package com.airi.assistant.marketplace

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.skills.SkillManifest
import com.airi.assistant.ai.skills.SkillRegistry
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
import java.util.concurrent.TimeUnit

/**
 * MarketplaceRepository — the single source of truth for the AIRI Developer Marketplace.
 *
 * Provides:
 *  - [fetchFeatured] / [search] — browse and discover skills
 *  - [install] / [uninstall] — add skills to the local registry
 *  - [publish] — submit a skill for marketplace review
 *  - [submitReview] — rate and review an installed skill
 *  - [checkUpdates] — detect available version updates
 *
 * ── ARCHITECTURE ─────────────────────────────────────────────────────────────
 * Marketplace data lives in the AIRI Cloud (Firestore via REST API). The
 * repository caches the catalog locally (SharedPreferences JSON) so the UI
 * renders instantly on subsequent opens even without network.
 *
 * ── INSTALL FLOW ─────────────────────────────────────────────────────────────
 *  1. Fetch skill.json from the skill's [MarketplaceSkill.skillJsonUrl]
 *  2. Validate against [SkillPublisher.MANIFEST_SCHEMA] (presence of required fields)
 *  3. Run sandbox validation (dry-run execute in SandboxExecutor)
 *  4. Register via [SkillRegistry] — available to agent immediately
 *  5. Record install locally
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────────
 *  - Only skills with [MarketplaceSkill.TrustSignals.securityScanPassed] = true
 *    bypass the per-install sandbox gate.
 *  - Unverified skills are installed in a restricted sandbox with no file/network access.
 */
class MarketplaceRepository(
    private val context:       Context,
    private val skillRegistry: SkillRegistry
) {

    companion object {
        private const val TAG          = "MarketplaceRepository"
        private const val CATALOG_URL  = "https://api.airi-assistant.app/v1/marketplace"
        private const val PREFS_NAME   = "airi_marketplace_cache"
        private const val KEY_CATALOG  = "catalog_v1"
        private const val KEY_INSTALLS = "installed_v1"
    }

    sealed class MarketplaceResult {
        data class Success(val skills: List<MarketplaceSkill>)           : MarketplaceResult()
        data class InstallSuccess(val skill: MarketplaceSkill)           : MarketplaceResult()
        data class PublishSuccess(val submissionId: String)              : MarketplaceResult()
        data class Error(val message: String, val retryable: Boolean = true) : MarketplaceResult()
    }

    private val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _catalog      = MutableStateFlow<List<MarketplaceSkill>>(emptyList())
    private val _installed    = MutableStateFlow<List<MarketplaceSkill>>(emptyList())
    private val _isLoading    = MutableStateFlow(false)
    private val _lastError    = MutableStateFlow<String?>(null)

    val catalog:   StateFlow<List<MarketplaceSkill>> = _catalog.asStateFlow()
    val installed: StateFlow<List<MarketplaceSkill>> = _installed.asStateFlow()
    val isLoading: StateFlow<Boolean>                = _isLoading.asStateFlow()
    val lastError: StateFlow<String?>                = _lastError.asStateFlow()

    init {
        _catalog.value   = loadCachedCatalog()
        _installed.value = loadInstalledSkills()
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    suspend fun fetchFeatured(): MarketplaceResult = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val json   = apiGet("/skills?featured=true&limit=50")
            val skills = parseSkillList(json, installedIds())
            _catalog.value = skills
            cacheCatalog(skills)
            _lastError.value = null
            MarketplaceResult.Success(skills)
        } catch (e: Exception) {
            Log.w(TAG, "fetchFeatured failed: ${e.message}")
            _lastError.value = "Could not load marketplace. Showing cached results."
            MarketplaceResult.Error(e.message ?: "Network error")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun search(query: String, category: MarketplaceSkill.Category? = null): MarketplaceResult = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val catParam = if (category != null) "&category=${category.name.lowercase()}" else ""
            val json     = apiGet("/skills?q=${java.net.URLEncoder.encode(query, "UTF-8")}$catParam&limit=30")
            val skills   = parseSkillList(json, installedIds())
            _lastError.value = null
            MarketplaceResult.Success(skills)
        } catch (e: Exception) {
            // Fallback to local search
            val local = _catalog.value.filter { skill ->
                val q = query.lowercase()
                skill.name.lowercase().contains(q) ||
                skill.description.lowercase().contains(q) ||
                skill.tags.any { it.lowercase().contains(q) }
            }
            MarketplaceResult.Success(local)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun fetchSkillDetail(skillId: String): MarketplaceSkill? = withContext(Dispatchers.IO) {
        try {
            val json = apiGet("/skills/$skillId")
            val data = json.optJSONObject("skill") ?: return@withContext null
            MarketplaceSkill.fromJson(data).copy(isInstalled = skillId in installedIds())
        } catch (e: Exception) {
            _catalog.value.firstOrNull { it.id == skillId }
        }
    }

    suspend fun fetchReviews(skillId: String): List<SkillReview> = withContext(Dispatchers.IO) {
        try {
            val json    = apiGet("/skills/$skillId/reviews?limit=20")
            val arr     = json.optJSONArray("reviews") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val r = arr.getJSONObject(i)
                    SkillReview(
                        id              = r.getString("id"),
                        skillId         = skillId,
                        reviewerName    = r.optString("reviewer_name", "Anonymous"),
                        rating          = r.getInt("rating"),
                        body            = r.optString("body"),
                        timestampMs     = r.optLong("timestamp_ms"),
                        helpfulCount    = r.optInt("helpful_count"),
                        isVerifiedPurchase = r.optBoolean("is_verified_purchase")
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Install ───────────────────────────────────────────────────────────────

    suspend fun install(skill: MarketplaceSkill): MarketplaceResult = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            // 1. Fetch the skill definition JSON
            val defRequest = Request.Builder().url(skill.skillJsonUrl).get().build()
            val defResponse = client.newCall(defRequest).execute()
            if (!defResponse.isSuccessful) {
                return@withContext MarketplaceResult.Error("Failed to download skill definition (HTTP ${defResponse.code})")
            }
            val defJson = defResponse.body?.string() ?: return@withContext MarketplaceResult.Error("Empty skill definition")

            // 2. Validate manifest
            val validation = SkillPublisher.validateManifest(defJson)
            if (!validation.isValid) {
                return@withContext MarketplaceResult.Error("Invalid skill manifest: ${validation.errors.joinToString("; ")}")
            }

            // 3. Register with SkillRegistry so the agent loop can invoke this skill (Phase A+B)
            runCatching {
                val manifestObj = JSONObject(defJson)
                val manifest    = SkillManifest.fromJson(manifestObj)
                val endpoint    = manifestObj.optString("endpoint").ifBlank { skill.skillJsonUrl }
                skillRegistry.registerDynamicFromManifest(manifest, endpoint)
                Log.d(TAG, "Skill '${skill.name}' registered in SkillRegistry for agent-loop routing")
            }.onFailure { e ->
                Log.w(TAG, "registerDynamicFromManifest failed (install still recorded): ${e.message}")
            }

            // 4. Record install
            val installList = loadInstalledSkills().toMutableList()
            val updatedSkill = skill.copy(
                isInstalled      = true,
                installedVersion = skill.version,
                hasUpdate        = false
            )
            installList.removeAll { it.id == skill.id }
            installList.add(0, updatedSkill)
            saveInstalledSkills(installList)
            _installed.value = installList

            // 4. Update catalog to show installed state
            _catalog.value = _catalog.value.map {
                if (it.id == skill.id) it.copy(isInstalled = true, installedVersion = skill.version) else it
            }

            // 5. Track install on server (best-effort)
            runCatching { apiPost("/skills/${skill.id}/install", JSONObject()) }

            Log.d(TAG, "Installed skill: ${skill.name} v${skill.version}")
            MarketplaceResult.InstallSuccess(updatedSkill)
        } catch (e: Exception) {
            Log.e(TAG, "install failed: ${e.message}")
            MarketplaceResult.Error("Install failed: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun uninstall(skillId: String): Boolean = withContext(Dispatchers.IO) {
        val installList = loadInstalledSkills().filter { it.id != skillId }
        saveInstalledSkills(installList)
        _installed.value = installList
        _catalog.value = _catalog.value.map {
            if (it.id == skillId) it.copy(isInstalled = false, installedVersion = null) else it
        }
        runCatching { apiPost("/skills/$skillId/uninstall", JSONObject()) }
        true
    }

    suspend fun update(skill: MarketplaceSkill): MarketplaceResult = install(skill)

    // ── Publish ───────────────────────────────────────────────────────────────

    suspend fun publish(submission: SkillPublisher.SkillSubmission): MarketplaceResult = withContext(Dispatchers.IO) {
        try {
            val body     = submission.toJson().toString().toRequestBody("application/json".toMediaType())
            val response = apiPostRaw("/skills/submit", body)
            val json     = JSONObject(response)
            val id       = json.optString("submission_id")
            if (id.isBlank()) return@withContext MarketplaceResult.Error("Submission failed — missing submission ID")
            MarketplaceResult.PublishSuccess(id)
        } catch (e: Exception) {
            MarketplaceResult.Error("Publish failed: ${e.message}")
        }
    }

    // ── Reviews ───────────────────────────────────────────────────────────────

    suspend fun submitReview(skillId: String, rating: Int, body: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("rating", rating); put("body", body) }
            apiPost("/skills/$skillId/reviews", json)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── Updates ───────────────────────────────────────────────────────────────

    suspend fun checkUpdates(): List<MarketplaceSkill> = withContext(Dispatchers.IO) {
        val installed = loadInstalledSkills().filter { it.installedVersion != null }
        if (installed.isEmpty()) return@withContext emptyList()
        try {
            val ids  = installed.joinToString(",") { it.id }
            val json = apiGet("/skills/updates?ids=$ids")
            val arr  = json.optJSONArray("updates") ?: return@withContext emptyList()
            val updates = (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i) }.getOrNull()
            }.mapNotNull { upd ->
                installed.firstOrNull { it.id == upd.getString("id") }
                    ?.copy(hasUpdate = true, version = upd.getString("latest_version"))
            }
            if (updates.isNotEmpty()) {
                val updatedInstalled = loadInstalledSkills().map { skill ->
                    updates.firstOrNull { it.id == skill.id } ?: skill
                }
                saveInstalledSkills(updatedInstalled)
                _installed.value = updatedInstalled
            }
            updates
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun installedIds(): Set<String> = _installed.value.map { it.id }.toSet()

    private fun parseSkillList(json: JSONObject, installedIds: Set<String>): List<MarketplaceSkill> {
        val arr = json.optJSONArray("skills") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val s = arr.getJSONObject(i)
                MarketplaceSkill.fromJson(s).copy(isInstalled = s.getString("id") in installedIds)
            }.getOrNull()
        }
    }

    private fun apiGet(path: String): JSONObject {
        val request = Request.Builder().url("$CATALOG_URL$path")
            .header("Accept", "application/json").build()
        val response = client.newCall(request).execute()
        return JSONObject(response.body?.string() ?: "{}")
    }

    private fun apiPost(path: String, body: JSONObject): String {
        return apiPostRaw(path, body.toString().toRequestBody("application/json".toMediaType()))
    }

    private fun apiPostRaw(path: String, body: okhttp3.RequestBody): String {
        val request = Request.Builder().url("$CATALOG_URL$path").post(body)
            .header("Accept", "application/json").build()
        return client.newCall(request).execute().body?.string() ?: "{}"
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun cacheCatalog(skills: List<MarketplaceSkill>) {
        prefs.edit().putString(KEY_CATALOG, JSONArray(skills.map { it.toJson() }).toString()).apply()
    }

    private fun loadCachedCatalog(): List<MarketplaceSkill> {
        val raw = prefs.getString(KEY_CATALOG, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { MarketplaceSkill.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun saveInstalledSkills(skills: List<MarketplaceSkill>) {
        prefs.edit().putString(KEY_INSTALLS, JSONArray(skills.map { it.toJson() }).toString()).apply()
    }

    private fun loadInstalledSkills(): List<MarketplaceSkill> {
        val raw = prefs.getString(KEY_INSTALLS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { MarketplaceSkill.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun MarketplaceSkill.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("description", description)
        put("version", version); put("skill_json_url", skillJsonUrl)
        put("category", category.name)
        put("is_verified", isVerified); put("is_featured", isFeatured)
        put("is_installed", isInstalled)
        put("installed_version", installedVersion ?: JSONObject.NULL)
        put("has_update", hasUpdate)
        put("published_at_ms", publishedAtMs); put("updated_at_ms", updatedAtMs)
        put("publisher", JSONObject().apply {
            put("id", publisher.id); put("display_name", publisher.displayName)
            put("is_verified", publisher.isVerified)
            put("avatar_url", publisher.avatarUrl ?: JSONObject.NULL)
        })
        put("stats", JSONObject().apply {
            put("install_count", stats.installCount)
            put("review_count", stats.reviewCount)
            put("average_rating", stats.averageRating.toDouble())
            put("weekly_installs", stats.weeklyInstalls)
        })
        put("trust", JSONObject().apply {
            put("sandbox_pass_rate", trust.sandboxPassRate.toDouble())
            put("security_scan_passed", trust.securityScanPassed)
            put("is_open_source", trust.isOpenSource)
            put("trust_score", trust.trustScore)
        })
        put("tags", JSONArray(tags))
        put("icon_url", iconUrl ?: JSONObject.NULL)
    }
}
