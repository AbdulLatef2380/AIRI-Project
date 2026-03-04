package com.airi.assistant.brain

import org.json.JSONObject

object PlanParser {

    fun parse(json: String): PlanDto? {
        return try {
            val obj = JSONObject(json)
            val stepsArray = obj.getJSONArray("steps")
            val stepsList = mutableListOf<StepDto>()

            for (i in 0 until stepsArray.length()) {
                val s = stepsArray.getJSONObject(i)
                val id = s.optString("id", i.toString())
                val action = s.getString("action")
                stepsList.add(StepDto(id, action))
            }

            PlanDto(stepsList)

        } catch (e: Exception) {
            null
        }
    }
}
