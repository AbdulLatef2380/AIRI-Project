package com.airi.assistant.accessibility

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.airi.assistant.data.*

object BehaviorEngine {

    private var database: AppDatabase? = null

    fun initialize(context: Context) {
        database = AppDatabase.getDatabase(context)
    }

    fun recordUsage(suggestion: String) {
        val db = database ?: return

        CoroutineScope(Dispatchers.IO).launch {
            db.usageStatsDao().incrementUsage(
                suggestion,
                System.currentTimeMillis()
            )
        }
    }

    fun adjustSuggestionPriority(suggestions: List<String>): List<String> {
        val db = database ?: return suggestions

        // حالياً نعيدها كما هي
        // المرحلة التالية: تحميل stats وترتيبها حسب usageCount
        return suggestions
    }
}
