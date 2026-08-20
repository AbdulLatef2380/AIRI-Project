package com.airi.assistant.ai

import android.util.Log

/**
 * Static capability profile of the currently-loaded model. Populated once
 * after [LlamaNative.loadModelWithProgress] returns by reading
 * [LlamaNative.getModelDescription] and the file name. Surfaced in the UI
 * so the user can never mistake "I uploaded an image" for "the model
 * understood the image" — if [vision] is false, the chat path appends a
 * plain-text marker instead of pretending to do multimodal inference.
 *
 * The detection is intentionally conservative: a capability is FALSE
 * unless the loaded artifact is one we know supports it end-to-end. This
 * means a brand-new vision-capable architecture would be reported as
 * "vision=false" until we add it to [VISION_TAGS]; that is the right
 * default — false negatives are acceptable, false positives are not.
 *
 * Emits AIRI MODEL_CAPABILITIES_DETECTED on every population so the
 * decision can be audited from logcat.
 */
data class ModelCapabilities(
    val text: Boolean,
    val vision: Boolean,
    val embeddings: Boolean,
    val toolCalling: Boolean,
    val rawDescription: String
) {
    fun summary(): String = buildString {
        append("text=").append(yn(text))
        append(" vision=").append(yn(vision))
        append(" embeddings=").append(yn(embeddings))
        append(" tools=").append(yn(toolCalling))
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"

    companion object {
        const val UNKNOWN_DESC = "UNAVAILABLE"

        // Substrings (case-insensitive) that mark an artifact as multimodal.
        // Only architectures that are wired through `airi_eval_image` (i.e.
        // backed by a real mtmd/CLIP path inside the native bridge) belong
        // here. Adding a name here without the C++ side being implemented
        // would re-introduce the exact UX bug we built this class to fix.
        private val VISION_TAGS = listOf(
            "llava", "moondream", "minicpm-v", "minicpm-vision",
            "qwen2-vl", "qwen2.5-vl", "internvl", "phi-3-vision",
            "smolvlm"
        )

        // Architectures that are typically embedding-only models. A normal
        // chat LLM CAN also produce embeddings via llama_get_embeddings,
        // but the dedicated embedding GGUFs return a meaningful pooled
        // vector — surfacing this lets the Memory pipeline auto-pick the
        // right model for semantic search.
        private val EMBEDDING_TAGS = listOf(
            "bge-", "e5-", "gte-", "nomic-embed", "all-minilm",
            "snowflake-arctic-embed", "mxbai-embed", "jina-embed"
        )

        // Models whose chat template was post-trained for tool/function
        // calls. Conservative list — we only enable the tool path if the
        // user explicitly opted in AND the model is on this list.
        private val TOOL_CALL_TAGS = listOf(
            "qwen2", "qwen2.5", "llama-3.1", "llama-3.2", "llama-3.3",
            "mistral-7b-instruct-v0.3", "hermes-2", "functionary",
            "command-r"
        )

        /**
         * Build a capability profile from whatever the native bridge
         * reports about the live llama_model + the on-disk filename.
         * Returns a "text-only" baseline if the bridge is not loaded.
         */
        fun detect(modelInfo: ModelInfo): ModelCapabilities {
            val nativeDesc = runCatching { LlamaNative.getModelDescription() }
                .getOrDefault(UNKNOWN_DESC)
            val haystack = (nativeDesc + "|" + modelInfo.fileName + "|" + modelInfo.name)
                .lowercase()

            val text       = nativeDesc != UNKNOWN_DESC
            // Vision is TRUE iff (a) the architecture is on the wired list AND
            // (b) the native bridge confirms an mmproj has been loaded into
            // g_mtmd_ctx via airi_load_mmproj. Without (b) we have a vision
            // model file but no projector → cannot do real inference, so the
            // attach-image path stays text-only and the chat still composes
            // an honest "[image attached]" marker (no fabricated vision response).
            val tagMatch    = VISION_TAGS.any { it in haystack }
            val mmprojLoaded = runCatching { LlamaNative.isMmprojLoaded() }.getOrDefault(false)
            val vision     = tagMatch && mmprojLoaded
            val embeddings = EMBEDDING_TAGS.any { it in haystack }
            val tools      = TOOL_CALL_TAGS.any { it in haystack }

            val caps = ModelCapabilities(
                text       = text,
                vision     = vision,
                embeddings = embeddings,
                toolCalling = tools,
                rawDescription = nativeDesc
            )
            Log.i(
                "AIRI",
                "MODEL_CAPABILITIES_DETECTED file=${modelInfo.fileName} desc=\"$nativeDesc\" ${caps.summary()}"
            )
            return caps
        }

        fun textOnlyFallback(): ModelCapabilities = ModelCapabilities(
            text = false, vision = false, embeddings = false,
            toolCalling = false, rawDescription = UNKNOWN_DESC
        )
    }
}
