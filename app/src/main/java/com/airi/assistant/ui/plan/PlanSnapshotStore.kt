package com.airi.assistant.ui.plan

import android.content.Context

/**
 * Small durable store for the active plan only. It deliberately stores no
 * prompts, tokens, API keys, connector credentials, or certificate material.
 */
class PlanSnapshotStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(snapshot: PlanSnapshot) {
        prefs.edit().putString(KEY_ACTIVE, PlanSnapshotCodec.encode(snapshot)).apply()
    }

    fun load(): PlanSnapshot? = prefs.getString(KEY_ACTIVE, null)
        ?.let(PlanSnapshotCodec::decode)

    fun clear() { prefs.edit().remove(KEY_ACTIVE).apply() }

    companion object {
        private const val PREFS = "airi_plan_state"
        private const val KEY_ACTIVE = "active_plan_snapshot"
    }
}
