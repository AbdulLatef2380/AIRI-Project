package com.airi.assistant.agent.loop.tool

import com.airi.assistant.security.ScopedPermissionRegistry.AgentPermission

data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: Map<String, Param> = emptyMap(),
    val dangerous: Boolean = false,
    val category: Category = Category.SYSTEM,
    val requiredPermissions: Set<AgentPermission> = emptySet()
) {
    data class Param(
        val type: String,
        val description: String = "",
        val required: Boolean = true,
        val min: Long? = null,
        val max: Long? = null,
        val maxLength: Int? = null,
        val httpsOnly: Boolean = false
    )
    enum class Category { SYSTEM, PRODUCTIVITY, SEARCH, AUTOMATION, COMMUNICATION, EXTERNAL }
    fun validate(args: Map<String, String>): ValidationResult {
        val unknown = args.keys - parameters.keys
        if (unknown.isNotEmpty()) return ValidationResult.Invalid("unknown arguments: ${unknown.sorted()}")
        val missing = parameters.filterValues { it.required }.keys - args.keys
        if (missing.isNotEmpty()) return ValidationResult.Invalid("missing arguments: ${missing.sorted()}")
        for ((key, value) in args) {
            val spec = parameters.getValue(key)
            if (value.isBlank() && spec.required) return ValidationResult.Invalid("blank argument: $key")
            when (spec.type.lowercase()) {
                "string" -> Unit
                "int", "long" -> {
                    val number = value.toLongOrNull() ?: return ValidationResult.Invalid("$key must be an integer")
                    if (spec.min != null && number < spec.min) return ValidationResult.Invalid("$key below minimum")
                    if (spec.max != null && number > spec.max) return ValidationResult.Invalid("$key above maximum")
                }
                "boolean" -> if (value != "true" && value != "false") return ValidationResult.Invalid("$key must be boolean")
                else -> return ValidationResult.Invalid("unsupported type for $key: ${spec.type}")
            }
            if (spec.maxLength != null && value.length > spec.maxLength) return ValidationResult.Invalid("$key exceeds length limit")
            if (spec.httpsOnly && !value.startsWith("https://")) return ValidationResult.Invalid("$key must use https")
        }
        return ValidationResult.Valid
    }
    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }
}

object BuiltinTools {
    private val accessibility = setOf(AgentPermission.ACCESSIBILITY_ACTIONS)
    private val search = setOf(AgentPermission.SEARCH_WEB)
    private val memory = setOf(AgentPermission.READ_MEMORY)
    val READ_SCREEN = ToolSchema("read_screen", "Read the current Android screen", category = ToolSchema.Category.AUTOMATION, requiredPermissions = accessibility)
    val OPEN_APP = ToolSchema("open_app", "Launch an installed Android app", mapOf("app_name" to ToolSchema.Param("string", maxLength = 120)), category = ToolSchema.Category.AUTOMATION, requiredPermissions = setOf(AgentPermission.TRIGGER_INTENT))
    val TAP = ToolSchema("tap", "Tap a visible UI element", mapOf("target" to ToolSchema.Param("string", maxLength = 200)), category = ToolSchema.Category.AUTOMATION, requiredPermissions = accessibility)
    val TYPE_TEXT = ToolSchema("type_text", "Type into the focused field", mapOf("text" to ToolSchema.Param("string", maxLength = 4096)), category = ToolSchema.Category.AUTOMATION, requiredPermissions = accessibility)
    val SCROLL_DOWN = ToolSchema("scroll_down", "Scroll down", category = ToolSchema.Category.AUTOMATION, requiredPermissions = accessibility)
    val GO_BACK = ToolSchema("go_back", "Press Android back", category = ToolSchema.Category.AUTOMATION, requiredPermissions = accessibility)
    val WEB_SEARCH = ToolSchema("web_search", "Search the web", mapOf("query" to ToolSchema.Param("string", maxLength = 1000)), category = ToolSchema.Category.SEARCH, requiredPermissions = search)
    val FETCH_URL = ToolSchema("fetch_url", "Fetch verified web content", mapOf("url" to ToolSchema.Param("string", maxLength = 2048, httpsOnly = true)), category = ToolSchema.Category.SEARCH, requiredPermissions = search)
    val MEMORY_RECALL = ToolSchema("memory_recall", "Search scoped personal memory", mapOf("query" to ToolSchema.Param("string", maxLength = 1000)), category = ToolSchema.Category.SEARCH, requiredPermissions = memory)
    val CALENDAR_READ = ToolSchema("calendar_read", "Read upcoming calendar events", mapOf("days" to ToolSchema.Param("int", required = false, min = 0, max = 366)), requiredPermissions = setOf(AgentPermission.READ_CALENDAR))
    val CALENDAR_CREATE = ToolSchema("calendar_create", "Create a calendar event", mapOf("title" to ToolSchema.Param("string", maxLength = 200), "start_time" to ToolSchema.Param("string", maxLength = 80), "duration_min" to ToolSchema.Param("int", required = false, min = 1, max = 1440)), dangerous = true, requiredPermissions = setOf(AgentPermission.WRITE_CALENDAR))
    val SET_ALARM = ToolSchema("set_alarm", "Set an alarm", mapOf("time" to ToolSchema.Param("string", maxLength = 80), "label" to ToolSchema.Param("string", required = false, maxLength = 200)), requiredPermissions = setOf(AgentPermission.SET_ALARM))
    val CREATE_NOTE = ToolSchema("create_note", "Save a note", mapOf("title" to ToolSchema.Param("string", maxLength = 200), "content" to ToolSchema.Param("string", maxLength = 10000)), category = ToolSchema.Category.PRODUCTIVITY, requiredPermissions = setOf(AgentPermission.WRITE_NOTES))
    val ASK_CONFIRMATION = ToolSchema("ask_confirmation", "Request explicit user confirmation", mapOf("action" to ToolSchema.Param("string", maxLength = 500), "details" to ToolSchema.Param("string", required = false, maxLength = 1000)), requiredPermissions = setOf(AgentPermission.REQUEST_CONFIRMATION))
    val ALL = listOf(READ_SCREEN, OPEN_APP, TAP, TYPE_TEXT, SCROLL_DOWN, GO_BACK, WEB_SEARCH, FETCH_URL, MEMORY_RECALL, CALENDAR_READ, CALENDAR_CREATE, SET_ALARM, CREATE_NOTE, ASK_CONFIRMATION)
    val CHAT_ONLY = listOf(WEB_SEARCH, FETCH_URL, MEMORY_RECALL, CALENDAR_READ, CREATE_NOTE)
    val BY_NAME = ALL.associateBy { it.name }
}
