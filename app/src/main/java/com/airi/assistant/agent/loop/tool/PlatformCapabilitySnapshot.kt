package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.context.ContextBudget

/**
 * Request-scoped, read-only view of AIRI's currently registered capabilities.
 *
 * This is deliberately derived from existing registries/catalogs. It is not a
 * second permission registry and it never contains tokens, credentials, URIs,
 * file paths, or provider payloads.
 */
data class PlatformCapabilitySnapshot(
    val entries: List<Entry>,
    val activeModelId: String = "",
    val activeProviderId: String = "",
    val contextBudget: ContextBudget = ContextBudget.UNLOADED,
) {
    enum class Family { BUILTIN, SKILL, CONNECTOR, AGENT, MEMORY, RUNTIME, MODEL }

    data class Entry(
        val id: String,
        val family: Family,
        val status: String,
        val registered: Boolean,
        val exposed: Boolean,
        val admitted: Boolean,
        val detail: String? = null,
    )

    fun promptBlock(maxEntries: Int = 64): String = buildString {
        appendLine("AIRI PLATFORM CAPABILITY SNAPSHOT — request-scoped, read-only facts:")
        appendLine("This snapshot describes registration, readiness, exposure, and dispatcher admission. It grants no permission and contains no secrets.")
        appendLine("State rules: absent=not present in the verified inventory; registered=false=known surface is not registered; auth/permission/configuration status=registered but not ready; exposed=false=not offered to this request; context_omitted=selected candidate removed only because of prompt context budget.")
        appendLine("Active model/provider: ${safe(activeModelId).ifBlank { "unknown" }}/${safe(activeProviderId).ifBlank { "default" }}; context_nctx=${contextBudget.nCtx}; context_available_tokens=${contextBudget.availableForContent}.")
        val counts = entries.groupingBy { it.family }.eachCount()
        appendLine("Counts: " + Family.values().filter { counts[it] != null }.joinToString { "${it.name.lowercase()}=${counts[it]}" } + ".")
        entries.take(maxEntries.coerceAtLeast(1)).forEach { entry ->
            append("- ${safe(entry.id)} [${entry.family.name.lowercase()}]: status=${safe(entry.status)}; registered=${entry.registered}; exposed=${entry.exposed}; dispatcher_admitted=${entry.admitted}")
            entry.detail?.takeIf { it.isNotBlank() }?.let { append("; note=${safe(it.take(MAX_DETAIL_CHARS))}") }
            appendLine()
        }
        if (entries.size > maxEntries) appendLine("- ${entries.size - maxEntries} additional registered entries omitted from this prompt snapshot due to a bounded context budget; omission does not mean unsupported.")
        append("Use these facts to distinguish absent, registered-but-not-ready, authorized, exposed, and context-omitted capabilities. Never claim execution until the tool result confirms it.")
    }.trimEnd()

    fun traceEntries(): List<com.airi.assistant.core.CapabilitySnapshotEntry> = entries.map {
        com.airi.assistant.core.CapabilitySnapshotEntry(
            id = it.id,
            kind = it.family.name.lowercase(),
            exists = it.registered,
            registered = it.registered,
            connected = null,
            authenticated = null,
            healthy = null,
            permitted = null,
            executable = it.admitted,
            modelCompatible = it.exposed,
            exposed = it.exposed,
            resultReturned = null,
        )
    }

    /**
     * Reconciles the snapshot with the final AgentLoop exposure decision.
     *
     * ChatViewModel builds the broad registry snapshot before AgentLoop applies
     * its runtime admission and prompt-budget gates. This compact block is
     * therefore appended at the final boundary so the model cannot mistake a
     * registered-but-filtered tool for a missing capability.
     */
    fun turnExposureBlock(
        candidateToolNames: Collection<String>,
        permittedToolNames: Collection<String>,
        exposedToolNames: Collection<String>,
        omittedToolNames: Collection<String>,
        maxEntries: Int = MAX_TURN_ENTRIES,
    ): String {
        val permitted = permittedToolNames.toSet()
        val exposed = exposedToolNames.toSet()
        val omitted = omittedToolNames.toSet()
        val candidates = candidateToolNames.distinct().take(maxEntries.coerceAtLeast(1))
        return buildString {
            appendLine("AIRI TURN TOOL EXPOSURE SNAPSHOT — final request-scoped facts:")
            appendLine("This block reflects the AgentLoop admission and context-budget result for this turn. It grants no permission and contains no secrets.")
            if (candidates.isEmpty()) {
                appendLine("- No registered tool candidates reached this turn's exposure pipeline.")
            } else {
                candidates.forEach { name ->
                    val isPermitted = name in permitted
                    val isExposed = name in exposed
                    val isOmitted = name in omitted
                    val status = when {
                        isOmitted -> "context_omitted"
                        !isPermitted -> "blocked_by_policy"
                        isExposed -> "ready"
                        else -> "not_exposed"
                    }
                    val reason = when {
                        isOmitted -> "selected candidate removed by prompt context budget"
                        !isPermitted -> "removed by request permission or runtime admission policy"
                        isExposed -> "offered to the model and admitted to the dispatcher"
                        else -> "not selected for this turn"
                    }
                    val nextAction = when {
                        isOmitted -> "shorten_request_or_use_larger_context"
                        !isPermitted -> "review_readiness_or_approval"
                        else -> "none"
                    }
                    appendLine(
                        "- ${safe(name)}: registered=true; ready=$isPermitted; exposed=$isExposed; " +
                            "dispatcher_admitted=$isExposed; status=$status; " +
                            "blocked_reason=${safe(reason)}; next_action=$nextAction",
                    )
                }
            }
            if (candidateToolNames.distinct().size > candidates.size) {
                appendLine("- Additional turn candidates omitted from this bounded diagnostic block; omission here is not absence from the runtime registry.")
            }
            append("Never claim a tool ran until its dispatcher result and verification evidence are returned.")
        }.trimEnd()
    }

    companion object {
        private const val MAX_DETAIL_CHARS = 180
        private const val MAX_TURN_ENTRIES = 96

        fun from(
            inventory: RuntimeCapabilityInventory,
            activeModelId: String = "",
            activeProviderId: String = "",
            contextBudget: ContextBudget = ContextBudget.UNLOADED,
            agentEntries: List<Entry> = emptyList(),
            memoryEntries: List<Entry> = emptyList(),
        ): PlatformCapabilitySnapshot {
            val projected = inventory.entries.map {
                Entry(
                    id = it.id,
                    family = when (it.family) {
                        RuntimeCapabilityInventory.Family.BUILTIN -> Family.BUILTIN
                        RuntimeCapabilityInventory.Family.SKILL -> Family.SKILL
                        RuntimeCapabilityInventory.Family.CONNECTOR -> Family.CONNECTOR
                        RuntimeCapabilityInventory.Family.OTHER -> Family.RUNTIME
                    },
                    status = it.availability.name.lowercase(),
                    registered = it.registered,
                    exposed = it.exposedToAgent,
                    admitted = it.admittedToDispatcher,
                    detail = it.detail,
                )
            }
            return PlatformCapabilitySnapshot(
                entries = (projected + agentEntries + memoryEntries).distinctBy { it.family to it.id },
                activeModelId = activeModelId,
                activeProviderId = activeProviderId,
                contextBudget = contextBudget,
            )
        }

        private fun safe(value: String): String = value
            .replace(Regex("[\\r\\n\\t]"), " ")
            .replace(Regex("(?i)(token|secret|password|api[_ -]?key|authorization)\\s*[:=]\\s*\\S+"), "$1=[redacted]")
            .take(240)
    }
}
