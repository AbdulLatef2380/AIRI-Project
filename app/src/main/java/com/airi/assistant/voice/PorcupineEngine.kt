package com.airi.assistant.voice

import android.content.Context

/**
 * PorcupineEngine — DEPRECATED compatibility shim.
 *
 * This object previously wrapped the Picovoice Porcupine wake-word SDK.
 * Porcupine has been removed; on-device wake-word detection is now handled
 * entirely by [InternalWakeWordEngine] which uses Vosk grammar-constrained
 * keyword spotting (no API key, no proprietary SDK).
 *
 * This shim exists ONLY to avoid breaking any call sites compiled against
 * the old API. All methods delegate to [InternalWakeWordEngine].
 *
 * ── MIGRATION ──────────────────────────────────────────────────────────
 *   OLD: PorcupineEngine.status(context).ready
 *   NEW: InternalWakeWordEngine.status(context).ready
 *
 *   OLD: PorcupineEngine.setRuntimeAccessKey(context, key)
 *   NEW: No equivalent — no API key is required any more.
 */
@Deprecated(
    message  = "Picovoice Porcupine has been removed. Use InternalWakeWordEngine instead.",
    replaceWith = ReplaceWith("InternalWakeWordEngine", "com.airi.assistant.voice.InternalWakeWordEngine")
)
object PorcupineEngine {

    /**
     * Legacy status object for call sites that haven't migrated yet.
     * Maps onto [InternalWakeWordEngine.Status].
     */
    data class Status(
        /** True when a Vosk model is installed and selected. */
        val ready: Boolean,
        /** Always true (no access key required). */
        val accessKeyPresent: Boolean  = true,
        /** Always true when a Vosk model is installed. */
        val ppnPresent: Boolean        = ready,
        /** Human-readable source label for the active model. */
        val ppnSourceLabel: String?    = null,
        /** Legacy field — always null (no key source). */
        val accessKeySource: String?   = null
    )

    /**
     * Returns a [Status] derived from [InternalWakeWordEngine.status].
     *
     * The wake-word is ready as long as a Vosk model is installed; no API key
     * is needed.
     */
    fun status(context: Context): Status {
        val internal = InternalWakeWordEngine.status(context)
        return Status(
            ready          = internal.ready,
            accessKeyPresent = true,
            ppnPresent     = internal.modelInstalled,
            ppnSourceLabel = if (internal.modelInstalled) "vosk:${internal.modelName}" else null,
            accessKeySource = null
        )
    }

    /**
     * No-op — no API key is required when using [InternalWakeWordEngine].
     * Kept for binary compatibility.
     */
    fun setRuntimeAccessKey(context: Context, key: String?) {
        /* intentionally empty — Vosk does not need an access key */
    }

    /** @deprecated Use [InternalWakeWordEngine.loadModel] instead. */
    fun accessKey(context: Context): String = ""
}
