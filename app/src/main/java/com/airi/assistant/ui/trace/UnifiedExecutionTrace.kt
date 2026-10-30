package com.airi.assistant.ui.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.security.CommandRedactor
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.TaskStatus
import com.airi.assistant.ui.activity.sanitizeActivityEventForDisplay

/** The visual family used for grouping and choosing an icon in the timeline. */
enum class UnifiedTraceCategory {
    PLAN, MODEL, FILE, COMMAND, TOOL, SEARCH, MEMORY, CONNECTOR, TASK, SYSTEM
}

data class UnifiedExecutionTraceEvent(
    val executionId: String,
    val eventId: String,
    val actionId: String?,
    val sequence: Long,
    val timestampMs: Long,
    val title: String,
    val status: TaskStatus,
    val detailLog: String?,
    val category: UnifiedTraceCategory = UnifiedTraceCategory.SYSTEM,
    val machineTag: String? = null,
)

/** Immutable execution tree node with a parent event and ordered child steps. */
data class UnifiedExecutionTraceTaskNode(
    val event: UnifiedExecutionTraceEvent,
    val children: List<UnifiedExecutionTraceTaskNode> = emptyList(),
)

data class UnifiedExecutionTraceSummary(
    val filesRead: Int = 0,
    val commandsExecuted: Int = 0,
    val toolsUsed: Int = 0,
    val searches: Int = 0,
    val memoryOperations: Int = 0,
    val tasks: Int = 0,
) {
    val hasContent: Boolean
        get() = filesRead + commandsExecuted + toolsUsed + searches + memoryOperations + tasks > 0
}

data class UnifiedExecutionTraceGroup(
    val id: String,
    val title: String,
    val category: UnifiedTraceCategory,
    val events: List<UnifiedExecutionTraceEvent>,
) {
    val completedCount: Int get() = events.count { it.status == TaskStatus.COMPLETED }
    val failedCount: Int get() = events.count { it.status == TaskStatus.FAILED }
    val runningCount: Int get() = events.count { it.status == TaskStatus.RUNNING }
}

data class UnifiedExecutionTraceProjection(
    val events: List<UnifiedExecutionTraceEvent>,
    val groups: List<UnifiedExecutionTraceGroup>,
    val summary: UnifiedExecutionTraceSummary,
    val taskTree: List<UnifiedExecutionTraceTaskNode> = emptyList(),
)

/**
 * Converts the two compatibility feeds into one execution-owned projection.
 * Tool start/completed/failed events with the same actionId are one row whose
 * state is updated by the terminal event; they are never rendered as separate rows.
 */
internal object UnifiedExecutionTraceProjector {
    fun project(
        executionId: String,
        traceEntries: List<ExecutionTraceEvent>,
        activityEvents: List<ActivityEvent>,
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): List<UnifiedExecutionTraceEvent> = projectProjection(
        executionId,
        traceEntries,
        activityEvents,
        planningTaskLabel,
        executionCompletedLabel,
    ).events

    fun projectProjection(
        executionId: String,
        traceEntries: List<ExecutionTraceEvent>,
        activityEvents: List<ActivityEvent>,
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): UnifiedExecutionTraceProjection {
        if (executionId.isBlank()) return UnifiedExecutionTraceProjection(emptyList(), emptyList(), UnifiedExecutionTraceSummary())

        val owned = traceEntries
            .asSequence()
            .filter { it.executionId == executionId }
            .filter { it.summary.isNotBlank() }
            .sortedBy { it.sequence }
            .toList()
        val structured = collapseStructuredActions(owned, planningTaskLabel, executionCompletedLabel)
        val structuredKeys = structured.mapNotNull { it.dedupeKey }.toMutableSet()
        val projected = structured.map { it.event }.toMutableList()

        activityEvents
            .asSequence()
            .filter { it.executionId == executionId }
            .filterNot { it.machineTag?.startsWith("execution_lifecycle_compat") == true }
            .filterNot { it.machineTag?.startsWith("tool_dispatch_compat") == true && owned.any { trace -> trace.actionId != null } }
            .map(::sanitizeActivityEventForDisplay)
            .sortedWith(compareBy<ActivityEvent> { it.timestampMs }.thenBy { it.sequenceNumber ?: 0L })
            .mapIndexed { index, event -> event.toProjectionCandidate(event.sequenceNumber ?: index.toLong(), planningTaskLabel, executionCompletedLabel) }
            .sortedWith(compareBy<ProjectionCandidate> { it.event.timestampMs }.thenBy { it.event.sequence })
            .forEach { candidate ->
                val duplicate = candidate.dedupeKey != null && candidate.dedupeKey in structuredKeys
                if (!duplicate) {
                    projected += candidate.event
                    candidate.dedupeKey?.let(structuredKeys::add)
                }
            }

        val events = projected.sortedWith(compareBy<UnifiedExecutionTraceEvent> { it.timestampMs }.thenBy { it.sequence })
        val groups = events.groupBy { it.category }.map { (category, groupEvents) ->
            UnifiedExecutionTraceGroup(
                id = category.name,
                title = categoryTitle(category),
                category = category,
                events = groupEvents,
            )
        }.sortedBy { groupOrder(it.category) }
        return UnifiedExecutionTraceProjection(events, groups, summarize(events), buildTaskTree(events))
    }

    /** Attach operational events to the nearest active plan or step. */
    private fun buildTaskTree(events: List<UnifiedExecutionTraceEvent>): List<UnifiedExecutionTraceTaskNode> {
        if (events.isEmpty()) return emptyList()
        data class MutableNode(
            val event: UnifiedExecutionTraceEvent,
            val children: MutableList<MutableNode> = mutableListOf(),
        )
        val roots = mutableListOf<MutableNode>()
        var activePlan: MutableNode? = null
        var activeStep: MutableNode? = null
        events.forEach { event ->
            val node = MutableNode(event)
            val parent = when {
                event.category == UnifiedTraceCategory.PLAN -> null
                event.category == UnifiedTraceCategory.TASK -> activePlan
                else -> activeStep ?: activePlan
            }
            if (parent == null) roots += node else parent.children += node
            when (event.category) {
                UnifiedTraceCategory.PLAN -> {
                    activePlan = node
                    activeStep = null
                }
                UnifiedTraceCategory.TASK -> activeStep = node
                else -> Unit
            }
        }
        fun freeze(node: MutableNode): UnifiedExecutionTraceTaskNode =
            UnifiedExecutionTraceTaskNode(node.event, node.children.map(::freeze))
        return roots.map(::freeze)
    }

    private fun collapseStructuredActions(
        entries: List<ExecutionTraceEvent>,
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): List<ProjectionCandidate> {
        val result = mutableListOf<ProjectionCandidate>()
        val consumed = mutableSetOf<ExecutionTraceEvent>()
        entries.filter { it.actionId != null }
            .groupBy { ActionGroupKey(it.actionId!!, it.kind.actionFamily()) }
            .forEach { (key, actionEntries) ->
            val first = actionEntries.first()
            val terminal = actionEntries.lastOrNull { it.kind in key.family.terminalKinds }
            val source = terminal ?: first
            val displayTimestamp = if (first.kind == ExecutionTraceKind.STEP_STARTED || first.kind == ExecutionTraceKind.TOOL_STARTED) {
                first.timestampMs
            } else {
                source.timestampMs
            }
            val title = if (key.family == ActionFamily.STEP) "Agent step" else toolTitle(first.summary, source.summary)
            val details = buildList {
                if (key.family == ActionFamily.STEP) {
                    first.detail?.takeIf(String::isNotBlank)?.let { add("Action: $it") }
                } else {
                    source.detail?.takeIf(String::isNotBlank)?.let(::add)
                }
                source.errorCode?.let { add("Error code: ${normalizeErrorCode(it)}") }
                source.durationMs?.let { add("${it.coerceAtLeast(0L)} ms") }
            }.joinToString("\n").takeIf(String::isNotBlank)
            result += ProjectionCandidate(
                event = UnifiedExecutionTraceEvent(
                    executionId = first.executionId,
                    eventId = first.eventId,
                    actionId = key.actionId,
                    sequence = first.sequence,
                    timestampMs = displayTimestamp,
                    title = safeTraceText(localizeDynamicTitle(if (key.family == ActionFamily.STEP) "agent_step" else source.machineTag, title)),
                    status = source.kind.toTaskStatus(),
                    detailLog = details?.let(::safeTraceText),
                    category = if (key.family == ActionFamily.STEP) UnifiedTraceCategory.TASK else categoryFor(title, first.kind),
                    machineTag = if (key.family == ActionFamily.STEP) "agent_step" else source.machineTag,
                ),
                dedupeKey = "action:${key.family.name.lowercase()}:${key.actionId}",
                sourcePriority = 0,
            )
            consumed += actionEntries
        }
        entries.filterNot { it in consumed }.forEach { entry -> result += entry.toProjectionCandidate(planningTaskLabel, executionCompletedLabel) }
        return result
    }

    private data class ActionGroupKey(val actionId: String, val family: ActionFamily)

    private enum class ActionFamily(val terminalKinds: Set<ExecutionTraceKind>) {
        STEP(setOf(ExecutionTraceKind.STEP_COMPLETED)),
        TOOL(setOf(
            ExecutionTraceKind.TOOL_COMPLETED,
            ExecutionTraceKind.TOOL_FAILED,
            ExecutionTraceKind.TOOL_CANCELLED,
        )),
        OTHER(emptySet()),
    }

    private fun ExecutionTraceKind.actionFamily(): ActionFamily = when (this) {
        ExecutionTraceKind.STEP_STARTED, ExecutionTraceKind.STEP_COMPLETED -> ActionFamily.STEP
        ExecutionTraceKind.TOOL_STARTED, ExecutionTraceKind.TOOL_COMPLETED,
        ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.TOOL_CANCELLED -> ActionFamily.TOOL
        else -> ActionFamily.OTHER
    }

    private data class ProjectionCandidate(
        val event: UnifiedExecutionTraceEvent,
        val dedupeKey: String?,
        val sourcePriority: Int,
    )

    private fun ExecutionTraceEvent.toProjectionCandidate(
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): ProjectionCandidate = ProjectionCandidate(
        event = UnifiedExecutionTraceEvent(
            executionId = executionId,
            eventId = eventId,
            actionId = actionId,
            sequence = sequence,
            timestampMs = timestampMs,
            title = localizeDynamicTitle(machineTag, localizeLifecycleSummary(summary, planningTaskLabel, executionCompletedLabel)),
            status = kind.toTaskStatus(),
            detailLog = buildList {
                detail?.takeIf(String::isNotBlank)?.let { add(safeTraceText(it)) }
                errorCode?.let { add("Error code: ${normalizeErrorCode(it)}") }
                durationMs?.let { add("Duration: ${it.coerceAtLeast(0L)} ms") }
            }.joinToString("\n").takeIf(String::isNotBlank),
            category = categoryFor(summary, kind),
            machineTag = machineTag,
        ),
        dedupeKey = lifecycleDedupeKey(kind, summary),
        sourcePriority = 0,
    )

    private fun ActivityEvent.toProjectionCandidate(
        sequence: Long,
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): ProjectionCandidate {
        val title = localizeDynamicTitle(machineTag, localizeLifecycleSummary(message, planningTaskLabel, executionCompletedLabel))
        val category = categoryForActivity(category, title)
        return ProjectionCandidate(
            event = UnifiedExecutionTraceEvent(
                executionId = executionId.orEmpty(),
                eventId = id,
                actionId = if (category == UnifiedTraceCategory.TOOL || category == UnifiedTraceCategory.CONNECTOR) "activity:$id" else null,
                sequence = sequence,
                timestampMs = timestampMs,
                title = title,
                status = when (severity) {
                    ActivitySeverity.INFO -> if (machineTag?.contains("start", true) == true) TaskStatus.RUNNING else TaskStatus.COMPLETED
                    ActivitySeverity.WARN -> TaskStatus.RUNNING
                    ActivitySeverity.ERROR -> TaskStatus.FAILED
                },
                detailLog = detail?.takeIf(String::isNotBlank),
                category = category,
                machineTag = machineTag,
            ),
            dedupeKey = lifecycleDedupeKey(null, message),
            sourcePriority = 1,
        )
    }

    private fun summarize(events: List<UnifiedExecutionTraceEvent>): UnifiedExecutionTraceSummary {
        fun count(category: UnifiedTraceCategory) = events.count { it.category == category }
        return UnifiedExecutionTraceSummary(
            filesRead = count(UnifiedTraceCategory.FILE),
            commandsExecuted = count(UnifiedTraceCategory.COMMAND),
            toolsUsed = count(UnifiedTraceCategory.TOOL) + count(UnifiedTraceCategory.CONNECTOR),
            searches = count(UnifiedTraceCategory.SEARCH),
            memoryOperations = count(UnifiedTraceCategory.MEMORY),
            tasks = count(UnifiedTraceCategory.TASK) + count(UnifiedTraceCategory.PLAN),
        )
    }

    private fun categoryForActivity(activityCategory: ActivityCategory, value: String): UnifiedTraceCategory {
        val text = value.lowercase()
        return when {
            listOf("file", "read ", "written", "document", ".kt", ".md", ".txt", "ملف", "قراءة").any(text::contains) -> UnifiedTraceCategory.FILE
            listOf("command", "terminal", "shell", "compile", "gradle", "git ", "أمر", "طرفية").any(text::contains) -> UnifiedTraceCategory.COMMAND
            listOf("search", "بحث", "web", "url", "research").any(text::contains) -> UnifiedTraceCategory.SEARCH
            activityCategory == ActivityCategory.MODEL -> UnifiedTraceCategory.MODEL
            activityCategory == ActivityCategory.TOOL -> UnifiedTraceCategory.TOOL
            activityCategory == ActivityCategory.CONNECTOR -> UnifiedTraceCategory.CONNECTOR
            activityCategory == ActivityCategory.MEMORY -> UnifiedTraceCategory.MEMORY
            activityCategory == ActivityCategory.ROUTING -> UnifiedTraceCategory.PLAN
            activityCategory == ActivityCategory.REASONING || activityCategory == ActivityCategory.ORCHESTRATION -> UnifiedTraceCategory.TASK
            else -> UnifiedTraceCategory.SYSTEM
        }
    }

    private fun categoryFor(value: String, kind: ExecutionTraceKind): UnifiedTraceCategory {
        val text = value.lowercase()
        return when {
            listOf("file", "read ", "written", "document", ".kt", ".md", ".txt").any(text::contains) -> UnifiedTraceCategory.FILE
            listOf("command", "terminal", "shell", "compile", "gradle", "git ").any(text::contains) -> UnifiedTraceCategory.COMMAND
            listOf("search", "بحث", "web", "url", "research").any(text::contains) -> UnifiedTraceCategory.SEARCH
            kind in setOf(ExecutionTraceKind.TOOL_STARTED, ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.TOOL_CANCELLED) -> UnifiedTraceCategory.TOOL
            kind == ExecutionTraceKind.PLANNING || kind == ExecutionTraceKind.RECOVERING || kind == ExecutionTraceKind.REFLECTING -> UnifiedTraceCategory.PLAN
            kind == ExecutionTraceKind.STEP_STARTED || kind == ExecutionTraceKind.STEP_COMPLETED -> UnifiedTraceCategory.TASK
            else -> UnifiedTraceCategory.SYSTEM
        }
    }

    private fun categoryTitle(category: UnifiedTraceCategory): String = when (category) {
        UnifiedTraceCategory.MODEL -> "Model"
        UnifiedTraceCategory.FILE -> "Files"
        UnifiedTraceCategory.COMMAND -> "Commands"
        UnifiedTraceCategory.TOOL -> "Tools"
        UnifiedTraceCategory.SEARCH -> "Search"
        UnifiedTraceCategory.MEMORY -> "Memory"
        UnifiedTraceCategory.CONNECTOR -> "Connections"
        UnifiedTraceCategory.PLAN -> "Plan"
        UnifiedTraceCategory.TASK -> "Tasks"
        UnifiedTraceCategory.SYSTEM -> "Activity"
    }

    private fun groupOrder(category: UnifiedTraceCategory): Int = when (category) {
        UnifiedTraceCategory.PLAN -> 0
        UnifiedTraceCategory.TASK -> 1
        UnifiedTraceCategory.MODEL -> 2
        UnifiedTraceCategory.FILE -> 3
        UnifiedTraceCategory.COMMAND -> 4
        UnifiedTraceCategory.TOOL, UnifiedTraceCategory.CONNECTOR -> 5
        UnifiedTraceCategory.SEARCH, UnifiedTraceCategory.MEMORY -> 6
        UnifiedTraceCategory.SYSTEM -> 7
    }

    private fun toolTitle(first: String, terminal: String): String {
        val source = if (first.contains(": ")) first.substringAfter(": ") else first
        return source.ifBlank { terminal }
    }

    private fun lifecycleDedupeKey(kind: ExecutionTraceKind?, summary: String): String? {
        val normalized = summary.trim().lowercase()
        return when {
            kind == ExecutionTraceKind.PLANNING || normalized == "planning task" || normalized.startsWith("planning:") -> "lifecycle:planning"
            kind == ExecutionTraceKind.COMPLETED || normalized == "execution completed" -> "lifecycle:completed"
            kind == ExecutionTraceKind.FAILED || normalized.startsWith("execution failed") -> "lifecycle:failed"
            kind == ExecutionTraceKind.CANCELLED || normalized.startsWith("execution cancelled") -> "lifecycle:cancelled"
            else -> null
        }
    }

    private fun ExecutionTraceKind.toTaskStatus(): TaskStatus = when (this) {
        ExecutionTraceKind.PLANNING, ExecutionTraceKind.STEP_STARTED, ExecutionTraceKind.TOOL_STARTED,
        ExecutionTraceKind.RECOVERING, ExecutionTraceKind.REFLECTING -> TaskStatus.RUNNING
        ExecutionTraceKind.STEP_COMPLETED, ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.COMPLETED -> TaskStatus.COMPLETED
        ExecutionTraceKind.TOOL_FAILED, ExecutionTraceKind.FAILED -> TaskStatus.FAILED
        ExecutionTraceKind.TOOL_CANCELLED, ExecutionTraceKind.CANCELLED -> TaskStatus.CANCELLED
    }

    private fun localizeLifecycleSummary(value: String, planningTaskLabel: String, executionCompletedLabel: String): String = when (value.trim()) {
        "Planning task" -> planningTaskLabel
        "Execution completed" -> executionCompletedLabel
        "Execution failed" -> "Execution failed"
        "Execution cancelled" -> "Execution cancelled"
        else -> safeTraceText(value)
    }

    private fun localizeDynamicTitle(machineTag: String?, fallback: String): String {
        val tag = machineTag?.substringBefore(':')?.trim()?.lowercase().orEmpty()
        val arabic = java.util.Locale.getDefault().language == "ar"
        val localized = if (arabic) {
            mapOf(
                "file_created" to "إنشاء ملف",
                "file_read" to "قراءة ملف",
                "command_executed" to "تنفيذ أمر",
                "tool_call" to "استدعاء أداة",
                "search_started" to "بدء البحث",
                "search_completed" to "اكتمال البحث",
                "memory_read" to "قراءة الذاكرة",
                "memory_write" to "تحديث الذاكرة",
                "planning" to "التخطيط للمهمة",
                "execution_completed" to "اكتمل التنفيذ",
                "agent_step" to "خطوة الوكيل",
                "runtime_request_received" to "استلام الطلب",
                "runtime_model_context_resolved" to "تجهيز سياق النموذج",
                "runtime_capability_filtered" to "تصفية القدرات والأدوات",
                "runtime_tools_exposed" to "إتاحة الأدوات للنموذج",
                "runtime_model_response_received" to "استلام رد النموذج",
                "runtime_tool_rejected" to "رفض تنفيذ الأداة",
                "runtime_execution_path_resolved" to "تحديد مسار التنفيذ",
                "runtime_connector_failed" to "فشل تنفيذ الموصل",
                "runtime_result_returned" to "عودة نتيجة التنفيذ",
                "runtime_model_continuation_completed" to "اكتمال متابعة النموذج",
                "runtime_intent_stage" to "مرحلة فهم الطلب",
            )[tag]
        } else {
            mapOf(
                "file_created" to "Create file",
                "file_read" to "Read file",
                "command_executed" to "Run command",
                "tool_call" to "Invoke tool",
                "search_started" to "Start search",
                "search_completed" to "Search completed",
                "memory_read" to "Read memory",
                "memory_write" to "Update memory",
                "planning" to "Plan task",
                "execution_completed" to "Execution completed",
                "agent_step" to "Agent step",
                "runtime_request_received" to "Request received",
                "runtime_model_context_resolved" to "Resolve model context",
                "runtime_capability_filtered" to "Filter capabilities",
                "runtime_tools_exposed" to "Expose tools to model",
                "runtime_model_response_received" to "Receive model response",
                "runtime_tool_rejected" to "Tool dispatch rejected",
                "runtime_execution_path_resolved" to "Resolve execution path",
                "runtime_connector_failed" to "Connector execution failed",
                "runtime_result_returned" to "Return execution result",
                "runtime_model_continuation_completed" to "Model continuation completed",
                "runtime_intent_stage" to "Request intent stage",
            )[tag]
        }
        return localized ?: fallback
    }

    private fun safeTraceText(value: String): String = PrivacyGuard.redactForTrace(CommandRedactor.redact(value), maximumChars = 1_000)

    private fun normalizeErrorCode(value: String): String = value.trim().lowercase().take(64)

}
