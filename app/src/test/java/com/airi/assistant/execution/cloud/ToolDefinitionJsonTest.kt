package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest
import org.junit.Assert.assertFalse
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

    @Test
    fun everyProviderNormalizesInternalTypeAliases() {
        val aliased = definition.copy(
            parameters = listOf(
                ExecutionRequest.ToolParameter("count", "int"),
                ExecutionRequest.ToolParameter("ratio", "float"),
                ExecutionRequest.ToolParameter("enabled", "bool"),
                ExecutionRequest.ToolParameter("payload", "json"),
                ExecutionRequest.ToolParameter("link", "url"),
            )
        )

        val openAi = ToolDefinitionJson.openAiFunction(aliased)
        val anthropic = ToolDefinitionJson.anthropicTool(aliased)
        val gemini = ToolDefinitionJson.geminiDeclaration(aliased)

        listOf(openAi, anthropic).forEach { json ->
            assertTrue(json.contains("\"type\":\"integer\""))
            assertTrue(json.contains("\"type\":\"number\""))
            assertTrue(json.contains("\"type\":\"boolean\""))
            assertTrue(json.contains("\"type\":\"object\""))
            assertTrue(json.contains("\"type\":\"string\""))
            assertFalse(json.contains("\"type\":\"int\""))
            assertFalse(json.contains("\"type\":\"float\""))
            assertFalse(json.contains("\"type\":\"bool\""))
        }
        assertTrue(gemini.contains("\"type\":\"INTEGER\""))
        assertTrue(gemini.contains("\"type\":\"NUMBER\""))
        assertTrue(gemini.contains("\"type\":\"BOOLEAN\""))
        assertTrue(gemini.contains("\"type\":\"OBJECT\""))
        assertTrue(gemini.contains("\"type\":\"STRING\""))
        assertFalse(gemini.contains("\"type\":\"INT\""))
        assertFalse(gemini.contains("\"type\":\"FLOAT\""))
        assertFalse(gemini.contains("\"type\":\"BOOL\""))
    }

    @Test
    fun complexArrayAndObjectSchemasCarryItemsPropertiesAndNestedRequired() {
        val complex = definition.copy(
            parameters = listOf(
                ExecutionRequest.ToolParameter(
                    name = "records",
                    type = "array",
                    items = ExecutionRequest.ToolParameter(
                        name = "record",
                        type = "object",
                        properties = listOf(
                            ExecutionRequest.ToolParameter("id", "integer"),
                            ExecutionRequest.ToolParameter("label", "string", required = false),
                        ),
                    ),
                ),
            ),
        )

        val openAi = ToolDefinitionJson.openAiFunction(complex)
        val anthropic = ToolDefinitionJson.anthropicTool(complex)
        val gemini = ToolDefinitionJson.geminiDeclaration(complex)

        listOf(openAi, anthropic).forEach { json ->
            assertTrue(json.contains("\"type\":\"array\""))
            assertTrue(json.contains("\"items\":{\"type\":\"object\""))
            assertTrue(json.contains("\"properties\":{\"id\":{\"type\":\"integer\"}"))
            assertTrue(json.contains("\"required\":[\"id\"]"))
            assertFalse(json.contains("\"required\":[\"id\",\"label\"]"))
        }
        assertTrue(gemini.contains("\"type\":\"ARRAY\""))
        assertTrue(gemini.contains("\"items\":{\"type\":\"OBJECT\""))
        assertTrue(gemini.contains("\"properties\":{\"id\":{\"type\":\"INTEGER\"}"))
        assertTrue(gemini.contains("\"required\":[\"id\"]"))
    }
}
