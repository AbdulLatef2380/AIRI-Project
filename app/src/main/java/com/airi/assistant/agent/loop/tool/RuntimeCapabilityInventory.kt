package com.airi.assistant.agent.loop.tool

/**
 * Per-request facts projected from the existing runtime catalog. This is a
 * snapshot, not another registry and not an authorization grant.
 */
data class RuntimeCapabilityInventory(
    val entries: List<Entry>,
) {
    enum class Family { BUILTIN, SKILL, CONNECTOR, OTHER }

    enum class Availability {
        READY,
        NOT_READY,
        AUTH_REQUIRED,
        PERMISSION_REQUIRED,
        CONFIGURATION_REQUIRED,
        DEPENDENCY_MISSING,
        BLOCKED,
        DISCONNECTED,
        ERROR,
        UNSUPPORTED,
        UNKNOWN,
    }

    data class Entry(
        val id: String,
        val family: Family,
        val registered: Boolean,
        val availability: Availability,
        /** Included by request-scoped catalog policy; distinct from model exposure. */
        val inRequestCatalog: Boolean,
        val exposedToAgent: Boolean,
        /** Readiness plus exposure; final runtime/OS/provider gates still run at invocation. */
        val admittedToDispatcher: Boolean,
        val detail: String? = null,
    )

    data class ConnectorReadinessFacts(
        val supported: Boolean = true,
        val registered: Boolean,
        val hasActions: Boolean,
        val hasPermittedActions: Boolean,
        val connected: Boolean,
        val healthy: Boolean,
        val providerAuthorizationRequired: Boolean = false,
        val requiredProviderScopesGranted: Boolean = true,
    )

    fun promptBlock(userInput: String, maxEntries: Int = 16): String {
        if (entries.isEmpty()) return "APPLICATION CAPABILITY INVENTORY: no runtime entries were verified."

        val counts = entries.groupingBy { it.family }.eachCount()
        val ready = entries.count { it.availability == Availability.READY }
        val selected = entries.count { it.inRequestCatalog }
        val exposed = entries.count { it.exposedToAgent }
        val admitted = entries.count { it.admittedToDispatcher }
        val normalizedInput = userInput.lowercase()
        val asksSkills = "skill" in normalizedInput || "مهار" in normalizedInput
        val asksConnectors = "connector" in normalizedInput || "موصل" in normalizedInput ||
            entries.any { entry -> entry.family == Family.CONNECTOR && mentions(entry.id, normalizedInput) }
        val mentioned = entries.filter { entry -> mentions(entry.id, normalizedInput) }
        val familyRequested = entries.filter { entry ->
            (asksSkills && entry.family == Family.SKILL) ||
                (asksConnectors && entry.family == Family.CONNECTOR && entry.registered)
        }
        val important = (mentioned + familyRequested + entries.filter {
            it.family == Family.BUILTIN && it.id in IMPORTANT_BUILTINS
        })
            .distinctBy { it.family to it.id }
            .take(maxEntries.coerceAtLeast(1))

        return buildString {
            appendLine("AIRI APPLICATION CAPABILITY INVENTORY — per-request snapshot from existing runtime registries (not a new permission grant):")
            appendLine("- Registered entries: ${entries.count { it.registered }}; readiness READY: $ready; included by request-catalog policy: $selected; exposed after request-level permission filtering: $exposed; eligible for dispatcher admission before final runtime rechecks: $admitted.")
            appendLine("- Counts by family: built-ins=${counts[Family.BUILTIN] ?: 0}, skills=${counts[Family.SKILL] ?: 0}, connector actions/surfaces=${counts[Family.CONNECTOR] ?: 0}.")
            entries.groupBy { it.family }.forEach { (family, group) ->
                if (family != Family.OTHER && group.isNotEmpty()) {
                    val stateCounts = group.groupingBy { it.availability }.eachCount()
                    appendLine("- ${family.name.lowercase()} readiness counts: " + stateCounts.entries
                        .sortedBy { it.key.name }
                        .joinToString { "${it.key.name.lowercase()}=${it.value}" } + ".")
                }
            }
            appendLine("- 'Not exposed' means only that this request's intent/policy did not include that tool; it does not mean the product feature is absent. 'Exposed/admitted' is not proof that a provider, OS permission, sandbox, or approval will succeed. Never claim execution until the real tool result confirms it.")
            appendLine("- When asked what AIRI supports, answer from this snapshot and distinguish registered, ready, auth/permission/configuration-required, and request-exposed states. Do not turn a missing tool schema into a claim that AIRI has no such feature.")
            if (important.isNotEmpty()) {
                appendLine("- Relevant registered entries (status fields are independent):")
                important.forEach { entry ->
                    append("  • ${entry.id}: registered=${entry.registered}, availability=${entry.availability.name.lowercase()}, in_request_catalog=${entry.inRequestCatalog}, exposed=${entry.exposedToAgent}, dispatcher_admitted=${entry.admittedToDispatcher}")
                    entry.detail?.takeIf(String::isNotBlank)?.let { append("; note=${it.take(MAX_DETAIL_CHARS)}") }
                    appendLine()
                }
            }
        }.trimEnd()
    }

    companion object {
        private const val MAX_DETAIL_CHARS = 180
        private val IMPORTANT_BUILTINS = setOf("terminal_execute", "current_time", "memory_recall", "web_search")
        private val VERIFIED_BUILTINS = setOf("current_time", "ask_confirmation")

        fun connectorAvailability(facts: ConnectorReadinessFacts): Availability = when {
            !facts.supported -> Availability.UNSUPPORTED
            !facts.registered -> Availability.CONFIGURATION_REQUIRED
            !facts.hasActions -> Availability.UNSUPPORTED
            !facts.hasPermittedActions -> Availability.PERMISSION_REQUIRED
            !facts.connected -> if (facts.providerAuthorizationRequired) Availability.AUTH_REQUIRED else Availability.DISCONNECTED
            facts.providerAuthorizationRequired -> Availability.AUTH_REQUIRED
            !facts.healthy -> Availability.ERROR
            !facts.requiredProviderScopesGranted -> Availability.AUTH_REQUIRED
            else -> Availability.READY
        }

        private fun mentions(id: String, input: String): Boolean {
            if (id.isBlank()) return false
            val normalizedId = id.lowercase()
            if (input.contains(normalizedId)) return true
            if (id == "terminal_execute" && "sandbox" in input) return true
            return normalizedId.split(Regex("[^a-z0-9\\u0600-\\u06ff]+")).any { token ->
                token.length >= 4 && input.contains(token)
            }
        }

        fun from(
            catalog: RuntimeToolCatalog.Result,
            dispatcherAdmittedNames: Set<String>,
            additionalEntries: List<Entry> = emptyList(),
        ): RuntimeCapabilityInventory {
            val selected = catalog.exposed.mapTo(mutableSetOf()) { it.schema.name }
            val entries = catalog.candidates.map { contract ->
                val name = contract.schema.name
                val availability = when (contract.source) {
                    // Built-in schemas are declarations. Device permissions, current
                    // session, sandbox, and side-effect policy are only authoritative
                    // at runtime, so schema presence alone cannot certify readiness.
                    RuntimeToolContract.Source.BUILTIN -> when {
                        contract.readiness == RuntimeToolContract.Readiness.BLOCKED -> Availability.BLOCKED
                        contract.readiness == RuntimeToolContract.Readiness.PERMISSION_REQUIRED -> Availability.PERMISSION_REQUIRED
                        contract.readiness == RuntimeToolContract.Readiness.CONFIGURATION_REQUIRED -> Availability.CONFIGURATION_REQUIRED
                        contract.readiness == RuntimeToolContract.Readiness.DEPENDENCY_MISSING -> Availability.DEPENDENCY_MISSING
                        contract.readiness == RuntimeToolContract.Readiness.REQUIRES_AUTH -> Availability.AUTH_REQUIRED
                        contract.readiness == RuntimeToolContract.Readiness.DISCONNECTED -> Availability.DISCONNECTED
                        contract.readiness == RuntimeToolContract.Readiness.ERROR -> Availability.ERROR
                        contract.readiness == RuntimeToolContract.Readiness.UNSUPPORTED -> Availability.UNSUPPORTED
                        contract.readiness == RuntimeToolContract.Readiness.AVAILABLE && name in VERIFIED_BUILTINS -> Availability.READY
                        contract.readiness == RuntimeToolContract.Readiness.AVAILABLE -> Availability.UNKNOWN
                        else -> Availability.NOT_READY
                    }
                    // SkillToolBridge emits enabled skills whose declared connector
                    // dependencies are present; invocation still rechecks permissions.
                    RuntimeToolContract.Source.SKILL -> Availability.READY
                    RuntimeToolContract.Source.CONNECTOR -> when (contract.readiness) {
                        RuntimeToolContract.Readiness.AVAILABLE -> Availability.READY
                        RuntimeToolContract.Readiness.NOT_READY -> Availability.NOT_READY
                        RuntimeToolContract.Readiness.REQUIRES_AUTH -> Availability.AUTH_REQUIRED
                        RuntimeToolContract.Readiness.PERMISSION_REQUIRED -> Availability.PERMISSION_REQUIRED
                        RuntimeToolContract.Readiness.CONFIGURATION_REQUIRED -> Availability.CONFIGURATION_REQUIRED
                        RuntimeToolContract.Readiness.DEPENDENCY_MISSING -> Availability.DEPENDENCY_MISSING
                        RuntimeToolContract.Readiness.DISCONNECTED -> Availability.DISCONNECTED
                        RuntimeToolContract.Readiness.ERROR -> Availability.ERROR
                        RuntimeToolContract.Readiness.UNSUPPORTED -> Availability.UNSUPPORTED
                        RuntimeToolContract.Readiness.BLOCKED -> Availability.BLOCKED
                    }
                }
                Entry(
                    id = name,
                    family = when (contract.source) {
                        RuntimeToolContract.Source.BUILTIN -> Family.BUILTIN
                        RuntimeToolContract.Source.SKILL -> Family.SKILL
                        RuntimeToolContract.Source.CONNECTOR -> Family.CONNECTOR
                    },
                    registered = true,
                    availability = availability,
                    inRequestCatalog = name in selected,
                    exposedToAgent = name in dispatcherAdmittedNames,
                    admittedToDispatcher = name in dispatcherAdmittedNames && availability == Availability.READY,
                    detail = contract.reason ?: if (
                        contract.source == RuntimeToolContract.Source.BUILTIN && availability == Availability.UNKNOWN
                    ) "Registered handler; actual device/session/policy readiness is checked at invocation." else null,
                )
            }
            return RuntimeCapabilityInventory((entries + additionalEntries).distinctBy { it.family to it.id })
        }
    }
}
