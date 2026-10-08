package com.airi.assistant.agent.loop.tool

import com.airi.assistant.security.ExecutionFirewall
import com.airi.assistant.security.ScopedPermissionRegistry

/** Single deny-by-default gate used immediately before every builtin handler. */
class ToolExecutionPipeline(
    private val firewall: ExecutionFirewall,
    private val principal: String = "agent_loop"
) {
    sealed interface Decision {
        data class Allow(val invocation: ValidatedInvocation) : Decision
        data class Deny(val reason: String) : Decision
    }
    data class ValidatedInvocation(val schema: ToolSchema, val args: Map<String, String>)

    fun authorize(schema: ToolSchema, args: Map<String, String>): Decision {
        return try {
            when (val validation = schema.validate(args)) {
                is ToolSchema.ValidationResult.Invalid -> Decision.Deny("invalid arguments: ${validation.reason}")
                ToolSchema.ValidationResult.Valid -> {
                    firewall.guard(principal, schema.name, schema.requiredPermissions)
                    Decision.Allow(ValidatedInvocation(schema, args.toMap()))
                }
            }
        } catch (t: Throwable) {
            // Security and registry failures are always deny; never fail open.
            Decision.Deny("authorization failure: ${t::class.simpleName}")
        }
    }

    companion object {
        fun production(): ToolExecutionPipeline {
            val registry = ScopedPermissionRegistry().also { it.installDefaults() }
            return ToolExecutionPipeline(ExecutionFirewall(registry))
        }
    }
}
