package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDefinitionJsonTest {
    private val definition = ExecutionRequest.ToolDefinition(
        name = "web_search",
        description = "Search the web",
        parameters = listOf(
            ExecutionRequest.ToolParameter("query", "string", "Search query"),
        ),
    )

    @Test
    fun openAiChatUsesFunctionWrapper() {
        val json = ToolDefinitionJson.openAiFunction(definition)
        assertTrue(json.contains("\"type\":\"function\""))
        assertTrue(json.contains("\"name\":\"web_search\""))
        assertTrue(json.contains("\"type\":\"string\""))
    }

    @Test
    fun anthropicUsesInputSchema() {
        val json = ToolDefinitionJson.anthropicTool(definition)
        assertTrue(json.contains("\"input_schema\""))
        assertTrue(json.contains("\"required\":[\"query\"]"))
    }

    @Test
    fun geminiUsesUppercaseSchemaTypes() {
        val json = ToolDefinitionJson.geminiDeclaration(definition)
        assertTrue(json.contains("\"type\":\"OBJECT\""))
        assertTrue(json.contains("\"type\":\"STRING\""))
    }
}
