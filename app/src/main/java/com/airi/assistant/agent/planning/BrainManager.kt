package com.airi.assistant.agent.planning

import android.content.Context
import android.util.Log
import com.airi.assistant.accessibility.service.AiriAccessibilityService
import com.airi.assistant.core.intent.IntentType

object BrainManager {

    private const val TAG = "AIRI_BRAIN"

    /**
     * DEAD PATH — superseded by the SubAgentRegistry routing pipeline.
     *
     * This method drove the original intent-keyword loop
     * (UIMemory → ActionPlanner → IntentEngine → AiriAccessibilityService).
     * It has NO callers in the current codebase.
     *
     * The live path is:
     *   ChatViewModel.sendMessage()
     *     → SubAgentRegistry.route()
     *       → AndroidAgent / AccessibilityCommandBridge
     *
     * Retained only to avoid breaking any external callers that might be
     * introduced through testing. If resurrected, execution MUST be launched
     * on a background dispatcher — IntentEngine.execute() calls accessibility
     * APIs synchronously and must NOT be invoked on the main or accessibility
     * service thread.
     *
     * @deprecated No live callers. Use SubAgentRegistry routing instead.
     */
    @Deprecated(
        message = "Dead path — no live callers. Route through SubAgentRegistry instead.",
        level   = DeprecationLevel.WARNING
    )
    fun processScreen(context: Context, screenText: String) {
        Log.e(TAG, "BrainManager.processScreen() called — this is a dead path with no live " +
              "callers. Fix the call site to use SubAgentRegistry routing.")

        val searchKeywords = listOf("search", "Search", "بحث", "🔍")

        for (keyword in searchKeywords) {
            val rememberedNode = UIMemory.recallNode(context, keyword)

            if (rememberedNode != null) {
                Log.i(TAG, "Memory triggered for keyword: $keyword")

                val intent = AiriIntent(IntentType.CLICK, keyword)
                val plan = ActionPlanner.plan(intent)

                for (step in plan) {
                    IntentEngine.execute(step)
                }
                return
            }
        }

        val intent = IntentEngine.resolve(screenText)

        if (intent != null) {
            if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "New intent detected via Analysis: $intent")

            val plan = ActionPlanner.plan(intent)

            for (step in plan) {
                IntentEngine.execute(step)
            }
        } else {
            Log.w(TAG, "No clear intent detected for this screen. Monitoring...")
        }
    }

    /**
     * DEAD PATH — no live callers. The body is a no-op string scan.
     *
     * @deprecated No live callers. Use SubAgentRegistry routing instead.
     */
    @Deprecated(
        message = "Dead path — no live callers. Route through SubAgentRegistry instead.",
        level   = DeprecationLevel.WARNING
    )
    fun processScreenContext(context: String, service: AiriAccessibilityService) {
        Log.e(TAG, "BrainManager.processScreenContext() called — dead path.")
        try {
            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_BRAIN", "Processing context: $context")
            if (context.contains("search", true) || context.contains("بحث", true)) {
                if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_BRAIN", "Search related screen detected")
            }

        } catch (e: Exception) {
            Log.e("AIRI_BRAIN", "Context processing error", e)
        }
    }
}
