package com.airi.assistant.execution.backend

import com.airi.assistant.execution.ExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolCallPromptContractTest {
    private val definitions = listOf(
        ExecutionRequest.ToolDefinition(
            name = "github_get_repos",
            description = "Read repositories",
            parameters = listOf(
                ExecutionRequest.ToolParameter(
                    name = "owner",
                    type = "string",
                    required = true,
                ),
            ),
        ),
    )

    @Test
    fun localContractContainsTheCanonicalSingleAndBatchEnvelopes() {
        val prompt = LocalToolCallPromptContract.render(definitions)
        assertTrue(prompt.contains("{\"tool_call\":{\"name\""))
        assertTrue(prompt.contains("{\"tool_calls\":["))
        assertTrue(prompt.contains("github_get_repos"))
        assertTrue(prompt.contains("owner (string, required)"))
    }

    @Test
    fun existingContractIsNotDuplicated() {
        val existing = "system\n\n${LocalToolCallPromptContract.render(definitions)}"
        assertEquals(existing, LocalToolCallPromptContract.appendIfMissing(existing, definitions))
    }

    @Test
    fun emptyDefinitionsDoNotChangeTheSystemPrompt() {
        assertEquals("system", LocalToolCallPromptContract.appendIfMissing("system", emptyList()))
    }
}
