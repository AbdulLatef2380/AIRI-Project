package com.airi.assistant.ai.skills.impl

import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillRiskLevel
import com.airi.assistant.ai.skills.SkillToolDefinition

/**
 * Evidence-first engineering review executor. It does not claim to profile or
 * run code; it analyzes supplied source, traces, metrics, or benchmark output.
 */
class EngineeringQualitySkill(private val spec: Spec) : AiriSkill {
    override val skillId = spec.id
    override val name = spec.name
    override val displayName = spec.name
    override val description = spec.description
    override val version = "1.0.0"
    override val author = "AIRI Official"
    override val category = spec.category
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.NONE
    override val modelAccess = SkillModelAccess.CHAT
    override val parameters = mapOf("input" to "string")
    override val inputSchema = parameters
    override val outputSchema = mapOf("findings" to "string", "verification_tests" to "string")
    override val instructions = spec.stages.joinToString(" ") { it.instruction }
    override val examples = spec.examples
    override val limitations = spec.limitations
    override val riskLevel = SkillRiskLevel.LOW
    override val supportsStreaming = false
    override val supportsAttachments = false
    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name = spec.id,
            description = spec.description,
            parameters = mapOf("input" to SkillParamDef("string", "Code, metrics, or design input", required = true))
        )
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        val hits = spec.keywords.count { lower.contains(it) }
        return (hits * 28 + if (context.lastUsedSkill == skillId) 10 else 0).coerceIn(0, 100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val startedAt = System.currentTimeMillis()
        val context = params["context"] as? SkillContext
            ?: return failure("Skill context is required for engineering analysis.", startedAt)
        val input = params["input"] as? String
            ?: return failure("No code, metrics, or design input was provided.", startedAt)
        if (input.isBlank()) return failure("Input cannot be blank.", startedAt)
        val bridge = context.modelBridge
            ?: return failure("An active AI model is required for engineering analysis.", startedAt)

        var evidence = input.trim()
        return try {
            spec.stages.forEachIndexed { index, stage ->
                val prompt = buildString {
                    append(stage.instruction)
                    append("\n\nEvidence supplied by the user:\n")
                    append(evidence)
                    append("\n\nRequired output:\n")
                    append(stage.outputContract)
                    append("\nNever invent a benchmark, trace, file location, or measured value.")
                    if (index == spec.stages.lastIndex) append("\nReturn the final engineering report only.")
                }
                val result = try {
                    bridge.complete(prompt, spec.systemPrompt, spec.maxTokens)
                } catch (error: Exception) {
                    throw IllegalStateException(
                        "Analysis stage ${index + 1} failed: ${error.message ?: "unknown model error"}",
                        error
                    )
                }
                if (result.isBlank()) error("Analysis stage ${index + 1} returned an empty result")
                evidence = result.trim()
            }
            SkillResult(
                success = true,
                data = evidence,
                skillName = skillId,
                executionMs = System.currentTimeMillis() - startedAt,
                metadata = mapOf(
                    "analysis_focus" to spec.focus,
                    "model_execution" to "active_bridge",
                    "stages" to spec.stages.size.toString()
                )
            )
        } catch (error: Exception) {
            failure("Engineering analysis failed: ${error.message ?: "unknown error"}", startedAt)
        }
    }

    private fun failure(message: String, startedAt: Long) = SkillResult(
        success = false,
        data = "",
        error = message,
        skillName = skillId,
        executionMs = System.currentTimeMillis() - startedAt
    )

    data class Stage(val instruction: String, val outputContract: String)

    data class Spec(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val focus: String,
        val keywords: List<String>,
        val stages: List<Stage>,
        val systemPrompt: String,
        val examples: List<String>,
        val limitations: List<String>,
        val maxTokens: Int = 1200
    )

    companion object {
        private fun stages(scope: String, finding: String, remediation: String) = listOf(
            Stage("Inventory the supplied $scope and identify observable signals, missing evidence, and assumptions.", "A compact inventory that separates observed evidence from hypotheses."),
            Stage("Analyze the inventory for $finding. Rank findings by impact, confidence, and reproducibility.", "A finding table with severity, evidence quote, confidence, and affected area."),
            Stage("Turn the findings into $remediation. Prefer the smallest safe change and define a verification test or measurement.", "A prioritized action plan with concrete fix guidance, expected effect, risk, and a verification test.")
        )

        val SPECS = listOf(
            Spec("performance_profiler", "Performance Profiler", "Identify bottlenecks from code, traces, or benchmark output", "PERFORMANCE", "runtime bottlenecks", listOf("performance", "slow", "latency", "benchmark", "أداء", "بطء", "زمن الاستجابة"), stages("performance evidence", "hot paths, unnecessary work, blocking calls, and allocation pressure", "performance improvements with before/after measurements"), "You are a performance engineer. Distinguish measured data from static suspicion.", listOf("Analyze this slow request trace"), listOf("Does not run profilers or generate measurements.")),
            Spec("memory_leak_auditor", "Memory Leak Auditor", "Find retention risks and memory pressure in application code", "PERFORMANCE", "memory retention", listOf("memory leak", "leak", "heap", "allocation", "تسريب الذاكرة", "الذاكرة"), stages("lifecycle and memory evidence", "retained references, listener leaks, unbounded caches, and oversized allocations", "leak fixes and a repeatable heap or lifecycle verification"), "You are an Android memory specialist. Do not claim a leak without a retention path or measurement.", listOf("Audit this ViewModel for memory leaks"), listOf("Static analysis cannot replace a heap dump.")),
            Spec("startup_latency_auditor", "Startup Latency Auditor", "Reduce cold and warm startup work safely", "PERFORMANCE", "startup latency", listOf("startup", "cold start", "launch", "initialization", "بدء التشغيل", "إقلاع"), stages("startup path", "eager initialization, main-thread I/O, blocking dependency setup, and unnecessary first-frame work", "lazy or deferred initialization with startup timing checkpoints"), "You are an Android startup performance specialist. Preserve correctness and first-frame behavior.", listOf("Review this application startup path"), listOf("Requires startup traces to confirm timing impact.")),
            Spec("compose_recomposition_auditor", "Compose Recomposition Auditor", "Identify avoidable recompositions and unstable UI state", "PERFORMANCE", "Compose recomposition", listOf("compose", "recomposition", "remember", "unstable", "Jetpack Compose", "إعادة التركيب"), stages("Compose state and parameter flow", "unstable parameters, broad state reads, missing remember keys, and expensive work in composition", "stable state boundaries and a recomposition measurement plan"), "You are a Jetpack Compose performance specialist. Never recommend removing state correctness for speed.", listOf("Audit this composable for recomposition"), listOf("Needs Layout Inspector or runtime counters for confirmation.")),
            Spec("concurrency_auditor", "Concurrency Auditor", "Find blocking, cancellation, race, and dispatcher mistakes", "QUALITY", "concurrency correctness", listOf("coroutine", "thread", "race", "blocking", "cancellation", "تزامن", "سباق"), stages("async control flow", "blocking calls, lost cancellation, shared mutable state, lifecycle races, and dispatcher misuse", "structured concurrency fixes and deterministic race or cancellation tests"), "You are a concurrency reviewer. Make lifecycle ownership and cancellation explicit.", listOf("Review this coroutine flow"), listOf("Static review cannot prove absence of all races.")),
            Spec("complexity_reducer", "Complexity Reducer", "Reduce complexity while preserving behavior and testability", "QUALITY", "maintainability complexity", listOf("complexity", "refactor", "maintainability", "duplicate", "تعقيد", "إعادة هيكلة"), stages("code structure and behavior", "duplication, long methods, hidden coupling, branching complexity, and unclear contracts", "a behavior-preserving refactor sequence with characterization tests"), "You are a conservative refactoring specialist. Do not recommend a rewrite without migration evidence.", listOf("Find the safest refactoring path"), listOf("Requires tests or characterization fixtures to preserve behavior.")),
            Spec("api_quality_reviewer", "API Quality Reviewer", "Review API contracts for clarity, compatibility, and failure behavior", "QUALITY", "API contract quality", listOf("api", "contract", "compatibility", "pagination", "error handling", "واجهة برمجة", "عقد"), stages("API surface and callers", "ambiguous contracts, breaking changes, validation gaps, pagination, and error semantics", "a version-safe contract revision with contract tests"), "You are an API design reviewer. Prefer explicit, backward-compatible contracts.", listOf("Review this REST API contract"), listOf("Does not inspect live traffic unless supplied.")),
            Spec("refactoring_planner", "Refactoring Planner", "Create an incremental quality-improvement plan from a codebase hotspot", "QUALITY", "safe refactoring plan", listOf("refactoring plan", "technical debt", "code quality", "خطة إعادة هيكلة", "دين تقني"), stages("hotspot and constraints", "risk, dependencies, test gaps, and rollback boundaries", "a sequenced refactor plan with small commits, gates, and rollback points"), "You are a staff engineer planning incremental change. Keep each step reviewable and reversible.", listOf("Plan this refactor without breaking behavior"), listOf("The plan depends on repository tests and ownership context."))
        )
    }
}
