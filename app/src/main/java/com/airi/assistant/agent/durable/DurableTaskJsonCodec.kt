package com.airi.assistant.agent.durable

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Versioned JSON interchange codec for the current local task store.
 * Legacy root arrays remain readable; all new writes use a versioned envelope
 * so a later Room import can validate and migrate records deterministically.
 */
class DurableTaskJsonCodec(private val gson: Gson = Gson()) {
    fun encode(tasks: List<DurableTask>): String = gson.toJson(
        DurableTaskFileDocument(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            tasks = tasks.map(DurableTask::withDeserializationDefaults)
        )
    )

    fun decode(json: String): List<DurableTask> {
        val root = JsonParser.parseString(json)
        val tasks = when {
            root.isJsonArray -> gson.fromJson<List<DurableTask>>(root, TASK_LIST_TYPE).orEmpty()
            root.isJsonObject -> {
                val versionElement = root.asJsonObject.get("schemaVersion")
                    ?: throw IllegalArgumentException("Task document has no schemaVersion")
                val version = versionElement.asInt
                require(version in 1..CURRENT_SCHEMA_VERSION) {
                    "Unsupported task document schemaVersion=$version"
                }
                val tasksElement = root.asJsonObject.get("tasks")
                    ?: throw IllegalArgumentException("Task document has no tasks collection")
                require(tasksElement.isJsonArray) { "Task document tasks must be an array" }
                gson.fromJson<List<DurableTask>>(tasksElement, TASK_LIST_TYPE).orEmpty()
            }
            else -> throw IllegalArgumentException("Task document root must be an object or legacy array")
        }
        return tasks.map(DurableTask::withDeserializationDefaults)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val TASK_LIST_TYPE: Type = object : TypeToken<List<DurableTask>>() {}.type
    }
}

private data class DurableTaskFileDocument(
    val schemaVersion: Int,
    val tasks: List<DurableTask>
)
