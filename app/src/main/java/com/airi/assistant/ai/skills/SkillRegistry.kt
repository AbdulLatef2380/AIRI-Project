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
        val descriptors = listOf(
            // ── Official skills ───────────────────────────────────────────────
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "web_search",
                displayName = "Web Search",
                description = "Search the web for current information, news, facts",
                keywords    = listOf("search", "find", "look up", "google", "web", "internet", "news", "latest", "what is", "who is", "how to", "current"),
                intents     = listOf("search for", "look up", "find information", "google", "web search"),
                offlineOk   = false,
                priorityBias = 0.80f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "website_reader",
                displayName = "Website Reader",
                description = "Fetch and read content from any web page URL",
                keywords    = listOf("read", "fetch", "open url", "visit", "content from", "http", "https", "www"),
                intents     = listOf("read page", "fetch url", "open website", "read content"),
                offlineOk   = false,
                priorityBias = 0.75f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "research_agent",
                displayName = "Research Agent",
                description = "Deep research: search multiple sources and synthesize",
                keywords    = listOf("research", "investigate", "deep dive", "analysis", "analyze", "comprehensive", "report"),
                intents     = listOf("research", "deep dive", "investigate topic", "write a report"),
                offlineOk   = false,
                priorityBias = 0.85f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "translator",
                displayName = "Translator",
                description = "Translate text between any languages",
                keywords    = listOf("translate", "translation", "in french", "in spanish", "in arabic", "in german", "in chinese", "to english"),
                intents     = listOf("translate", "how do you say", "translate to"),
                offlineOk   = false,
                priorityBias = 0.85f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "code_assistant",
                displayName = "Code Assistant",
                description = "Write, explain, review, debug, and refactor code",
                keywords    = listOf("code", "program", "function", "class", "debug", "bug", "kotlin", "python", "javascript", "java", "implement", "refactor"),
                intents     = listOf("write code", "debug", "explain code", "review code", "refactor"),
                offlineOk   = false,
                priorityBias = 0.80f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "task_planner",
                displayName = "Task Planner",
                description = "Break down goals into actionable step-by-step plans",
                keywords    = listOf("plan", "planning", "roadmap", "steps", "organize", "schedule", "project", "milestone", "checklist"),
                intents     = listOf("plan", "create roadmap", "break down task", "organize project"),
                offlineOk   = false,
                priorityBias = 0.75f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "memory_manager",
                displayName = "Memory Manager",
                description = "Search, recall, and save information to persistent memory",
                keywords    = listOf("remember", "recall", "memory", "save to memory", "what did i", "do you remember", "previously"),
                intents     = listOf("remember", "recall from memory", "save to memory"),
                offlineOk   = true,
                priorityBias = 0.70f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "document_reader",
                displayName = "Document Reader",
                description = "Read and extract text from documents on device",
                keywords    = listOf("read document", "open file", "document", "pdf", "text file", "read file", "content of"),
                intents     = listOf("read document", "open file", "extract text"),
                offlineOk   = true,
                priorityBias = 0.70f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "file_manager",
                displayName = "File Manager",
                description = "List, search, and inspect files in device storage",
                keywords    = listOf("files", "folder", "directory", "storage", "downloads", "documents", "list files", "search files"),
                intents     = listOf("list files", "find file", "show storage", "search files"),
                offlineOk   = true,
                priorityBias = 0.65f
            ),
            // ── Connector skills ──────────────────────────────────────────────
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "github_guardian",
                displayName = "GitHub Guardian",
                description = "Read GitHub repos, profile, stars, issues, activity",
                keywords    = listOf("github", "repo", "repository", "commit", "code", "stars", "pull", "issue"),
                intents     = listOf("check github", "show repos", "read code", "list issues"),
                offlineOk   = false,
                connectorIds = listOf("github"),
                priorityBias = 0.75f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "gmail_assistant",
                displayName = "Gmail Assistant",
                description = "Read, summarize, and manage Gmail emails",
                keywords    = listOf("email", "gmail", "inbox", "mail", "message", "unread", "draft"),
                intents     = listOf("read email", "check inbox", "summarize email", "send mail"),
                offlineOk   = false,
                connectorIds = listOf("google"),
                priorityBias = 0.80f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "drive_search",
                displayName = "Drive Search",
                description = "Search and retrieve Google Drive files",
                keywords    = listOf("drive", "file", "document", "spreadsheet", "folder", "cloud storage"),
                intents     = listOf("find file", "search drive", "open document", "list files"),
                offlineOk   = false,
                connectorIds = listOf("google"),
                priorityBias = 0.70f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "calendar_events",
                displayName = "Calendar Events",
                description = "Check and create Google Calendar events and schedule",
                keywords    = listOf("calendar", "event", "meeting", "schedule", "appointment", "tomorrow", "today"),
                intents     = listOf("check calendar", "schedule meeting", "what do I have", "upcoming events"),
                offlineOk   = false,
                connectorIds = listOf("google"),
                permissions  = listOf(Manifest.permission.READ_CALENDAR),
                priorityBias = 0.85f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "telegram_messenger",
                displayName = "Telegram Messenger",
                description = "Send Telegram messages and notifications",
                keywords    = listOf("telegram", "message", "notify", "send", "chat"),
                intents     = listOf("send telegram", "message someone", "notify via telegram"),
                offlineOk   = false,
                connectorIds = listOf("telegram"),
                priorityBias = 0.65f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "alarm_tool",
                displayName = "Alarm & Reminder",
                description = "Set alarms, timers, and reminders",
                keywords    = listOf("alarm", "reminder", "timer", "wake", "alert", "notify"),
                intents     = listOf("set alarm", "remind me", "wake me up", "set timer"),
                offlineOk   = true,
                priorityBias = 0.90f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "search_tool",
                displayName = "Web Search",
                description = "Search the web for current information",
                keywords    = listOf("search", "find", "look up", "google", "web", "internet", "news", "latest"),
                intents     = listOf("search for", "look up", "find information", "what is"),
                offlineOk   = false,
                priorityBias = 0.70f
            ),
            AiriSkillOrchestrator.SkillDescriptor(
                skillId     = "notes_tool",
                displayName = "Notes",
                description = "Create and manage notes",
                keywords    = listOf("note", "write down", "save", "remember", "jot"),
                intents     = listOf("take note", "save note", "write note", "remember this"),
                offlineOk   = true,
                priorityBias = 0.75f
            )
        )
        descriptors.forEach { AiriSkillOrchestrator.register(it) }
    }

    fun isSkillEnabled(skillName: String): Boolean =
        disabledSkillsPrefs.getBoolean(skillName, true)

    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        disabledSkillsPrefs.edit().putBoolean(skillName, enabled).apply()
    }

    fun getAvailableSkills(): List<AiriSkill> {
        val skills = mutableListOf<AiriSkill>()

        // ── Always-available official skills ─────────────────────────────────
        if (isSkillEnabled("web_search"))       skills.add(WebSearchSkill(context))
        if (isSkillEnabled("website_reader"))   skills.add(WebsiteReaderSkill(context))
        if (isSkillEnabled("research_agent"))   skills.add(ResearchAgentSkill(context))
        if (isSkillEnabled("translator"))       skills.add(TranslatorSkill(context))
        if (isSkillEnabled("code_assistant"))   skills.add(CodeAssistantSkill(context))
        if (isSkillEnabled("task_planner"))     skills.add(TaskPlannerSkill(context))
        if (isSkillEnabled("memory_manager"))   skills.add(MemoryManagerSkill(context))
        if (isSkillEnabled("document_reader"))  skills.add(DocumentReaderSkill(context))
        if (isSkillEnabled("file_manager"))     skills.add(FileManagerSkill(context))

        // ── Connector-backed skills ───────────────────────────────────────────
        if (secureStorage.isGithubConnected() && isSkillEnabled("github_guardian")) {
            skills.add(GithubGuardianSkill(context))
        }
        if (secureStorage.isTelegramConnected() && isSkillEnabled("telegram_messenger")) {
            skills.add(TelegramMessengerSkill(context))
        }
        if (secureStorage.isGoogleConnected()) {
            if (isSkillEnabled("gmail_assistant")) skills.add(GmailAssistantSkill(context))
            if (isSkillEnabled("drive_search"))    skills.add(DriveSearchSkill(context))
            if (isSkillEnabled("calendar_events")) skills.add(CalendarEventsSkill(context))
        }

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

    data class SkillInfo(
        val name:         String,
        val description:  String,
        val isConnected:  Boolean,
        val isEnabled:    Boolean,
        val version:      String       = "1.0.0",
        val dependencies: List<String> = emptyList(),
        val author:       String       = "builtin",
        val id:           String       = name
    )

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
            "AIRI_RUNTIME SKILL_INSTALLED name=${info.name} version=${info.version} action=$action"
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
                "AIRI_RUNTIME SKILL_MANIFEST_REGISTERED id=$skillId version=${manifest.version}"
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
        // ── Always-available official skills ─────────────────────────────────
        add(SkillInfo("web_search",      "Search the web for current information",           isConnected = true,  isEnabled = isSkillEnabled("web_search"),      version = "1.1.0", author = "AIRI Official"))
        add(SkillInfo("website_reader",  "Fetch and read content from web pages",            isConnected = true,  isEnabled = isSkillEnabled("website_reader"),  version = "1.0.0", author = "AIRI Official"))
        add(SkillInfo("research_agent",  "Deep research using multiple web sources",         isConnected = true,  isEnabled = isSkillEnabled("research_agent"),  version = "1.0.0", author = "AIRI Official"))
        add(SkillInfo("translator",      "Translate text between any languages",             isConnected = true,  isEnabled = isSkillEnabled("translator"),      version = "1.0.0", author = "AIRI Official"))
        add(SkillInfo("code_assistant",  "Write, explain, review, and debug code",          isConnected = true,  isEnabled = isSkillEnabled("code_assistant"),  version = "1.0.0", author = "AIRI Official"))
        add(SkillInfo("task_planner",    "Break down goals into step-by-step plans",        isConnected = true,  isEnabled = isSkillEnabled("task_planner"),    version = "1.0.0", author = "AIRI Official"))
        add(SkillInfo("memory_manager",  "Search and save to AIRI's persistent memory",     isConnected = true,  isEnabled = isSkillEnabled("memory_manager"),  version = "1.0.0", author = "AIRI Official"))
        add(SkillInfo("document_reader", "Read text documents stored on device",            isConnected = true,  isEnabled = isSkillEnabled("document_reader"), version = "1.0.0", author = "AIRI Official"))
        add(SkillInfo("file_manager",    "List and search files in device storage",         isConnected = true,  isEnabled = isSkillEnabled("file_manager"),    version = "1.0.0", author = "AIRI Official"))
        // ── Connector-backed skills ───────────────────────────────────────────
        add(SkillInfo("github_guardian",    "Check GitHub repositories and profile",        isConnected = secureStorage.isGithubConnected(),   isEnabled = isSkillEnabled("github_guardian"),    author = "AIRI Official"))
        add(SkillInfo("telegram_messenger", "Send messages via Telegram bot",               isConnected = secureStorage.isTelegramConnected(), isEnabled = isSkillEnabled("telegram_messenger"), author = "AIRI Official"))
        add(SkillInfo("gmail_assistant",    "Read and summarize Gmail emails",              isConnected = secureStorage.isGoogleConnected(),   isEnabled = isSkillEnabled("gmail_assistant"),    author = "AIRI Official"))
        add(SkillInfo("drive_search",       "Search files in Google Drive",                 isConnected = secureStorage.isGoogleConnected(),   isEnabled = isSkillEnabled("drive_search"),       author = "AIRI Official"))
        add(SkillInfo("calendar_events",    "Get upcoming Google Calendar events",          isConnected = secureStorage.isGoogleConnected(),   isEnabled = isSkillEnabled("calendar_events"),    author = "AIRI Official"))
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
