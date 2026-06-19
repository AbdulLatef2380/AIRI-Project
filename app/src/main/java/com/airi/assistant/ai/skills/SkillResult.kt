package com.airi.assistant.ai.skills

/**
 * SkillResult — output contract for every [AiriSkill.execute] call.
 *
 * [data]     — primary output content fed back into the conversation.
 * [metadata] — optional structured key/value pairs (e.g. URLs, counts).
 * [toolOutputs] — raw outputs from sub-tools the skill invoked.
 */
data class SkillResult(
    val success:     Boolean,
    val data:        String,
    val error:       String?            = null,
    val skillName:   String?            = null,
    val executionMs: Long               = 0L,
    val metadata:    Map<String, String> = emptyMap(),
    val toolOutputs: List<ToolOutput>  = emptyList()
) {
    data class ToolOutput(
        val toolName: String,
        val output:   String,
        val success:  Boolean = true
    )
}
