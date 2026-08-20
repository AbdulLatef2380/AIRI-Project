package com.airi.assistant.ai.agent

import android.util.Log
import com.airi.assistant.agent.planning.GoalNode
import com.airi.assistant.agent.planning.RecoveryBranch
import com.airi.assistant.agent.planning.TypedPlanGraph
import com.airi.assistant.agent.planning.buildPlanGraph
import com.airi.assistant.agent.planning.node
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.auth.SecureStorage

// ─────────────────────────────────────────────────────────────────────────────
// TaskPlanner — upgraded to emit TypedPlanGraph (DAG with recovery branches)
//
// Replaces the previous flat List<TaskStep> approach.  Every detected step
// becomes a GoalNode with:
//   • Explicit dependency links → DAG execution order
//   • A RecoveryBranch per step (retry, fallback, skip, abort, replan)
//   • An isCritical flag so the orchestrator knows what failures are fatal
//
// Self-correction path: if a node fails after exhausting its RecoveryBranch,
// the graph emits RecoveryDecision.RequestReplan which the orchestrator feeds
// back to the LLM for a patched subtask.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "TaskPlanner"

class TaskPlanner(private val secureStorage: SecureStorage) {

    companion object {
        private val STEP_CONNECTORS = listOf(
            " then ", " and then ", " after that ", " afterwards ", " next then ",
            " ثم ", " وثم ", " وبعدها ", " ثم بعد ذلك ", " بعد ذلك "
        )

        private val TOOL_KEYWORDS: Map<String, List<String>> = mapOf(
            "github_get_user"        to listOf("github profile", "github user", "my github account", "who am i on github"),
            "github_get_repos"       to listOf("github repos", "my repos", "repositories", "github projects", "my projects"),
            "github_push"            to listOf("push to github", "upload to github", "commit and push", "push changes"),
            "github_create_pr"       to listOf("create pr", "pull request", "open pr"),
            "gmail_list_emails"      to listOf("email", "emails", "gmail", "inbox", "mail", "messages"),
            "telegram_send_message"  to listOf("telegram", "send message", "message via telegram", "via telegram", "through telegram"),
            "drive_search_file"      to listOf("drive", "google drive", "find file", "search file", "workspace", "document"),
            "calendar_next_events"   to listOf("calendar", "schedule", "events", "agenda", "meetings", "upcoming"),
            "analyze_code"           to listOf("analyze code", "find bug", "review code", "check code", "debug code"),
            "fix_code"               to listOf("fix bug", "fix code", "patch", "apply fix", "correct the code")
        )

        // Recovery strategy per tool — critical connectors retry, decorative ones skip
        private val TOOL_RECOVERY: Map<String, RecoveryBranch> = mapOf(
            "github_push"           to RecoveryBranch.Retry(maxAttempts = 3),
            "github_create_pr"      to RecoveryBranch.Retry(maxAttempts = 2),
            "gmail_list_emails"     to RecoveryBranch.Fallback("gmail_list_emails", mapOf("max" to "3")),
            "telegram_send_message" to RecoveryBranch.Retry(maxAttempts = 2),
            "drive_search_file"     to RecoveryBranch.Skip,
            "calendar_next_events"  to RecoveryBranch.Skip,
            "analyze_code"          to RecoveryBranch.Replan,
            "fix_code"              to RecoveryBranch.Replan
        )

        // Whether tool failure aborts the whole plan
        private val TOOL_CRITICAL: Map<String, Boolean> = mapOf(
            "github_push"  to true,
            "github_create_pr" to true,
            "analyze_code" to true,
            "fix_code"     to true
        )
    }

    // ── Original flat API (backward-compat) ───────────────────────────────────

    fun plan(input: String, context: SkillContext): Task? {
        val graph = planAsGraph(input) ?: return null
        // Bridge: convert graph nodes back to flat TaskSteps for legacy callers
        val steps = graph.allNodes().map { node ->
            TaskStep(toolName = node.action, params = node.params, description = node.description)
        }
        if (steps.size < 2) return null
        return Task(originalInput = input, steps = steps)
    }

    // ── New: emit a TypedPlanGraph ─────────────────────────────────────────────

    fun planAsGraph(input: String): TypedPlanGraph? {
        val lower        = input.lowercase()
        val hasConnector = STEP_CONNECTORS.any { lower.contains(it) }
        if (!hasConnector) return null

        val detectedSteps = mutableListOf<DetectedStep>()
        var remaining     = lower

        for (connector in STEP_CONNECTORS) {
            if (!remaining.contains(connector)) continue
            val parts        = remaining.split(connector, limit = 2)
            val firstSegment = parts[0].trim()
            remaining        = parts.getOrElse(1) { "" }.trim()
            detectStep(firstSegment)?.let { detectedSteps.add(it) }
            if (remaining.isBlank()) break
        }
        if (remaining.isNotBlank()) detectStep(remaining)?.let { detectedSteps.add(it) }

        if (detectedSteps.size < 2) return null

        val goalId = "goal_${System.currentTimeMillis()}"
        val graph  = buildPlanGraph(goalId, input) {
            detectedSteps.forEachIndexed { index, step ->
                val nodeId   = "step_${index + 1}"
                val prevId   = if (index > 0) "step_$index" else null
                node(
                    id          = nodeId,
                    description = step.description,
                    action      = step.toolName,
                    params      = step.params,
                    dependsOn   = if (prevId != null) listOf(prevId) else emptyList(),
                    recovery    = TOOL_RECOVERY[step.toolName] ?: RecoveryBranch.Retry(2),
                    isCritical  = TOOL_CRITICAL[step.toolName] ?: false
                )
            }
        }

        Log.i(TAG, "TASK_PLAN_GRAPH_CREATED goalId=$goalId nodeCount=${graph.nodeCount()} inputChars=${input.length}")
        return graph
    }

    /**
     * Re-plan a single failed node given the failure reason.
     * Attempts to patch the node's action/params rather than rebuilding
     * the entire graph — surgical self-correction.
     */
    fun repairNode(graph: TypedPlanGraph, failedNodeId: String, reason: String): GoalNode? {
        val node    = graph.allNodes().firstOrNull { it.id == failedNodeId } ?: return null
        val repaired = selfCorrectNode(node, reason) ?: return null
        graph.patchNode(failedNodeId, repaired.action, repaired.params)
        Log.i(TAG, "AIRI TASK_PLAN_REPAIR nodeId=$failedNodeId newAction=${repaired.action} reason=$reason")
        return repaired
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private data class DetectedStep(
        val toolName:    String,
        val params:      Map<String, String>,
        val description: String
    )

    private fun detectStep(text: String): DetectedStep? {
        for ((tool, keywords) in TOOL_KEYWORDS) {
            if (keywords.none { text.contains(it) }) continue
            if (!isToolAvailable(tool)) continue
            return DetectedStep(
                toolName    = tool,
                params      = extractParams(tool, text),
                description = text.take(60)
            )
        }
        return null
    }

    private fun isToolAvailable(tool: String): Boolean = when {
        tool.startsWith("github")   -> secureStorage.isGithubConnected()
        tool.startsWith("telegram") -> secureStorage.isTelegramConnected()
        tool.startsWith("gmail") || tool.startsWith("drive") || tool.startsWith("calendar") ->
            secureStorage.isGoogleConnected()
        else -> true
    }

    private fun extractParams(tool: String, text: String): Map<String, String> = when (tool) {
        "github_get_repos"       -> mapOf("limit" to "10")
        "github_push"            -> mapOf("message" to "auto: agent commit")
        "github_create_pr"       -> mapOf("title" to "Agent-generated fix", "draft" to "false")
        "gmail_list_emails"      -> mapOf("max" to "5")
        "calendar_next_events"   -> mapOf("count" to "5")
        "analyze_code"           -> mapOf("scope" to "full", "output" to "diff")
        "fix_code"               -> mapOf("apply" to "true")
        "drive_search_file" -> {
            val query = text.replace("find file", "").replace("search file", "")
                .replace("google drive", "").replace("drive", "")
                .replace("document", "").trim()
            mapOf("query" to query.ifBlank { "" })
        }
        "telegram_send_message" -> {
            val toIdx  = text.indexOf(" to ")
            val chatId = if (toIdx >= 0) text.substring(toIdx + 4).trim().split(" ").firstOrNull() ?: "" else ""
            mapOf("chat_id" to chatId, "text" to "")
        }
        else -> emptyMap()
    }

    /**
     * Heuristic self-correction: given a failed node + reason, produce a patched version.
     * Returns null if no correction can be determined.
     */
    private fun selfCorrectNode(node: GoalNode, reason: String): GoalNode? {
        val lower = reason.lowercase()
        return when {
            "permission" in lower && node.action.startsWith("github") ->
                node.copy(action = node.action, params = node.params + mapOf("auth_retry" to "true"))

            "not found" in lower && node.action == "drive_search_file" ->
                node.copy(params = node.params + mapOf("fuzzy" to "true"))

            "timeout" in lower ->
                node.copy(params = node.params + mapOf("timeout_ms" to "30000"))

            "rate limit" in lower ->
                node.copy(params = node.params + mapOf("delay_ms" to "5000"))

            else -> null
        }
    }
}
