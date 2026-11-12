package com.airi.assistant.attachments

import android.graphics.Bitmap
import android.net.Uri

/**
 * Android-only attachment payload handles.
 *
 * These values are transient transport inputs. They must not cross into
 * core-domain metadata, Room metadata, prompts, or desktop contracts.
 */
data class AndroidAttachmentPayload(
    val sourceUri: Uri? = null,
    val capturedBitmap: Bitmap? = null,
    val persistedPath: String? = null,
) {
    fun hasSource(): Boolean = sourceUri != null || capturedBitmap != null || !persistedPath.isNullOrBlank()
}
