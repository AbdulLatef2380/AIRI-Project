package com.airi.assistant.execution.cloud

import com.airi.assistant.ai.QueryType
import com.airi.assistant.execution.ExecutionRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterAdapterTest {

    private fun request(
        prompt: String = "hello",
        queryType: QueryType = QueryType.SIMPLE,
        vision: Boolean = false,
        longContext: Boolean = false,
    ) = ExecutionRequest(
        prompt = prompt,
        systemPrompt = "",
        maxTokens = 128,
        temperature = 0.7f,
        queryType = queryType,
        requiresVision = vision,
        requiresLongContext = longContext,
    )

    @Test
    fun automaticSelectionNeverReturnsRetiredGemini20FreeId() {
        val selected = listOf(
            OpenRouterAdapter.selectModel(request()),
            OpenRouterAdapter.selectModel(request(vision = true)),
            OpenRouterAdapter.selectModel(request(longContext = true)),
            OpenRouterAdapter.selectModel(request("write kotlin code", queryType = QueryType.ACTION)),
            OpenRouterAdapter.selectModel(request("analyze this", queryType = QueryType.ANALYTICAL)),
        )

        assertFalse(selected.any { it == "google/gemini-2.0-flash-exp:free" })
        assertFalse(selected.any { it.contains("gemini-2.0") })
    }

    @Test
    fun defaultRouteUsesCurrentFreeCatalogModel() {
        assertEquals("qwen/qwen3.8-27b:free", OpenRouterAdapter.DEFAULT_MODEL)
    }
}
