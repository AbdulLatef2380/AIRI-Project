package com.airi.assistant.tools.capability

/**
 * Extended tool capability schema for the AIRI agent operating system.
 *
 * Extends the basic [ToolDefinition] with production-grade metadata:
 *   - Structured parameter typing (not just String maps)
 *   - Execution profile (latency, cost, cancellability)
 *   - Privacy and permission requirements
 *   - Result streaming support
 *   - MCP-compatible schema (future)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * TOOL CATEGORIES
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   SYSTEM       — Android OS interaction (alarms, calendar, contacts)
 *   PRODUCTIVITY — Files, notes, tasks, docs
 *   COMMUNICATION— Email, messaging, calls
 *   SEARCH       — Web, internal, semantic
 *   CODE         — Code execution, analysis, generation
 *   AUTOMATION   — Accessibility, app navigation, workflows
 *   EXTERNAL     — Third-party APIs (GitHub, Drive, etc.)
 *   COMPUTE      — Sandboxed script execution
 */
data class ToolCapabilitySchema(

    // ── Identity ─────────────────────────────────────────────────────────────

    /** Stable unique tool identifier. Must match [ToolDefinition.name]. */
    val toolId: String,

    /** Human-readable name shown in agent traces and UI. */
    val displayName: String,

    /** Comprehensive description for LLM routing and user display. */
    val description: String,

    /** Tool version for compatibility tracking. */
    val version: String = "1.0.0",

    // ── Parameters ───────────────────────────────────────────────────────────

    /** Typed parameter definitions (replaces the String→String map). */
    val parameters: List<ToolParameter> = emptyList(),

    // ── Categorization ───────────────────────────────────────────────────────

    val category: ToolCategory = ToolCategory.EXTERNAL,

    // ── Permissions ──────────────────────────────────────────────────────────

    /** Android runtime permissions required. */
    val requiredPermissions: List<String> = emptyList(),

    /** Whether this tool makes outbound network calls. */
    val requiresNetwork: Boolean = false,

    /** Whether this tool accesses private user data. */
    val accessesPrivateData: Boolean = false,

    // ── Execution profile ─────────────────────────────────────────────────────

    /** Expected execution time range. Used for UX timeout calibration. */
    val expectedDurationMs: IntRange = 100..5_000,

    /** Whether execution can be cancelled mid-flight. */
    val isCancellable: Boolean = true,

    /** Whether this tool can stream partial results. */
    val supportsStreaming: Boolean = false,

    /** Whether this tool can run in background without user interaction. */
    val supportsBackground: Boolean = true,

    // ── Result ────────────────────────────────────────────────────────────────

    /** Expected result content type. */
    val resultType: ResultType = ResultType.TEXT,

    // ── Safety ───────────────────────────────────────────────────────────────

    /**
     * Whether user confirmation is required before execution.
     * Set true for destructive or privacy-sensitive operations.
     */
    val requiresConfirmation: Boolean = false,

    /**
     * Whether this tool's actions can be rolled back on failure.
     * If true, the orchestrator may attempt rollback on agent failure.
     */
    val supportsRollback: Boolean = false,

    // ── MCP compatibility (future) ────────────────────────────────────────────

    /** MCP server endpoint if this tool wraps an MCP-compatible server. */
    val mcpServerEndpoint: String? = null

) {
    // ── Nested types ─────────────────────────────────────────────────────────

    data class ToolParameter(
        val name:        String,
        val type:        ParameterType,
        val description: String,
        val required:    Boolean = true,
        val defaultValue: String? = null,
        val enumValues:  List<String> = emptyList()
    )

    enum class ParameterType { STRING, INTEGER, BOOLEAN, FLOAT, JSON, FILE_URI, URL }

    enum class ResultType { TEXT, JSON, FILE, BINARY, STREAM }

    enum class ToolCategory {
        SYSTEM,
        PRODUCTIVITY,
        COMMUNICATION,
        SEARCH,
        CODE,
        AUTOMATION,
        EXTERNAL,
        COMPUTE
    }
}

/**
 * Built-in tool schema definitions.
 *
 * Register with [CapabilityAwareToolRegistry] on app startup.
 */
object BuiltinToolSchemas {

    val CALENDAR = ToolCapabilitySchema(
        toolId              = "calendar_tool",
        displayName         = "Calendar",
        description         = "Create, read, update, and delete calendar events. Supports recurring events and reminders.",
        category            = ToolCapabilitySchema.ToolCategory.SYSTEM,
        requiredPermissions = listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
        accessesPrivateData = true,
        requiresConfirmation = true,
        supportsRollback    = true,
        parameters = listOf(
            ToolCapabilitySchema.ToolParameter("action",    ToolCapabilitySchema.ParameterType.STRING,  "create|read|update|delete"),
            ToolCapabilitySchema.ToolParameter("title",     ToolCapabilitySchema.ParameterType.STRING,  "Event title", required = false),
            ToolCapabilitySchema.ToolParameter("startTime", ToolCapabilitySchema.ParameterType.STRING,  "ISO-8601 start time", required = false),
            ToolCapabilitySchema.ToolParameter("endTime",   ToolCapabilitySchema.ParameterType.STRING,  "ISO-8601 end time", required = false)
        )
    )

    val ALARM = ToolCapabilitySchema(
        toolId              = "alarm_tool",
        displayName         = "Alarms & Timers",
        description         = "Set, cancel, and list alarms and countdown timers.",
        category            = ToolCapabilitySchema.ToolCategory.SYSTEM,
        parameters = listOf(
            ToolCapabilitySchema.ToolParameter("action",  ToolCapabilitySchema.ParameterType.STRING, "set|cancel|list"),
            ToolCapabilitySchema.ToolParameter("time",    ToolCapabilitySchema.ParameterType.STRING, "HH:mm or duration like '25 minutes'", required = false),
            ToolCapabilitySchema.ToolParameter("label",   ToolCapabilitySchema.ParameterType.STRING, "Alarm label", required = false)
        )
    )

    val WEB_SEARCH = ToolCapabilitySchema(
        toolId           = "web_search_tool",
        displayName      = "Web Search",
        description      = "Search the web for current information. Returns summarized results.",
        category         = ToolCapabilitySchema.ToolCategory.SEARCH,
        requiresNetwork  = true,
        supportsStreaming = true,
        expectedDurationMs = 500..8_000,
        parameters = listOf(
            ToolCapabilitySchema.ToolParameter("query",   ToolCapabilitySchema.ParameterType.STRING, "Search query"),
            ToolCapabilitySchema.ToolParameter("maxResults", ToolCapabilitySchema.ParameterType.INTEGER, "Max results to return", required = false, defaultValue = "5")
        )
    )

    val CODE_EXEC = ToolCapabilitySchema(
        toolId              = "code_exec_tool",
        displayName         = "Code Execution",
        description         = "Execute Python or JavaScript in an isolated sandbox with resource limits.",
        category            = ToolCapabilitySchema.ToolCategory.COMPUTE,
        supportsStreaming    = true,
        isCancellable       = true,
        requiresConfirmation = true,
        expectedDurationMs  = 500..30_000,
        parameters = listOf(
            ToolCapabilitySchema.ToolParameter("language", ToolCapabilitySchema.ParameterType.STRING, "python|javascript"),
            ToolCapabilitySchema.ToolParameter("code",     ToolCapabilitySchema.ParameterType.STRING, "Source code to execute"),
            ToolCapabilitySchema.ToolParameter("timeoutMs",ToolCapabilitySchema.ParameterType.INTEGER,"Execution timeout ms", required = false, defaultValue = "10000")
        )
    )

    val GMAIL = ToolCapabilitySchema(
        toolId              = "gmail_tool",
        displayName         = "Gmail",
        description         = "Read, search, draft, and send Gmail messages.",
        category            = ToolCapabilitySchema.ToolCategory.COMMUNICATION,
        requiresNetwork     = true,
        accessesPrivateData = true,
        requiresConfirmation = true,
        parameters = listOf(
            ToolCapabilitySchema.ToolParameter("action",  ToolCapabilitySchema.ParameterType.STRING, "read|search|draft|send"),
            ToolCapabilitySchema.ToolParameter("query",   ToolCapabilitySchema.ParameterType.STRING, "Search query or recipient", required = false),
            ToolCapabilitySchema.ToolParameter("subject", ToolCapabilitySchema.ParameterType.STRING, "Email subject", required = false),
            ToolCapabilitySchema.ToolParameter("body",    ToolCapabilitySchema.ParameterType.STRING, "Email body", required = false)
        )
    )

    val all: List<ToolCapabilitySchema> = listOf(CALENDAR, ALARM, WEB_SEARCH, CODE_EXEC, GMAIL)
}
