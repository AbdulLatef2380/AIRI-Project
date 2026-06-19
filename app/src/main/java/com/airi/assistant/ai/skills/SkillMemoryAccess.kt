package com.airi.assistant.ai.skills

/**
 * Memory access permission levels for skills.
 *
 * Controls what a skill is allowed to read from or write to the
 * [com.airi.assistant.memory.repository.MemoryManager].
 */
enum class SkillMemoryAccess(val label: String, val canRead: Boolean, val canWrite: Boolean) {
    NONE       ("None",            false, false),
    READ_ONLY  ("Read Only",       true,  false),
    READ_WRITE ("Read / Write",    true,  true),
    FULL_ACCESS("Full Access",     true,  true)
}

/**
 * Model-invocation permission levels for skills.
 *
 * Controls whether a skill may call the active LLM and, if so, whether
 * it may select a specific provider or only use the session default.
 */
enum class SkillModelAccess(val label: String) {
    NONE             ("None — skill does not call the model"),
    CHAT             ("Chat — can invoke the session model for text generation"),
    CHAT_WITH_ROUTING("Chat + Routing — can request a specific model provider")
}
