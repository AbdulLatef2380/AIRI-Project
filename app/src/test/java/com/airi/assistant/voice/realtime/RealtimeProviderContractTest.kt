package com.airi.assistant.voice.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeProviderContractTest {
    @Test
    fun unknownAndLocalIdentifiersFailClosedToLocalPipeline() {
        assertEquals(LocalVoicePipeline, RealtimeProviderFactory.create("LOCAL"))
        assertEquals(LocalVoicePipeline, RealtimeProviderFactory.create("unknown"))
        assertFalse(RealtimeProviderFactory.isCloud("LOCAL"))
    }

    @Test
    fun cloudIdentifiersSelectBidirectionalProviders() {
        val gemini = RealtimeProviderFactory.create("gemini_live")
        val openAi = RealtimeProviderFactory.create("OPENAI")
        assertTrue(gemini is GeminiLiveProvider)
        assertTrue(gemini.supportsBidirectionalStreaming)
        assertTrue(openAi is OpenAIRealtimeProvider)
        assertTrue(openAi.supportsBidirectionalStreaming)
    }

    @Test
    fun resamplerPreservesIdentityAndExpectedFrameLength() {
        val input = shortArrayOf(0, 1_000, 2_000, 3_000)
        assertEquals(input.toList(), Pcm16Resampler.resample(input, 16_000, 16_000).toList())
        assertEquals(6, Pcm16Resampler.resample(input, 16_000, 24_000).size)
        assertEquals(0, Pcm16Resampler.resample(input, 16_000, 24_000).first())
    }
}
