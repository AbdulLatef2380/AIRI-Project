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
        val required:    Boolean = true
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
        parameters  = mapOf("app_name" to ToolSchema.Param("string", "App name as shown on device, e.g. 'Settings', 'WhatsApp'")),
        category    = ToolSchema.Category.AUTOMATION
    )

    val TAP = ToolSchema(
        name        = "tap",
        description = "Tap a UI element by its visible text or content description.",
        parameters  = mapOf("target" to ToolSchema.Param("string", "Visible text or label of the element to tap")),
        category    = ToolSchema.Category.AUTOMATION,
        dangerous   = false
    )

    val TYPE_TEXT = ToolSchema(
        name        = "type_text",
        description = "Type text into the currently focused input field.",
        parameters  = mapOf("text" to ToolSchema.Param("string", "Text to type")),
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
        parameters  = mapOf("query" to ToolSchema.Param("string", "Search query")),
        category    = ToolSchema.Category.SEARCH
    )

    val MEMORY_RECALL = ToolSchema(
        name        = "memory_recall",
        description = "Search the user's personal memory for relevant facts.",
        parameters  = mapOf("query" to ToolSchema.Param("string", "What to look for in memory")),
        category    = ToolSchema.Category.SEARCH
    )

    val CALENDAR_READ = ToolSchema(
        name        = "calendar_read",
        description = "Read upcoming calendar events.",
        parameters  = mapOf("days" to ToolSchema.Param("int", "How many days ahead to look", required = false)),
        category    = ToolSchema.Category.SYSTEM
    )

    val CALENDAR_CREATE = ToolSchema(
        name        = "calendar_create",
        description = "Create a new calendar event.",
        parameters  = mapOf(
            "title"       to ToolSchema.Param("string", "Event title"),
            "start_time"  to ToolSchema.Param("string", "ISO-8601 datetime or natural language like '3pm tomorrow'"),
            "duration_min" to ToolSchema.Param("int", "Duration in minutes", required = false)
        ),
        category  = ToolSchema.Category.SYSTEM,
        dangerous = true
    )

    val SET_ALARM = ToolSchema(
        name        = "set_alarm",
        description = "Set an alarm or reminder.",
        parameters  = mapOf(
            "time"  to ToolSchema.Param("string", "Time as 'HH:mm' or natural language like '7am'"),
            "label" to ToolSchema.Param("string", "Alarm label", required = false)
        ),
        category = ToolSchema.Category.SYSTEM
    )

    val CREATE_NOTE = ToolSchema(
        name        = "create_note",
        description = "Save a note to the user's notes.",
        parameters  = mapOf(
            "title"   to ToolSchema.Param("string", "Note title"),
            "content" to ToolSchema.Param("string", "Note body text")
        ),
        category = ToolSchema.Category.PRODUCTIVITY
    )

    val ASK_CONFIRMATION = ToolSchema(
        name        = "ask_confirmation",
        description = "Ask the user to confirm before proceeding with a sensitive action.",
        parameters  = mapOf(
            "action"  to ToolSchema.Param("string", "What you are about to do"),
            "details" to ToolSchema.Param("string", "Why this is needed", required = false)
        ),
        category  = ToolSchema.Category.SYSTEM,
        dangerous = false
    )

    /** Full set of tools for an ACTION-capable session. */
    val ALL: List<ToolSchema> = listOf(
        READ_SCREEN, OPEN_APP, TAP, TYPE_TEXT, SCROLL_DOWN, GO_BACK,
        WEB_SEARCH, MEMORY_RECALL,
        CALENDAR_READ, CALENDAR_CREATE, SET_ALARM, CREATE_NOTE,
        ASK_CONFIRMATION
    )

    /** Minimal set for plain chat (no accessibility, no calendar write). */
    val CHAT_ONLY: List<ToolSchema> = listOf(
        WEB_SEARCH, MEMORY_RECALL, CALENDAR_READ, CREATE_NOTE
    )
}
