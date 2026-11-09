package com.airi.assistant.agent.request

/**
 * How a model can be asked to call tools (closure plan, phase 1.5).
 *
 *  - NATIVE: the provider API carries tool declarations and returns structured calls.
 *  - TEXT_PROTOCOL: the model is asked to print a `tool_call` block that AIRI parses; less reliable,
 *    so the action-completion guard must back it up.
 *  - NONE: the model must not be offered tools; a request that needs one must say so openly
 *    instead of getting a silent text reply.
 *
 * Pure Kotlin. Runtime facts (a local model's measured ability, an OpenRouter catalog entry) are
 * passed in by the caller; this class only decides.
 */
enum class ModelToolSupport {
    NATIVE, TEXT_PROTOCOL, NONE;

    val canCallTools: Boolean get() = this != NONE

    companion object {
        private val NATIVE_CLOUD = setOf("openai", "anthropic", "gemini")

        /**
         * @param providerId stable provider id ("openai", "anthropic", "gemini", "openrouter", "local").
         * @param declaresTools OpenRouter: the catalog's `supported_parameters` lists `tools`
         *   (true), does not (false), or the catalog is not loaded (null). Ignored elsewhere.
         * @param localToolsVerified local models: tool calling was tested on this model and
         *   context size. A small model is never assumed to support it.
         */
        fun resolve(
            providerId: String,
            declaresTools: Boolean? = null,
            localToolsVerified: Boolean = false,
        ): ModelToolSupport {
            val id = providerId.trim().lowercase()
            return when {
                id in NATIVE_CLOUD -> NATIVE
                id == "openrouter" -> when (declaresTools) {
                    true -> NATIVE
                    false -> NONE
                    null -> TEXT_PROTOCOL
                }
                id == "local" -> if (localToolsVerified) TEXT_PROTOCOL else NONE
                else -> TEXT_PROTOCOL
            }
        }
    }
}
