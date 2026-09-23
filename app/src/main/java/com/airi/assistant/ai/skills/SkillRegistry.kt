package com.airi.assistant.ai.skills

import android.Manifest
import android.content.Context
import com.airi.assistant.ai.skills.impl.CalendarEventsSkill
import com.airi.assistant.ai.skills.impl.CodeAssistantSkill
import com.airi.assistant.ai.skills.impl.DocumentReaderSkill
import com.airi.assistant.ai.skills.impl.DriveSearchSkill
import com.airi.assistant.ai.skills.impl.FileManagerSkill
import com.airi.assistant.ai.skills.impl.GithubGuardianSkill
import com.airi.assistant.ai.skills.impl.GmailAssistantSkill
import com.airi.assistant.ai.skills.impl.MemoryManagerSkill
import com.airi.assistant.ai.skills.impl.ResearchAgentSkill
import com.airi.assistant.ai.skills.impl.TaskPlannerSkill
import com.airi.assistant.ai.skills.impl.TelegramMessengerSkill
import com.airi.assistant.ai.skills.impl.TranslatorSkill
import com.airi.assistant.ai.skills.impl.WebSearchSkill
import com.airi.assistant.ai.skills.impl.WebsiteReaderSkill
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillExecutor
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.domain.customskill.SkillConfig
import com.airi.assistant.domain.customskill.SkillType
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

class SkillRegistry(private val context: Context) {

    // AP-04: Use ServiceLocator singleton to prevent split-brain on Keystore failure.
    private val secureStorage get() = ServiceLocator.secureStorage
    val connectorAvailability: StateFlow<com.airi.assistant.auth.SecureStorage.IntegrationConnections>
        get() = secureStorage.integrationConnections
    private val customSkillRepository = CustomSkillRepository(context)

    /** Shared executor instance for custom-skill adapters returned from [getAvailableSkills]. */
    private val customSkillExecutor by lazy { CustomSkillExecutor(context) }

    private val disabledSkillsPrefs by lazy {
        context.getSharedPreferences("airi_skill_toggles", Context.MODE_PRIVATE)
    }

    init {
        // Register all known skills with the AiriSkillOrchestrator so it can
        // automatically discover and match them without manual configuration.
        registerOrchestrationDescriptors()
    }

    private fun registerOrchestrationDescriptors() {
        // OfficialSkillLibrary is the canonical source. A second hand-written
        // descriptor list previously caused catalog-only IDs and omitted batches.
        OfficialSkillLibrary.ALL.forEach { entry ->
            val manifest = entry.manifest
            val connectorIds = manifest.dependencies
                .filter { it.startsWith("connector:") }
                .map { it.removePrefix("connector:") }
            val keywords = (manifest.tags +
                manifest.id.replace('_', ' ').split(' ') +
                manifest.name.split(' '))
                .map { it.trim().lowercase() }
                .filter { it.length > 2 }
                .distinct()
            register(
                AiriSkillOrchestrator.SkillDescriptor(
                    skillId = manifest.id,
                    displayName = manifest.displayName,
                    description = manifest.description,
                    keywords = keywords,
                    intents = manifest.examples + listOf(
                        manifest.displayName,
                        manifest.id.replace('_', ' ')
                    ),
                    offlineOk = manifest.modelAccess == SkillModelAccess.NONE &&
                        manifest.category !in setOf("SEARCH", "COMMUNICATION", "DATA"),
                    connectorIds = connectorIds,
                    permissions = manifest.permissions,
                    priorityBias = when (manifest.riskLevel) {
                        SkillRiskLevel.LOW -> 0.70f
                        SkillRiskLevel.MEDIUM -> 0.60f
                        SkillRiskLevel.HIGH, SkillRiskLevel.CRITICAL -> 0.50f
                    }
                )
            )
        }
    }

    fun isSkillEnabled(skillName: String): Boolean =
        disabledSkillsPrefs.getBoolean(skillName, true)

    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        disabledSkillsPrefs.edit().putBoolean(skillName, enabled).apply()
    }

    fun getAvailableSkills(): List<AiriSkill> {
        // OfficialSkillLibrary is the single runtime source. Every official
        // manifest has a factory, so discovery, enablement, routing, tool
        // schemas, and execution use one canonical ID set.
        val skills = OfficialSkillLibrary.ALL
            .filter { entry ->
                isSkillEnabled(entry.manifest.id) &&
                    areRuntimeDependenciesAvailable(entry.manifest.dependencies)
            }
            .mapNotNull { entry -> runCatching { entry.factory(context) }.getOrNull() }
            .toMutableList()

        // ── Custom / marketplace-installed skills (Phase B fix) ───────────────
        // Without this block, installed marketplace skills were listed in the system
        // prompt via buildSkillDescriptionBlock() but could never be invoked by the
        // agent loop, because getAvailableSkills() is the sole routing source for
        // SkillToolBridge.invoke() and ToolDispatcher.
        customSkillRepository.getAllSkills()
            .filter { isSkillEnabled(it.id) && hasRunnableEndpoint(it) }
            .forEach { customSkill ->
                skills.add(CustomSkillAiriSkillAdapter(customSkill, customSkillExecutor))
            }

        return skills
    }

    private fun areRuntimeDependenciesAvailable(dependencies: List<String>): Boolean =
        dependencies.filter { it.startsWith("connector:") }.all { dependency ->
            when (dependency.removePrefix("connector:")) {
                "github" -> secureStorage.isGithubConnected()
                "telegram" -> secureStorage.isTelegramConnected()
                "google" -> secureStorage.isGoogleConnected()
                else -> false
            }
        }

    data class SkillInfo(
        val name:         String,
        val description:  String,
        val isConnected:  Boolean,
        val isEnabled:    Boolean,
        val version:      String       = "1.0.0",
        val dependencies: List<String> = emptyList(),
        val author:       String       = "builtin",
        val id:           String       = name,
        /** Whether execution stays on-device or requires a remote service. */
        val executionKind: ExecutionKind = ExecutionKind.LOCAL,
        /** Human-readable Android/service permissions shown before enabling. */
        val requiredPermissions: List<String> = emptyList()
    )

    enum class ExecutionKind { LOCAL, CLOUD }

    // ── Version registry (persisted in SharedPreferences) ─────────────────────

    private val versionPrefs by lazy {
        context.getSharedPreferences("airi_skill_versions", Context.MODE_PRIVATE)
    }

    /**
     * Record the installed version of a skill.
     * Called by [installSkillWithVersion] and on first boot for built-ins.
     */
    fun setInstalledVersion(skillName: String, version: String) {
        versionPrefs.edit().putString("v_$skillName", version).apply()
    }

    /** Returns the currently installed version, or "1.0.0" if not recorded. */
    fun getInstalledVersion(skillName: String): String =
        versionPrefs.getString("v_$skillName", "1.0.0") ?: "1.0.0"

    /**
     * Install or upgrade a skill from a [SkillInfo] descriptor.
     *
     * Version comparison follows Semantic Versioning (major.minor.patch).
     * Downgrade is blocked unless [allowDowngrade] is true.
     *
     * @return [InstallResult] describing what happened.
     */
    fun installSkillWithVersion(
        info:           SkillInfo,
        allowDowngrade: Boolean = false
    ): InstallResult {
        val existing = getInstalledVersion(info.name)
        val action = when {
            compareVersions(info.version, existing) > 0  -> "upgrade"
            compareVersions(info.version, existing) == 0 -> "same"
            allowDowngrade                                -> "downgrade"
            else                                          -> return InstallResult.Blocked(
                "Downgrade from $existing to ${info.version} blocked for skill '${info.name}'. " +
                "Pass allowDowngrade=true to force."
            )
        }
        // Phase E: circular dependency detection
        val circular = detectCircularDependencies(info.name)
        if (circular.isNotEmpty()) {
            return InstallResult.DependencyFailure(
                listOf("Circular dependency detected: ${circular.joinToString(" → ")}")
            )
        }

        val depResult = validateDependencies(info.dependencies)
        if (!depResult.satisfied) {
            return InstallResult.DependencyFailure(depResult.missing)
        }
        setSkillEnabled(info.name, true)
        setInstalledVersion(info.name, info.version)
        android.util.Log.i(
            "SkillRegistry",
            "AIRI SKILL_INSTALLED name=${info.name} version=${info.version} action=$action"
        )
        return InstallResult.Success(info.name, info.version, action)
    }

    /**
     * Validate that all dependency skill IDs are installed and enabled.
     *
     * A dependency is satisfied when it appears in [getAllSkillInfos] with
     * [SkillInfo.isEnabled] = true. Unconnected but enabled skills count —
     * connection state is a runtime concern, not an install-time constraint.
     */
    fun validateDependencies(dependencies: List<String>): DependencyValidation {
        if (dependencies.isEmpty()) return DependencyValidation(satisfied = true, missing = emptyList())
        val enabledIds = getAllSkillInfos()
            .filter { it.isEnabled }
            .map { it.id }
            .toSet()
        val missing = dependencies.filter { dep -> dep !in enabledIds }
        return DependencyValidation(satisfied = missing.isEmpty(), missing = missing)
    }

    /** Result of [validateDependencies]. */
    data class DependencyValidation(val satisfied: Boolean, val missing: List<String>)

    // ── Circular dependency detection (Phase E) ────────────────────────────────

    /**
     * Detect circular dependencies starting from [skillId].
     *
     * Returns the dependency chain that forms the cycle (e.g. ["A", "B", "C", "A"]),
     * or an empty list if no cycle exists.
     */
    fun detectCircularDependencies(skillId: String): List<String> =
        detectCircularDeps(skillId, emptyList())

    private fun detectCircularDeps(skillId: String, chain: List<String>): List<String> {
        if (skillId in chain) return chain + skillId        // cycle found
        val info = getAllSkillInfos().firstOrNull { it.id == skillId } ?: return emptyList()
        for (dep in info.dependencies) {
            val result = detectCircularDeps(dep, chain + skillId)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    // ── Dynamic skill registration (Phase A+B) ────────────────────────────────

    /**
     * Convert a [SkillManifest] into a [CustomSkill] and persist it via
     * [CustomSkillRepository] so that [getAvailableSkills] auto-discovers it
     * on the next call — making the skill immediately usable in the agent loop.
     *
     * Called by [com.airi.assistant.marketplace.MarketplaceRepository.install] and
     * [com.airi.assistant.marketplace.GitHubSkillImporter] after successful import.
     *
     * @param manifest   The parsed manifest describing the skill.
     * @param endpoint   The HTTPS endpoint where the skill's API is hosted.
     *                   Falls back to [SkillManifest.homepage] or [SkillManifest.repositoryUrl].
     * @return           true on success, false if persistence or validation fails.
     */
    fun registerDynamicFromManifest(
        manifest: SkillManifest,
        endpoint: String,
        method: String = "POST",
        bodyTemplate: String = """{"input": "{{input}}"}""",
        headers: Map<String, String> = emptyMap(),
        type: SkillType = SkillType.API,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean {
        val skillId = manifest.id.trim()
        val resolvedEndpoint = endpoint.trim()
        val resolvedMethod = method.trim().uppercase()
        if (
            !isValidDynamicManifest(manifest) ||
            !isTrustedHttpsEndpoint(resolvedEndpoint) ||
            resolvedMethod !in SUPPORTED_HTTP_METHODS ||
            bodyTemplate.isBlank() ||
            type !in REMOTE_SKILL_TYPES
        ) {
            android.util.Log.w("SkillRegistry", "Rejected dynamic skill registration id=$skillId")
            return false
        }
        return runCatching {
            val customSkill = CustomSkill(
                id = skillId,
                name = manifest.name.trim(),
                description = manifest.description.trim(),
                type = type,
                config = SkillConfig(
                    endpoint = resolvedEndpoint,
                    method = resolvedMethod,
                    headers = headers,
                    bodyTemplate = bodyTemplate
                ),
                createdAt = createdAt
            )
            customSkillRepository.saveSkill(customSkill)
            setSkillEnabled(skillId, true)
            setInstalledVersion(skillId, manifest.version)
            android.util.Log.i(
                "SkillRegistry",
                "AIRI SKILL_MANIFEST_REGISTERED id=$skillId version=${manifest.version}"
            )
            true
        }.getOrElse { error ->
            android.util.Log.e(
                "SkillRegistry",
                "Dynamic skill registration failed id=$skillId error=${error.javaClass.simpleName}"
            )
            false
        }
    }

    fun isCustomSkillAvailable(skill: CustomSkill): Boolean =
        isSkillEnabled(skill.id) && hasRunnableEndpoint(skill)

    /** Canonical discovery API used by UI and planners; callers must not maintain parallel lists. */
    fun findById(skillId: String): SkillInfo? =
        getAllSkillInfos().firstOrNull { it.id == skillId || it.name == skillId }

    fun search(query: String): List<SkillInfo> {
        val normalized = query.trim()
        if (normalized.isBlank()) return getAllSkillInfos()
        return getAllSkillInfos().filter { info ->
            info.id.contains(normalized, ignoreCase = true) ||
                info.name.contains(normalized, ignoreCase = true) ||
                info.description.contains(normalized, ignoreCase = true)
        }
    }

    fun enabledSkills(): List<SkillInfo> = getAllSkillInfos().filter { it.isEnabled }

    fun connectedSkills(): List<SkillInfo> = getAllSkillInfos().filter { it.isConnected }

    fun byCategory(category: String): List<SkillInfo> {
        val normalized = category.trim()
        if (normalized.isBlank() || normalized.equals("ALL", ignoreCase = true)) return getAllSkillInfos()
        return getAllSkillInfos().filter { info ->
            OfficialSkillLibrary.manifestFor(info.id)
                ?.category
                ?.equals(normalized, ignoreCase = true) == true
        }
    }

    fun requiredCapabilities(skillId: String): SkillCapabilitySummary? {
        val manifest = OfficialSkillLibrary.manifestFor(skillId) ?: return null
        return SkillCapabilitySummary(
            skillId = manifest.id,
            permissions = manifest.permissions,
            tools = manifest.tools.map { it.name },
            dependencies = manifest.dependencies,
            riskLevel = manifest.riskLevel,
            requiresConfirmation = manifest.requiresConfirmation,
            supportsStreaming = manifest.supportsStreaming,
            supportsAttachments = manifest.supportsAttachments
        )
    }

    data class SkillCapabilitySummary(
        val skillId: String,
        val permissions: List<String>,
        val tools: List<String>,
        val dependencies: List<String>,
        val riskLevel: SkillRiskLevel,
        val requiresConfirmation: Boolean,
        val supportsStreaming: Boolean,
        val supportsAttachments: Boolean
    )

    private fun isValidDynamicManifest(manifest: SkillManifest): Boolean =
        manifest.id.matches(Regex("^[a-z][a-z0-9_-]{2,63}$")) &&
            manifest.name.trim().length in 3..80 &&
            manifest.description.trim().length in 10..500 &&
            manifest.version.matches(Regex("^\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?(\\+[A-Za-z0-9.]+)?$"))

    private fun hasRunnableEndpoint(skill: CustomSkill): Boolean =
        isTrustedHttpsEndpoint(skill.config.endpoint)

    private fun isTrustedHttpsEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo.isNullOrBlank()
    }.getOrDefault(false)

    /** Result of [installSkillWithVersion]. */
    sealed class InstallResult {
        data class Success(val name: String, val version: String, val action: String) : InstallResult()
        data class Blocked(val reason: String) : InstallResult()
        data class DependencyFailure(val missingDeps: List<String>) : InstallResult()
    }

    /**
     * Semantic version comparison. Returns positive if [a] > [b], 0 if equal, negative if [a] < [b].
     * Parses "major.minor.patch" — falls back to string comparison on parse error.
     */
    private fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    fun getAllSkillInfos(): List<SkillInfo> = buildList {
        // ── Official catalog is the single discovery source ────────────────────
        addAll(OfficialSkillLibrary.ALL.map { entry ->
            val dependencies = entry.manifest.dependencies
            val connected = when {
                "connector:github" in dependencies -> secureStorage.isGithubConnected()
                "connector:telegram" in dependencies -> secureStorage.isTelegramConnected()
                "connector:google" in dependencies -> secureStorage.isGoogleConnected()
                else -> true
            }
            SkillInfo(
                id = entry.manifest.id,
                name = entry.manifest.displayName,
                description = entry.manifest.description,
                isConnected = connected,
                isEnabled = isSkillEnabled(entry.manifest.id),
                version = entry.manifest.version,
                dependencies = entry.manifest.dependencies,
                author = entry.manifest.author,
                executionKind = if (entry.manifest.modelAccess == SkillModelAccess.NONE) ExecutionKind.LOCAL else ExecutionKind.CLOUD,
                requiredPermissions = entry.manifest.permissions
            )
        })
        // ── Custom / user-installed skills ────────────────────────────────────
        addAll(customSkillRepository.getAllSkills().map { skill ->
            SkillInfo(
                id = skill.id,
                name = skill.name,
                description = skill.description,
                isConnected = hasRunnableEndpoint(skill),
                isEnabled = isSkillEnabled(skill.id)
            )
        })
    }

    fun buildSkillDescriptionBlock(): String {
        val available = getAvailableSkills()
        if (available.isEmpty()) return ""
        return buildString {
            append("\n\nYou have access to the following Skills for high-level tasks:")
            for (skill in available) {
                val meta = SKILL_METADATA[skill.name]
                append("\n\n- Skill: ${skill.name}")
                append("\n  Description: ${skill.description}")
                if (meta != null) {
                    append("\n  When to use: ${meta.whenToUse}")
                    append("\n  Expected input: ${meta.expectedInput}")
                }
            }
            append(
                "\n\nUse these skills intelligently when the user's intent matches them. " +
                        "Skills are separate from Tools — prefer skills for known integration tasks."
            )
        }
    }

    private data class SkillMeta(val whenToUse: String, val expectedInput: String)

    private companion object {
        private val SUPPORTED_HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        private val REMOTE_SKILL_TYPES = setOf(SkillType.API, SkillType.WEBHOOK)

        private val SKILL_METADATA = mapOf(
            "web_search" to SkillMeta(
                whenToUse = "When user asks about current events, facts, news, or needs information from the internet",
                expectedInput = "Natural language search query (e.g. 'latest AI news', 'who is the CEO of Apple')"
            ),
            "website_reader" to SkillMeta(
                whenToUse = "When user provides a URL and wants its content read or summarized",
                expectedInput = "URL string (must start with https://)"
            ),
            "research_agent" to SkillMeta(
                whenToUse = "When user wants a deep, comprehensive analysis of a topic using multiple sources",
                expectedInput = "Research question or topic (e.g. 'research the history of quantum computing')"
            ),
            "translator" to SkillMeta(
                whenToUse = "When user asks to translate text to another language",
                expectedInput = "Text to translate and target language (e.g. 'translate hello to French')"
            ),
            "code_assistant" to SkillMeta(
                whenToUse = "When user asks to write, explain, debug, review, or refactor code",
                expectedInput = "Coding task description with optional code snippet"
            ),
            "task_planner" to SkillMeta(
                whenToUse = "When user wants to break down a project or goal into actionable steps",
                expectedInput = "Goal or project description (e.g. 'plan how to launch a mobile app')"
            ),
            "memory_manager" to SkillMeta(
                whenToUse = "When user wants to save information to memory or recall past conversations",
                expectedInput = "Action ('recall'/'save') and query or content"
            ),
            "document_reader" to SkillMeta(
                whenToUse = "When user shares a document file and asks to read or analyze it",
                expectedInput = "Content URI of the document shared from the device"
            ),
            "file_manager" to SkillMeta(
                whenToUse = "When user asks to list, find, or get info about files on their device",
                expectedInput = "Action ('list'/'search'/'storage_info') and optional directory/query"
            ),
            "github_guardian" to SkillMeta(
                whenToUse = "When user asks about their GitHub repos, profile, stars, or code activity",
                expectedInput = "Natural language query about GitHub (e.g. 'show my repos', 'how many stars do I have')"
            ),
            "telegram_messenger" to SkillMeta(
                whenToUse = "When user asks to send a Telegram message or notification",
                expectedInput = "chat_id and message text (e.g. 'send hello to @mychat')"
            ),
            "gmail_assistant" to SkillMeta(
                whenToUse = "When user asks to read, summarize, or check their emails",
                expectedInput = "Natural language query about email (e.g. 'show my latest emails')"
            ),
            "drive_search" to SkillMeta(
                whenToUse = "When user wants to find or search files in their Google Drive",
                expectedInput = "File name or search query (e.g. 'find my resume')"
            ),
            "calendar_events" to SkillMeta(
                whenToUse = "When user asks about upcoming meetings, events, or schedule",
                expectedInput = "Natural language request for calendar info (e.g. 'what do I have tomorrow')"
            )
        )
    }
}
