package com.airi.assistant.perception

import android.graphics.Bitmap
import android.util.Log

// ─────────────────────────────────────────────────────────────────────────────
// PerceptionFusion — Unified Multimodal Input Layer
//
// Merges text, voice transcripts, and vision frames into a single
// CognitiveInput that the planner (UnifiedCognitiveLoop / PlanGenerator)
// consumes as one atomic percept.
//
// Architecture:
//   ChatViewModel ──text──────────────────╮
//   LiveVoiceService ──voiceTranscript───►│ PerceptionFusion ──► CognitiveInput
//   VisionImage ──bitmap (optional) ──────╯
//
// The fused CognitiveInput carries:
//   • primaryText   — the dominant language signal (text or STT transcript)
//   • contextText   — secondary context (screen content, notes, etc.)
//   • visionFrame   — optional Bitmap ready for multimodal models
//   • visionSummary — optional text description of the image for text-only
//                     model fallback (produced by PerceptionFusion)
//   • modalities    — bitmask of which channels contributed
//   • confidence    — 0..1 fusion confidence estimate
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "PerceptionFusion"

// ── Modality flags ────────────────────────────────────────────────────────────

object Modality {
    const val TEXT  = 0b001
    const val VOICE = 0b010
    const val VISION = 0b100
}

// ── Raw perception inputs ─────────────────────────────────────────────────────

data class TextPercept(
    val text:      String,
    val sourceId:  String = "user_input",
    val timestamp: Long   = System.currentTimeMillis()
)

data class VoicePercept(
    val transcript:    String,
    val confidenceP:   Float  = 1f,       // STT confidence [0,1]
    val durationMs:    Long   = 0L,
    val isFinal:       Boolean = true,
    val timestamp:     Long   = System.currentTimeMillis()
)

data class VisionPercept(
    val bitmap:    Bitmap?,
    val rgbBytes:  ByteArray? = null,     // pre-computed RGB888 for native bridge
    val width:     Int        = bitmap?.width  ?: 0,
    val height:    Int        = bitmap?.height ?: 0,
    val label:     String?    = null,     // optional human label ("screenshot", "photo")
    val timestamp: Long       = System.currentTimeMillis()
)

// ── Fused output ──────────────────────────────────────────────────────────────

data class CognitiveInput(
    val primaryText:    String,
    val contextText:    String          = "",
    val visionFrame:    Bitmap?         = null,
    val visionRgb:      ByteArray?      = null,
    val visionSummary:  String?         = null,
    val modalities:     Int             = Modality.TEXT,
    val confidence:     Float           = 1f,
    val sourceIds:      List<String>    = emptyList(),
    val timestamp:      Long            = System.currentTimeMillis()
) {
    val hasVision: Boolean  get() = (modalities and Modality.VISION) != 0
    val hasVoice:  Boolean  get() = (modalities and Modality.VOICE)  != 0
    val hasText:   Boolean  get() = (modalities and Modality.TEXT)   != 0

    val modalityLabel: String
        get() = buildList {
            if (hasText)   add("text")
            if (hasVoice)  add("voice")
            if (hasVision) add("vision")
        }.joinToString("+")

    /** A single prompt-ready string combining all available signals. */
    fun toPromptString(): String = buildString {
        if (visionSummary != null) {
            append("[IMAGE: $visionSummary]\n")
        }
        if (contextText.isNotBlank()) {
            append("[CONTEXT: $contextText]\n")
        }
        append(primaryText)
    }
}

// ── Fusion engine ─────────────────────────────────────────────────────────────

object PerceptionFusion {

    /**
     * Fuse a text percept with optional voice + vision into a [CognitiveInput].
     *
     * Rules:
     *   1. If voice transcript is available and longer / higher confidence than
     *      text, voice becomes primaryText.
     *   2. If a vision frame is provided, generate a lightweight text description
     *      (size, label) as visionSummary for text-only fallback.
     *   3. Confidence is the geometric mean of contributing modality confidences.
     */
    fun fuse(
        text:    TextPercept?,
        voice:   VoicePercept? = null,
        vision:  VisionPercept? = null
    ): CognitiveInput {
        var modalities  = 0
        val sources     = mutableListOf<String>()
        var confidence  = 1f

        // ── Resolve primary text ──────────────────────────────────────────────
        val primaryText: String
        val contextText: String

        when {
            voice != null && voice.isFinal &&
            (text == null || voice.transcript.length >= text.text.length) -> {
                primaryText = voice.transcript
                contextText = text?.text ?: ""
                modalities  = modalities or Modality.VOICE
                confidence  *= voice.confidenceP.coerceIn(0f, 1f)
                sources.add("voice:${voice.transcript.take(20)}")
                if (text != null) {
                    modalities = modalities or Modality.TEXT
                    sources.add(text.sourceId)
                }
            }
            text != null -> {
                primaryText = text.text
                contextText = voice?.transcript?.takeIf { it != text.text } ?: ""
                modalities  = modalities or Modality.TEXT
                sources.add(text.sourceId)
                if (voice != null) {
                    modalities = modalities or Modality.VOICE
                    confidence *= voice.confidenceP.coerceIn(0f, 1f)
                    sources.add("voice")
                }
            }
            else -> {
                Log.w(TAG, "PerceptionFusion: no text or voice input provided")
                return CognitiveInput(primaryText = "", confidence = 0f)
            }
        }

        // ── Vision ────────────────────────────────────────────────────────────
        val visionSummary: String? = vision?.let {
            modalities = modalities or Modality.VISION
            sources.add("vision:${it.label ?: "frame"}")
            buildVisionSummary(it)
        }

        val fused = CognitiveInput(
            primaryText   = primaryText,
            contextText   = contextText,
            visionFrame   = vision?.bitmap,
            visionRgb     = vision?.rgbBytes,
            visionSummary = visionSummary,
            modalities    = modalities,
            confidence    = confidence,
            sourceIds     = sources
        )

        Log.i(TAG, "AIRI PERCEPTION_FUSED modalities=${fused.modalityLabel} " +
            "confidence=${"%.2f".format(confidence)} " +
            "primaryLen=${primaryText.length}")

        return fused
    }

    /**
     * Convenience: fuse text only (most common path, zero overhead).
     */
    fun fromText(text: String, sourceId: String = "user_input"): CognitiveInput =
        CognitiveInput(
            primaryText = text,
            modalities  = Modality.TEXT,
            confidence  = 1f,
            sourceIds   = listOf(sourceId)
        )

    /**
     * Convenience: fuse voice transcript only (voice-only session).
     */
    fun fromVoice(transcript: String, confidenceP: Float = 1f): CognitiveInput =
        CognitiveInput(
            primaryText = transcript,
            modalities  = Modality.VOICE,
            confidence  = confidenceP,
            sourceIds   = listOf("voice")
        )

    /**
     * Convenience: fuse text + vision (chat with image attachment).
     */
    fun fromTextAndVision(text: String, vision: VisionPercept): CognitiveInput =
        fuse(text = TextPercept(text), vision = vision)

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildVisionSummary(v: VisionPercept): String {
        val label = v.label ?: "image"
        val dims  = if (v.width > 0 && v.height > 0) "${v.width}×${v.height}" else "unknown size"
        return "$label ($dims)"
    }
}
