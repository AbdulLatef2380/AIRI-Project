package com.airi.assistant.ai.skills

import android.content.Context
import com.airi.assistant.ai.skills.impl.CalendarEventsSkill
import com.airi.assistant.ai.skills.impl.AdvancedModelSkill
import com.airi.assistant.ai.skills.impl.CodeAssistantSkill
import com.airi.assistant.ai.skills.impl.DocumentReaderSkill
import com.airi.assistant.ai.skills.impl.DocumentAnalysisSkill
import com.airi.assistant.ai.skills.impl.DefensiveEngineeringSkill
import com.airi.assistant.ai.skills.impl.EngineeringQualitySkill
import com.airi.assistant.ai.skills.impl.DriveSearchSkill
import com.airi.assistant.ai.skills.impl.FileManagerSkill
import com.airi.assistant.ai.skills.impl.GithubGuardianSkill
import com.airi.assistant.ai.skills.impl.GmailAssistantSkill
import com.airi.assistant.ai.skills.impl.MemoryManagerSkill
import com.airi.assistant.ai.skills.impl.ModelUtilitySkill
import com.airi.assistant.ai.skills.impl.ResearchAgentSkill
import com.airi.assistant.ai.skills.impl.ReminderPlanningSkill
import com.airi.assistant.ai.skills.impl.TaskPlannerSkill
import com.airi.assistant.ai.skills.impl.TelegramMessengerSkill
import com.airi.assistant.ai.skills.impl.TranslatorSkill
import com.airi.assistant.ai.skills.impl.WebSearchSkill
import com.airi.assistant.ai.skills.impl.WebsiteReaderSkill

/**
 * OfficialSkillLibrary — the complete catalog of first-party AIRI skills.
 *
 * Every skill here is a real executor that wraps existing Android/AIRI
 * infrastructure. No placeholders, no demo cards.
 *
 * Skills are grouped by capability tier:
 *  - CORE   : Always available; no connector required.
 *  - SEARCH : Require network; may need API keys for full functionality.
 *  - AI     : Require an active AI model.
 *  - SYSTEM : Require Android system permissions.
 *  - CONNECTOR : Require an external connector (GitHub, Gmail, Telegram, etc.)
 */
object OfficialSkillLibrary {

    enum class Tier { CORE, SEARCH, AI, SYSTEM, CONNECTOR }

    data class OfficialEntry(
        val manifest: SkillManifest,
        val tier:     Tier,
        val factory:  (Context) -> AiriSkill
    )

    val ALL: List<OfficialEntry> = (listOf(

        // ── Search ────────────────────────────────────────────────────────────

        OfficialEntry(
            manifest = SkillManifest(
                id           = "web_search",
                name         = "Web Search",
                description  = "Search the web for current information, news, facts, and answers",
                version      = "1.1.0",
                author       = "AIRI Official",
                category     = "SEARCH",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.READ_WRITE,
                modelAccess  = SkillModelAccess.NONE,
                tags         = listOf("search", "web", "internet", "news", "facts"),
                tools        = listOf(
                    SkillManifest.ToolDef("web_search", "Search the web and return top results",
                        mapOf("query" to SkillManifest.ParamDef("string", "Search query")))
                )
            ),
            tier    = Tier.SEARCH,
            factory = ::WebSearchSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "website_reader",
                name         = "Website Reader",
                description  = "Fetch and extract the full text content of any web page URL",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "SEARCH",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.READ_WRITE,
                modelAccess  = SkillModelAccess.NONE,
                tags         = listOf("web", "url", "read", "scrape", "extract"),
                tools        = listOf(
                    SkillManifest.ToolDef("fetch_url", "Fetch the text content of a URL",
                        mapOf("url" to SkillManifest.ParamDef("string", "Full URL")))
                )
            ),
            tier    = Tier.SEARCH,
            factory = ::WebsiteReaderSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "research_agent",
                name         = "Research Agent",
                description  = "Deep research: searches multiple sources, reads pages, synthesizes a comprehensive answer",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "AI",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.READ_WRITE,
                modelAccess  = SkillModelAccess.CHAT,
                tags         = listOf("research", "analysis", "deep dive", "report"),
                tools        = listOf(
                    SkillManifest.ToolDef("research", "Deep research on a topic",
                        mapOf("query" to SkillManifest.ParamDef("string", "Research question")))
                )
            ),
            tier    = Tier.AI,
            factory = ::ResearchAgentSkill
        ),

        // ── AI / Model-Powered ────────────────────────────────────────────────

        OfficialEntry(
            manifest = SkillManifest(
                id           = "translator",
                name         = "Translator",
                description  = "Translate text between any languages using the active AI model",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "PRODUCTIVITY",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.NONE,
                modelAccess  = SkillModelAccess.CHAT,
                tags         = listOf("translate", "language", "multilingual"),
                tools        = listOf(
                    SkillManifest.ToolDef("translate_text", "Translate text to another language",
                        mapOf(
                            "text"            to SkillManifest.ParamDef("string", "Text to translate"),
                            "target_language" to SkillManifest.ParamDef("string", "Target language")
                        ))
                )
            ),
            tier    = Tier.AI,
            factory = ::TranslatorSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "code_assistant",
                name         = "Code Assistant",
                description  = "Write, explain, review, debug, and refactor code in any programming language",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "DEVELOPER",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.READ_ONLY,
                modelAccess  = SkillModelAccess.CHAT,
                tags         = listOf("code", "programming", "debug", "refactor", "dev"),
                tools        = listOf(
                    SkillManifest.ToolDef("code_assist", "Help with a coding task",
                        mapOf(
                            "task"     to SkillManifest.ParamDef("string", "What to do"),
                            "code"     to SkillManifest.ParamDef("string", "Existing code", required = false),
                            "language" to SkillManifest.ParamDef("string", "Language", required = false)
                        ))
                )
            ),
            tier    = Tier.AI,
            factory = ::CodeAssistantSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "task_planner",
                name         = "Task Planner",
                description  = "Break down complex goals into actionable step-by-step plans with priorities",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "PRODUCTIVITY",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.READ_WRITE,
                modelAccess  = SkillModelAccess.CHAT,
                tags         = listOf("plan", "productivity", "organize", "project", "tasks"),
                tools        = listOf(
                    SkillManifest.ToolDef("plan_tasks", "Create an action plan for a goal",
                        mapOf("goal" to SkillManifest.ParamDef("string", "The goal to plan")))
                )
            ),
            tier    = Tier.AI,
            factory = ::TaskPlannerSkill
        ),

        // ── Memory & Files ────────────────────────────────────────────────────

        OfficialEntry(
            manifest = SkillManifest(
                id           = "memory_manager",
                name         = "Memory Manager",
                description  = "Search, recall, and save information to AIRI's persistent memory",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "AI",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.FULL_ACCESS,
                modelAccess  = SkillModelAccess.NONE,
                tags         = listOf("memory", "recall", "remember", "save"),
                tools        = listOf(
                    SkillManifest.ToolDef("memory_recall", "Recall from memory",
                        mapOf("query" to SkillManifest.ParamDef("string", "What to recall"))),
                    SkillManifest.ToolDef("memory_save", "Save to memory",
                        mapOf("content" to SkillManifest.ParamDef("string", "Content to save")))
                )
            ),
            tier    = Tier.CORE,
            factory = ::MemoryManagerSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "document_reader",
                name         = "Document Reader",
                description  = "Read and extract text from documents stored on your device (TXT, MD, CSV, JSON, HTML, XML)",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "PRODUCTIVITY",
                isOfficial   = true,
                iconEmoji    = "",
                permissions  = listOf("android.permission.READ_EXTERNAL_STORAGE"),
                memoryAccess = SkillMemoryAccess.READ_WRITE,
                modelAccess  = SkillModelAccess.NONE,
                tags         = listOf("document", "file", "read", "text", "pdf"),
                tools        = listOf(
                    SkillManifest.ToolDef("read_document", "Read a document file",
                        mapOf("uri" to SkillManifest.ParamDef("string", "Content URI")))
                )
            ),
            tier    = Tier.SYSTEM,
            factory = ::DocumentReaderSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "file_manager",
                name         = "File Manager",
                description  = "List, search, and inspect files in device storage directories",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "PRODUCTIVITY",
                isOfficial   = true,
                iconEmoji    = "",
                permissions  = listOf("android.permission.READ_EXTERNAL_STORAGE"),
                memoryAccess = SkillMemoryAccess.NONE,
                modelAccess  = SkillModelAccess.NONE,
                tags         = listOf("files", "storage", "directory", "folder"),
                tools        = listOf(
                    SkillManifest.ToolDef("list_files", "List files in a directory",
                        mapOf("directory" to SkillManifest.ParamDef("string", "Path", required = false))),
                    SkillManifest.ToolDef("search_files", "Search files by name",
                        mapOf("query" to SkillManifest.ParamDef("string", "Name pattern")))
                )
            ),
            tier    = Tier.SYSTEM,
            factory = ::FileManagerSkill
        ),

        // ── Connectors ────────────────────────────────────────────────────────

        OfficialEntry(
            manifest = SkillManifest(
                id           = "github_guardian",
                name         = "GitHub Guardian",
                description  = "Read GitHub repositories, profile, stars, issues, and activity",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "DEVELOPER",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.NONE,
                modelAccess  = SkillModelAccess.NONE,
                dependencies = listOf("connector:github"),
                tags         = listOf("github", "code", "repos", "git")
            ),
            tier    = Tier.CONNECTOR,
            factory = ::GithubGuardianSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "gmail_assistant",
                name         = "Gmail Assistant",
                description  = "Read, summarize, and manage Gmail emails",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "COMMUNICATION",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.NONE,
                modelAccess  = SkillModelAccess.NONE,
                dependencies = listOf("connector:google"),
                tags         = listOf("email", "gmail", "inbox", "mail")
            ),
            tier    = Tier.CONNECTOR,
            factory = ::GmailAssistantSkill
        ),
        OfficialEntry(
            manifest = SkillManifest(
                id           = "drive_search",
                name         = "Google Drive Search",
                description  = "Search and retrieve files from the connected Google Drive account",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "DATA",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.NONE,
                modelAccess  = SkillModelAccess.NONE,
                dependencies = listOf("connector:google"),
                tags         = listOf("drive", "google", "files", "documents", "data"),
                tools        = listOf(
                    SkillManifest.ToolDef("drive_search_file", "Search Google Drive files by name or keywords",
                        mapOf("query" to SkillManifest.ParamDef("string", "File name or search keywords")))
                )
            ),
            tier    = Tier.CONNECTOR,
            factory = ::DriveSearchSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "calendar_events",
                name         = "Calendar Events",
                description  = "Check and create Google Calendar events and schedule",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "PRODUCTIVITY",
                isOfficial   = true,
                iconEmoji    = "",
                permissions  = listOf("android.permission.READ_CALENDAR"),
                memoryAccess = SkillMemoryAccess.NONE,
                modelAccess  = SkillModelAccess.NONE,
                dependencies = listOf("connector:google"),
                tags         = listOf("calendar", "events", "schedule", "meetings")
            ),
            tier    = Tier.CONNECTOR,
            factory = ::CalendarEventsSkill
        ),

        OfficialEntry(
            manifest = SkillManifest(
                id           = "telegram_messenger",
                name         = "Telegram Messenger",
                description  = "Send Telegram messages and notifications via bot",
                version      = "1.0.0",
                author       = "AIRI Official",
                category     = "COMMUNICATION",
                isOfficial   = true,
                iconEmoji    = "",
                memoryAccess = SkillMemoryAccess.NONE,
                modelAccess  = SkillModelAccess.NONE,
                dependencies = listOf("connector:telegram"),
                tags         = listOf("telegram", "message", "notify", "bot")
            ),
            tier    = Tier.CONNECTOR,
            factory = ::TelegramMessengerSkill
        ),
        OfficialEntry(
            manifest = SkillManifest(
                id = "pdf_analysis",
                name = "PDF Analysis",
                description = "Extract bounded text and page metadata from PDF documents on-device",
                version = "1.0.0",
                author = "AIRI Official",
                category = "DOCUMENTS",
                isOfficial = true,
                modelAccess = SkillModelAccess.NONE,
                tags = listOf("pdf", "document", "pages", "extract", "تحليل pdf"),
                tools = listOf(SkillManifest.ToolDef("pdf_analysis", "Analyze a PDF URI", mapOf("uri" to SkillManifest.ParamDef("string", "Content URI"))))
            ),
            tier = Tier.SYSTEM,
            factory = { context -> DocumentAnalysisSkill(context, DocumentAnalysisSkill.Mode.PDF) }
        ),
        OfficialEntry(
            manifest = SkillManifest(
                id = "ocr_analysis",
                name = "OCR Analysis",
                description = "Recognize Latin text from images or rendered PDF pages on-device",
                version = "1.0.0",
                author = "AIRI Official",
                category = "DOCUMENTS",
                isOfficial = true,
                modelAccess = SkillModelAccess.NONE,
                tags = listOf("ocr", "scan", "image", "text recognition", "التعرف الضوئي"),
                tools = listOf(SkillManifest.ToolDef("ocr_analysis", "Recognize text from an image URI", mapOf(
                    "uri" to SkillManifest.ParamDef("string", "Content URI"),
                    "language" to SkillManifest.ParamDef("string", "auto, latin, or arabic")
                )))
            ),
            tier = Tier.SYSTEM,
            factory = { context -> DocumentAnalysisSkill(context, DocumentAnalysisSkill.Mode.OCR) }
        ),
        OfficialEntry(
            manifest = SkillManifest(
                id = "reminder_planning",
                name = "Reminder Planning",
                description = "Parse and schedule one-shot reminders or timers behind explicit confirmation",
                version = "1.0.0",
                author = "AIRI Official",
                category = "PRODUCTIVITY",
                isOfficial = true,
                modelAccess = SkillModelAccess.NONE,
                tags = listOf("reminder", "alarm", "timer", "schedule", "تذكير", "منبه"),
                riskLevel = SkillRiskLevel.MEDIUM,
                requiresConfirmation = true,
                tools = listOf(SkillManifest.ToolDef("reminder_planning", "Preview or schedule a reminder", mapOf("input" to SkillManifest.ParamDef("string", "Reminder text"))))
            ),
            tier = Tier.SYSTEM,
            factory = ::ReminderPlanningSkill
        )
    ) + ModelUtilitySkill.SPECS.map(::modelEntry) + AdvancedModelSkill.SPECS.map(::advancedEntry) + EngineeringQualitySkill.SPECS.map(::qualityEntry) + DefensiveEngineeringSkill.SPECS.map(::defensiveEngineeringEntry)).map(::normalizeEntry)

    /** Fill safe, derived metadata for legacy built-ins without changing their runtime contract. */
    private fun normalizeEntry(entry: OfficialEntry): OfficialEntry {
        val manifest = entry.manifest
        val input = if (manifest.inputSchema.isNotEmpty()) manifest.inputSchema
        else manifest.tools.flatMap { it.parameters.keys }.associateWith { "string" }
        val output = if (manifest.outputSchema.isNotEmpty()) manifest.outputSchema
        else mapOf("result" to "string")
        return entry.copy(
            manifest = manifest.copy(
                displayName = manifest.displayName.ifBlank { manifest.name },
                inputSchema = input.ifEmpty { mapOf("input" to "string") },
                outputSchema = output,
                instructions = manifest.instructions.ifBlank { manifest.description },
                examples = manifest.examples.ifEmpty { listOf("Use ${manifest.name}") },
                limitations = manifest.limitations.ifEmpty { listOf("Results depend on the supplied input and available permissions.") }
            )
        )
    }

    private fun modelEntry(spec: ModelUtilitySkill.Spec): OfficialEntry = OfficialEntry(
        manifest = SkillManifest(
            id = spec.id,
            name = spec.name,
            description = spec.description,
            version = "1.0.0",
            author = "AIRI Official",
            category = spec.category,
            isOfficial = true,
            memoryAccess = SkillMemoryAccess.NONE,
            modelAccess = SkillModelAccess.CHAT,
            tools = listOf(SkillManifest.ToolDef(spec.id, spec.description, spec.parameters.mapValues { (key, type) -> SkillManifest.ParamDef(type, key, key == "input") })),
            tags = spec.keywords,
            inputSchema = spec.parameters,
            outputSchema = mapOf("result" to "string"),
            instructions = spec.instructions,
            examples = spec.examples,
            limitations = spec.limitations,
            supportsStreaming = false
        ),
        tier = Tier.AI,
        factory = { ModelUtilitySkill(spec) }
    )

    private fun advancedEntry(spec: AdvancedModelSkill.Spec): OfficialEntry = OfficialEntry(
        manifest = SkillManifest(
            id = spec.id,
            name = spec.name,
            description = spec.description,
            version = "1.0.0",
            author = "AIRI Official",
            category = spec.category,
            isOfficial = true,
            memoryAccess = SkillMemoryAccess.NONE,
            modelAccess = SkillModelAccess.CHAT,
            tools = listOf(SkillManifest.ToolDef(spec.id, spec.description, mapOf("input" to SkillManifest.ParamDef("string", "Task input")))),
            tags = spec.keywords,
            inputSchema = mapOf("input" to "string"),
            outputSchema = mapOf("result" to "string", "stages" to "integer"),
            instructions = spec.stages.joinToString(" ") { it.instruction },
            examples = spec.examples,
            limitations = spec.limitations,
            riskLevel = SkillRiskLevel.MEDIUM,
            supportsStreaming = false
        ),
        tier = Tier.AI,
        factory = { AdvancedModelSkill(spec) }
    )

    private fun qualityEntry(spec: EngineeringQualitySkill.Spec): OfficialEntry = OfficialEntry(
        manifest = SkillManifest(
            id = spec.id,
            name = spec.name,
            description = spec.description,
            version = "1.0.0",
            author = "AIRI Official",
            category = spec.category,
            isOfficial = true,
            memoryAccess = SkillMemoryAccess.NONE,
            modelAccess = SkillModelAccess.CHAT,
            tools = listOf(SkillManifest.ToolDef(spec.id, spec.description, mapOf("input" to SkillManifest.ParamDef("string", "Code, metrics, or design input")))),
            tags = spec.keywords,
            inputSchema = mapOf("input" to "string"),
            outputSchema = mapOf("findings" to "string", "verification_tests" to "string"),
            instructions = spec.stages.joinToString(" ") { it.instruction },
            examples = spec.examples,
            limitations = spec.limitations,
            riskLevel = SkillRiskLevel.LOW,
            supportsStreaming = false
        ),
        tier = Tier.AI,
        factory = { EngineeringQualitySkill(spec) }
    )

    private fun defensiveEngineeringEntry(spec: DefensiveEngineeringSkill.Spec): OfficialEntry = OfficialEntry(
        manifest = SkillManifest(
            id = spec.id,
            name = spec.name,
            description = spec.description,
            version = "1.0.0",
            author = "AIRI Official",
            category = spec.category,
            isOfficial = true,
            memoryAccess = SkillMemoryAccess.NONE,
            modelAccess = SkillModelAccess.CHAT,
            permissions = emptyList(),
            tools = listOf(SkillManifest.ToolDef(spec.id, spec.description, mapOf("input" to SkillManifest.ParamDef("string", "Source, logs, configuration, or report")))),
            tags = spec.keywords + if (spec.defensive) listOf("defensive", "authorized", "analysis-only") else emptyList(),
            inputSchema = mapOf("input" to "string"),
            outputSchema = mapOf("findings" to "string", "safe_actions" to "string", "verification" to "string"),
            instructions = spec.stages.joinToString(" ") { it.instruction },
            examples = spec.examples,
            limitations = spec.limitations,
            riskLevel = if (spec.defensive) SkillRiskLevel.MEDIUM else SkillRiskLevel.LOW,
            requiresConfirmation = false,
            supportsStreaming = false,
            supportsAttachments = false
        ),
        tier = Tier.AI,
        factory = { DefensiveEngineeringSkill(spec) }
    )

    /** Get the manifest for a skill by its ID. */
    fun manifestFor(skillId: String): SkillManifest? =
        ALL.firstOrNull { it.manifest.id == skillId }?.manifest

    /** Create a live skill instance by its ID. */
    fun instantiate(skillId: String, context: Context): AiriSkill? =
        ALL.firstOrNull { it.manifest.id == skillId }?.factory?.invoke(context)

    /** All skill IDs in the official library. */
    val ids: List<String> get() = ALL.map { it.manifest.id }

    /** Manifests grouped by tier. */
    fun byTier(tier: Tier): List<OfficialEntry> = ALL.filter { it.tier == tier }
}
