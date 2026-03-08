package com.airi.assistant.brain

import android.content.Context
import android.util.Log
import com.airi.assistant.accessibility.AIRIAccessibilityService

object BrainManager {

    private const val TAG = "AIRI_BRAIN"

    fun processScreen(context: Context, screenText: String) {

        Log.d(TAG, "Analyzing screen context...")

        val searchKeywords = listOf(
            "search",
            "Search",
            "بحث",
            "🔍"
        )

        for (keyword in searchKeywords) {
            val rememberedNode = UIMemory.recallNode(context, keyword)

            if (rememberedNode != null) {
                Log.i(TAG, "Memory triggered for keyword: $keyword")
                
                IntentEngine.execute(Intent(IntentType.CLICK, keyword))
                return 
            }
        }

        val intent = IntentEngine.resolve(screenText)

        if (intent != null) {
            Log.d(TAG, "New intent detected via Analysis: $intent")
            IntentEngine.execute(intent)
        } else {
            Log.w(TAG, "No clear intent detected for this screen. Monitoring...")
        }
    }

    fun processScreenContext(context: String, service: AIRIAccessibilityService) {
        try {
            Log.d("AIRI_BRAIN", "Processing context: $context")

            if (context.contains("search", true) || context.contains("بحث", true)) {
                Log.d("AIRI_BRAIN", "Search related screen detected")
            }

        } catch (e: Exception) {
            Log.e("AIRI_BRAIN", "Context processing error", e)
        }
    }
}
