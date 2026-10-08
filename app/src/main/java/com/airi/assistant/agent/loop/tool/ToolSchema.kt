package com.airi.assistant.agent.loop.tool

/**
 * ToolSchema — the single canonical tool definition used across AgentLoop.
 *
 * Replaces both [com.airi.assistant.tools.ToolDefinition] (String maps) and
 * [com.airi.assistant.tools.capability.ToolCapabilitySchema] (duplication).
 * The old classes are preserved for their connectors/skills callers but
 * AgentLoop only uses ToolSchema.
 */
data class ToolSchema(
    val name:        String,
    val description: String,
    val parameters:  Map<String, Param> = emptyMap(),
    val dangerous:   Boolean = false,   // requires user confirmation before execution
    val category:    Category = Category.SYSTEM
) {
    data class Param(
        val type:        String,          // "string" | "int" | "boolean"
        val description: String = "",
        val required:    Boolean = true,
        val minLength:   Int? = null,
        val maxLength:   Int? = null,
        val minInt:      Long? = null,
        val maxInt:      Long? = null,
        val allowedValues: Set<String> = emptySet(),
        val allowedSchemes: Set<String> = emptySet(),
    )

    enum class Category {
        SYSTEM,         // Android OS (alarm, calendar, contacts)
        PRODUCTIVITY,   // Notes, files, tasks
        SEARCH,         // Web, memory
        AUTOMATION,     // Accessibility / app navigation
        COMMUNICATION,  // Email, messaging
        EXTERNAL        // Third-party connectors
    }
}

/** The concrete tool schemas wired into AgentLoop for every session. */
object BuiltinTools {

    val READ_SCREEN = ToolSchema(
        name        = "read_screen",
        description = "Read the current Android screen content — returns visible text, focused element, and active app name.",
        category    = ToolSchema.Category.AUTOMATION
    )

    val OPEN_APP = ToolSchema(
        name        = "open_app",
        description = "Launch an installed Android app by its name.",
        parameters  = mapOf("app_name" to ToolSchema.Param("string", "App name as shown on device, e.g. 'Settings', 'WhatsApp'", maxLength = 128)),
        category    = ToolSchema.Category.AUTOMATION
    )

    val TAP = ToolSchema(
        name        = "tap",
        description = "Tap a UI element by its visible text or content description.",
        parameters  = mapOf("target" to ToolSchema.Param("string", "Visible text or label of the element to tap", minLength = 1, maxLength = 256)),
        category    = ToolSchema.Category.AUTOMATION,
        dangerous   = false
    )

    val TYPE_TEXT = ToolSchema(
        name        = "type_text",
        description = "Type text into the currently focused input field.",
        parameters  = mapOf("text" to ToolSchema.Param("string", "Text to type", minLength = 1, maxLength = 10_000)),
        category    = ToolSchema.Category.AUTOMATION
    )

    val SCROLL_DOWN = ToolSchema(
        name        = "scroll_down",
        description = "Scroll the current screen downward."
    )

    val GO_BACK = ToolSchema(
        name        = "go_back",
        description = "Press the Android back button."
    )

    val WEB_SEARCH = ToolSchema(
        name        = "web_search",
        description = "Search the web and return the top results summary.",
        parameters  = mapOf("query" to ToolSchema.Param("string", "Search query", minLength = 1, maxLength = 2_000)),
        category    = ToolSchema.Category.SEARCH
    )

    val MEMORY_RECALL = ToolSchema(
        name        = "memory_recall",
        description = "Search the user's personal memory for relevant facts.",
        parameters  = mapOf("query" to ToolSchema.Param("string", "What to look for in memory", minLength = 1, maxLength = 2_000)),
        category    = ToolSchema.Category.SEARCH
    )

    val CALENDAR_READ = ToolSchema(
        name        = "calendar_read",
        description = "Read upcoming calendar events.",
        parameters  = mapOf("days" to ToolSchema.Param("int", "How many days ahead to look", required = false, minInt = 1, maxInt = 366)),
        category    = ToolSchema.Category.SYSTEM
    )

    val CURRENT_TIME = ToolSchema(
        name        = "current_time",
        description = "Read the current date, time, timezone, and UTC offset from the Android device.",
        category    = ToolSchema.Category.SYSTEM
    )

    val FLASHLIGHT = ToolSchema(
        name = "flashlight",
        description = "Turn the rear camera torch on or off and verify the hardware state.",
        parameters = mapOf("enabled" to ToolSchema.Param("boolean", "true to turn on, false to turn off")),
        category = ToolSchema.Category.SYSTEM,
        dangerous = true,
    )

    val CALENDAR_CREATE = ToolSchema(
        name        = "calendar_create",
        description = "Create a new calendar event.",
        parameters  = mapOf(
            "title"       to ToolSchema.Param("string", "Event title", minLength = 1, maxLength = 256),
            "start_time"  to ToolSchema.Param("string", "ISO-8601 datetime or natural language like '3pm tomorrow'", minLength = 1, maxLength = 128),
            "duration_min" to ToolSchema.Param("int", "Duration in minutes", required = false, minInt = 1, maxInt = 1_440)
        ),
        category  = ToolSchema.Category.SYSTEM,
        dangerous = true
    )

    val SET_ALARM = ToolSchema(
        name        = "set_alarm",
        description = "Set an alarm or reminder.",
        parameters  = mapOf(
            "time"  to ToolSchema.Param("string", "Time as 'HH:mm' or natural language like '7am'", minLength = 1, maxLength = 128),
            "label" to ToolSchema.Param("string", "Alarm label", required = false, maxLength = 256)
        ),
        category = ToolSchema.Category.SYSTEM
    )

    val CREATE_NOTE = ToolSchema(
        name        = "create_note",
        description = "Save a note to the user's notes.",
        parameters  = mapOf(
            "title"   to ToolSchema.Param("string", "Note title", minLength = 1, maxLength = 256),
            "content" to ToolSchema.Param("string", "Note body text", minLength = 1, maxLength = 50_000)
        ),
        category = ToolSchema.Category.PRODUCTIVITY
    )

    val TERMINAL_EXECUTE = ToolSchema(
        name        = "terminal_execute",
        description = "Run one command in AIRI's restricted sandbox terminal and return its output. Network and unsafe commands remain blocked by AIRI governance.",
        parameters  = mapOf("command" to ToolSchema.Param("string", "A single shell command for the restricted AIRI sandbox", minLength = 1, maxLength = 4_000)),
        category    = ToolSchema.Category.PRODUCTIVITY,
        dangerous  = true
    )

    val ASK_CONFIRMATION = ToolSchema(
        name        = "ask_confirmation",
        description = "Ask the user to confirm before proceeding with a sensitive action.",
        parameters  = mapOf(
            "action"  to ToolSchema.Param("string", "What you are about to do", minLength = 1, maxLength = 512),
            "details" to ToolSchema.Param("string", "Why this is needed", required = false, maxLength = 4_000)
        ),
        category  = ToolSchema.Category.SYSTEM,
        dangerous = false
    )

    val FETCH_URL = ToolSchema(
        name        = "fetch_url",
        description = "Fetch the full text content of a web page. Use after web_search to read the actual content, verify facts, or extract detailed information from a specific URL.",
        parameters  = mapOf("url" to ToolSchema.Param("url", "Full URL to fetch (must start with https://", minLength = 1, maxLength = 4_096, allowedSchemes = setOf("https"))),
        category    = ToolSchema.Category.SEARCH,
        dangerous   = false
    )

        /** Full set of tools for an ACTION-capable session. */
    val ALL: List<ToolSchema> = listOf(
        READ_SCREEN, OPEN_APP, TAP, TYPE_TEXT, SCROLL_DOWN, GO_BACK,
        WEB_SEARCH, FETCH_URL, MEMORY_RECALL,
        CALENDAR_READ, CURRENT_TIME, FLASHLIGHT, CALENDAR_CREATE, SET_ALARM, CREATE_NOTE, TERMINAL_EXECUTE,
        ASK_CONFIRMATION
    )

    /** Minimal set for plain chat (no accessibility, no calendar write). */
    val CHAT_ONLY: List<ToolSchema> = listOf(
        WEB_SEARCH, FETCH_URL, MEMORY_RECALL, CALENDAR_READ, CURRENT_TIME, CREATE_NOTE, TERMINAL_EXECUTE
    )
}
