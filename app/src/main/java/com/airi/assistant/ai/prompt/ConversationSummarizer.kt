package com.airi.assistant.ai.prompt

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Compresses old chat turns into a compact summary using the SAME on-device
 * LLM that handles regular generation.
 *
 * Sequencing rule (CRITICAL):
 *   The native bridge has a single global llama_context. We must NEVER call
 *   `summarize()` while a user-facing generation is in flight — it would
 *   corrupt KV state. The caller is responsible for serializing this against
 *   `generateStream`. In ChatViewModel we run summarization AFTER the active
 *   generation completes (in the onComplete callback).
 *
 * Side-effect:
 *   On success, writes the new summary + coverage marker into MemoryStore.
 */
object ConversationSummarizer {

    private const val SUMMARY_MAX_TOKENS = 220
    private const val SUMMARY_TEMP       = 0.3f

    /**
     * Build a one-shot summarization prompt and run it through the local LLM.
     * Returns the summary text, or null if summarization didn't produce useful
     * output. Suspends until the model returns.
     *
     * @param olderTurns the messages BEFORE the recent sliding window
     * @param previousSummary the summary already on file (if any) — included
     *                        so the model can extend rather than restart
     */
    suspend fun summarize(
        ctx: Context,
        sessionId: String,
        llamaManager: LlamaManager,
        olderTurns: List<ChatMessage>,
        previousSummary: String
    ): String? {
        if (olderTurns.isEmpty()) return null
        val transcript = buildTranscript(olderTurns)
        if (transcript.isBlank()) return null

        val systemPrompt = """
            You are a conversation summarizer. Produce a compact factual summary
            of the conversation below. Focus on:
              - persistent user facts and preferences
              - decisions reached
              - open questions or pending tasks
            Do NOT include greetings, filler, or repeated content. Maximum 6
            short bullet points. Output ONLY the summary, no preamble.
        """.trimIndent()

        val userPrompt = buildString {
            if (previousSummary.isNotBlank()) {
                append("Previous summary:\n").append(previousSummary.trim()).append("\n\n")
                append("New messages to fold in:\n")
            } else {
                append("Conversation:\n")
            }
            append(transcript)
        }

        val result = suspendCancellableCoroutine<String?> { cont ->
            llamaManager.generate(
                prompt       = userPrompt,
                systemPrompt = systemPrompt,
                maxTokens    = SUMMARY_MAX_TOKENS,
                temperature  = SUMMARY_TEMP
            ) { out ->
                // Guard against IllegalStateException when the caller cancels
                // the parent coroutine before the LLM callback fires. Without
                // this, a cancelled summary would crash the dispatcher.
                if (cont.isActive) cont.resume(out.takeIf { it.isNotBlank() })
                else if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_PROMPT_COMPRESS",
                    "SUMMARIZE callback dropped — coroutine already cancelled")
            }
        }

        if (result.isNullOrBlank()) {
            Log.w("AIRI_PROMPT_COMPRESS", "SUMMARIZE returned blank")
            return null
        }
        val cleaned = result.trim().take(2_400) // hard char cap

        MemoryStore.setSummary(ctx, sessionId, cleaned)
        MemoryStore.setSummaryCoverage(ctx, sessionId, olderTurns.size)
        Log.i("AIRI_PROMPT_COMPRESS",
            "SUMMARIZE_OK session=$sessionId folded=${olderTurns.size} chars=${cleaned.length}")
        return cleaned
    }

    private fun buildTranscript(msgs: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (m in msgs) {
            val role = when (m.role) {
                "user"      -> "User"
                "assistant" -> "Assistant"
                else        -> m.role.replaceFirstChar { it.uppercase() }
            }
            // Cap each turn so a single huge message can't blow the summarizer prompt.
            val body = m.content.trim().take(800)
            if (body.isNotBlank()) sb.append(role).append(": ").append(body).append("\n")
        }
        return sb.toString()
    }
}
