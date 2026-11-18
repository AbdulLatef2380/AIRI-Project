package com.airi.assistant.ui.generative

import com.airi.assistant.execution.ExecutionRequest

/** Provider-neutral schema for the native render_ui document. */
object GenerativeUiStructuredOutput {
    val SPEC: ExecutionRequest.StructuredOutputSpec = ExecutionRequest.StructuredOutputSpec(
        name = "airi_ui_document",
        // The nested block payload is validated authoritatively by AIRI's compiler;
        // non-strict native JSON mode keeps provider adapters compatible with the
        // evolving block vocabulary while still guaranteeing a JSON document.
        strict = false,
        schemaJson = """
        {
          "type":"object",
          "required":["version","blocks"],
          "properties":{
            "version":{"type":"integer","enum":[1]},
            "title":{"type":"string"},
            "description":{"type":"string"},
            "blocks":{
              "type":"array","minItems":1,"maxItems":8,
              "items":{
                "type":"object",
                "required":["type","id"],
                "properties":{
                  "type":{"type":"string","enum":["stat","line_chart","bar_chart","pie_chart","image","cards","tabs","timeline","diagram","route","table","checklist","form","actions"]},
                  "id":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"}
                }
              }
            }
          }
        }
        """.trimIndent(),
    )

    /** Native schema enforcement is currently guaranteed only by first-party adapters. */
    fun supportedProvider(providerId: String): Boolean = when {
        providerId.isBlank() -> false
        providerId.contains("openai", ignoreCase = true) -> true
        providerId.contains("gemini", ignoreCase = true) -> true
        providerId.contains("anthropic", ignoreCase = true) -> true
        else -> false
    }

    /** Converts a provider's native JSON response into AIRI's canonical renderable form. */
    fun toFence(response: String): String? {
        val json = response.trim()
        if (!json.startsWith("{") || !json.endsWith("}")) return null
        return runCatching {
            org.json.JSONObject(json)
            "```airi-ui\n$json\n```"
        }.getOrNull()
    }
}
