package com.airi.assistant.ui.plan

import com.google.gson.Gson

object PlanSnapshotCodec {
    private val gson = Gson()

    fun encode(snapshot: PlanSnapshot): String = gson.toJson(snapshot)

    fun decode(raw: String): PlanSnapshot? =
        runCatching { gson.fromJson(raw, PlanSnapshot::class.java) }.getOrNull()
}
