package com.airi.assistant.adaptive

import com.airi.assistant.data.AppDatabase
import kotlinx.coroutines.runBlocking

object SuggestionScoreEngine {

    private var db: AppDatabase? = null

    fun initialize(database: AppDatabase) {
        db = database
    }

    fun calculate(app: String, intent: String): Int {

        val database = db ?: return 50
        val key = "$app|$intent"

        val stats = runBlocking {
            database.behaviorStatsDao().get(key)
        } ?: return 60

        val acceptanceRate =
            if (stats.shownCount == 0) 0.5
            else stats.acceptedCount.toDouble() / stats.shownCount

        val penalty =
            if (stats.dismissedCount > stats.acceptedCount) 10 else 0

        val score = (acceptanceRate * 100).toInt() - penalty

        return score.coerceIn(0, 100)
    }
}
