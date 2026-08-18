package com.airi.assistant.domain

import android.graphics.Bitmap
import android.net.Uri
import java.util.UUID

/**
 * PHASE 3 (actual fix): single, unified representation of *anything* the
 * user attaches to a chat message. Replaces the previous fragmented model
 * where the chat screen kept three orphan states (`selectedImageUri`,
 * `capturedBitmap`, file picker that did nothing) and the view-model had
 * an image-only entry point.
 *
 * **One type → one chip → one send path.** All four pickers (gallery
 * image, camera capture, generic file, future ones) construct one of
 * these and append it to a single `pendingAttachments` list. The
 * `ChatViewModel.sendMessageWithAttachments(...)` entry-point then makes
 * exactly one capability decision (vision-ready vs. text-marker) per
 * attachment in the list — no hidden forks.
 */
data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    /** URI for picker-sourced attachments. `null` only for raw camera bitmaps. */
    val uri: Uri? = null,
    /** In-memory bitmap for camera captures (`uri` is null in that case). */
    val bitmap: Bitmap? = null,
    /** Human-readable label shown on the input chip. */
    val displayName: String,
    /** Best-effort MIME type ("image/jpeg", "application/pdf", "text/plain", ...). */
    val mimeType: String? = null,
    /** File size in bytes if known (used for "[file: name (12 KB)]" markers). */
    val sizeBytes: Long? = null,
    /** Local file path after persistence to filesDir/attachments/. Null until sent. */
    val persistedPath: String? = null,
    /** Convenience uid alias — used by feedback and chip removal code. */
    val uid: String = id,
    /** File name for persisted file naming. */
    val fileName: String? = displayName.takeIf { it.isNotBlank() }
) {
    enum class Kind {
        /** Image picked from the gallery (URI is content://...). */
        IMAGE,
        /** Photo captured live by the camera (bitmap is in-memory, URI is null). */
        CAMERA,
        /** Generic file from the system file picker (any MIME). */
        FILE
    }

    /** True when the attachment carries an image payload that the vision
     *  pipeline can decode (either an image URI or a captured bitmap). */
    val isVisualImage: Boolean
        get() = kind == Kind.IMAGE || kind == Kind.CAMERA

    /** Marker appended to the user message when the vision pipeline is not
     *  available — gives the assistant honest context about what was
     *  attached without inventing a fabricated "[image attached]" string. */
    fun toTextMarker(): String {
        val sizeStr = sizeBytes?.let { sz ->
            when {
                sz >= 1024 * 1024 -> " (${"%.1f".format(sz / (1024.0 * 1024.0))} MB)"
                sz >= 1024        -> " (${sz / 1024} KB)"
                else              -> " (${sz} B)"
            }
        }.orEmpty()
        val mimeStr = mimeType?.let { " — $it" }.orEmpty()
        return when (kind) {
            Kind.IMAGE, Kind.CAMERA -> "[image: $displayName$sizeStr$mimeStr]"
            Kind.FILE               -> "[file: $displayName$sizeStr$mimeStr]"
        }
    }
}
