package com.airi.assistant.execution

import java.util.Collections
import java.util.LinkedHashMap

/** Transport selected by an adapter; this is a decision, not an executor. */
enum class AttachmentTransport {
    NATIVE_INLINE,
    NATIVE_FILE_REFERENCE,
    EXTRACTED_TEXT,
    FRAME_SAMPLING,
    UNSUPPORTED,
    NOT_READY,
}

/** Resolution state before any provider I/O is attempted. */
data class AttachmentResolution(
    val attachmentId: String,
    val transport: AttachmentTransport,
    val reason: String = "",
)

enum class AttachmentDeliveryStatus {
    SENT_NATIVE,
    SENT_EXTRACTED_TEXT,
    SENT_SAMPLED_MEDIA,
    REJECTED_WITH_REASON,
}

enum class AttachmentDeliveryStage {
    LOCAL_ATTACHMENT_RESOLVED,
    PROVIDER_PAYLOAD_BUILT,
    PAYLOAD_CONTAINS_CONTENT,
    HTTP_REQUEST_DISPATCHED,
    PROVIDER_RESPONSE_RECEIVED,
}

data class AttachmentDeliveryEvidence(
    val attachmentId: String,
    val status: AttachmentDeliveryStatus,
    val provider: CloudProvider?,
    val transport: AttachmentTransport,
    val stages: Set<AttachmentDeliveryStage>,
    val payloadIncluded: Boolean,
    val remoteFileId: String? = null,
    val bytesIncluded: Long = 0L,
    val extractedChars: Int = 0,
    val failureReason: String = "",
)

/**
 * Thread-safe per-request trace. It intentionally stores metadata only;
 * payload bytes and secrets are never retained in diagnostics.
 */
class AttachmentDeliveryTrace(
    resolutions: List<AttachmentResolution>,
    private val provider: CloudProvider?,
) {
    private data class MutableEvidence(
        val attachmentId: String,
        val transport: AttachmentTransport,
        var bytesIncluded: Long,
        val stages: LinkedHashSet<AttachmentDeliveryStage> = LinkedHashSet(),
        var payloadIncluded: Boolean = false,
        var status: AttachmentDeliveryStatus = AttachmentDeliveryStatus.REJECTED_WITH_REASON,
        var remoteFileId: String? = null,
        var extractedChars: Int = 0,
        var failureReason: String = "",
    )

    private val entries = Collections.synchronizedMap(
        LinkedHashMap<String, MutableEvidence>().apply {
            resolutions.forEach { resolution ->
                put(
                    resolution.attachmentId,
                    MutableEvidence(
                        attachmentId = resolution.attachmentId,
                        transport = resolution.transport,
                        bytesIncluded = 0L,
                        status = AttachmentDeliveryStatus.REJECTED_WITH_REASON,
                        failureReason = resolution.reason,
                    )
                )
            }
        }
    )

    fun mark(stage: AttachmentDeliveryStage, attachmentIds: Collection<String> = entries.keys) {
        synchronized(entries) {
            attachmentIds.forEach { id -> entries[id]?.stages?.add(stage) }
        }
    }

    fun markPayloadContainsContent(attachmentId: String, bytesIncluded: Long) {
        synchronized(entries) {
            entries[attachmentId]?.let {
                it.stages.add(AttachmentDeliveryStage.PAYLOAD_CONTAINS_CONTENT)
                it.payloadIncluded = bytesIncluded > 0L
                it.bytesIncluded = bytesIncluded
            }
        }
    }

    fun markProviderResponse(success: Boolean, attachmentIds: Collection<String> = entries.keys) {
        synchronized(entries) {
            attachmentIds.forEach { id ->
                entries[id]?.let {
                    if (success && it.payloadIncluded) {
                        it.status = AttachmentDeliveryStatus.SENT_NATIVE
                    } else if (!success) {
                        it.status = AttachmentDeliveryStatus.REJECTED_WITH_REASON
                        it.failureReason = "Provider did not accept the attachment request."
                    }
                }
            }
        }
    }

    fun markExtractedText(attachmentId: String, chars: Int) {
        synchronized(entries) {
            entries[attachmentId]?.let {
                it.extractedChars = chars.coerceAtLeast(0)
                it.status = AttachmentDeliveryStatus.SENT_EXTRACTED_TEXT
                it.payloadIncluded = chars > 0
                it.stages.add(AttachmentDeliveryStage.PAYLOAD_CONTAINS_CONTENT)
            }
        }
    }

    fun reject(reason: String) {
        synchronized(entries) {
            entries.values.forEach {
                it.status = AttachmentDeliveryStatus.REJECTED_WITH_REASON
                it.failureReason = reason
            }
        }
    }

    fun snapshot(): List<AttachmentDeliveryEvidence> = synchronized(entries) {
        entries.values.map {
            AttachmentDeliveryEvidence(
                attachmentId = it.attachmentId,
                status = it.status,
                provider = provider,
                transport = it.transport,
                stages = it.stages.toSet(),
                payloadIncluded = it.payloadIncluded,
                remoteFileId = it.remoteFileId,
                bytesIncluded = it.bytesIncluded,
                extractedChars = it.extractedChars,
                failureReason = it.failureReason,
            )
        }
    }

    fun isTransportSuccessful(): Boolean = snapshot().isNotEmpty() && snapshot().all { evidence ->
        when (evidence.status) {
            AttachmentDeliveryStatus.SENT_NATIVE ->
                evidence.payloadIncluded &&
                    AttachmentDeliveryStage.HTTP_REQUEST_DISPATCHED in evidence.stages &&
                    AttachmentDeliveryStage.PROVIDER_RESPONSE_RECEIVED in evidence.stages
            AttachmentDeliveryStatus.SENT_EXTRACTED_TEXT,
            AttachmentDeliveryStatus.SENT_SAMPLED_MEDIA -> evidence.payloadIncluded
            AttachmentDeliveryStatus.REJECTED_WITH_REASON -> false
        }
    }
}

/** Thin decision helper. It never reads files, uploads, extracts, or calls a network. */
object AttachmentTransportResolver {
    fun resolve(
        attachmentId: String,
        contentType: String,
        provider: CloudProvider?,
        nativeInlineReady: Boolean,
        extractedTextAvailable: Boolean,
        capabilitySupported: Boolean = false,
        frameSamplingAvailable: Boolean = false,
    ): AttachmentResolution {
        val normalized = contentType.lowercase()
        return when {
            nativeInlineReady -> AttachmentResolution(attachmentId, AttachmentTransport.NATIVE_INLINE)
            extractedTextAvailable -> AttachmentResolution(attachmentId, AttachmentTransport.EXTRACTED_TEXT)
            normalized.startsWith("video/") && frameSamplingAvailable ->
                AttachmentResolution(attachmentId, AttachmentTransport.FRAME_SAMPLING)
            capabilitySupported -> AttachmentResolution(
                attachmentId,
                AttachmentTransport.NOT_READY,
                "The model declares this capability but no ready transport is implemented.",
            )
            provider == null -> AttachmentResolution(attachmentId, AttachmentTransport.NOT_READY, "No provider transport is ready.")
            else -> AttachmentResolution(attachmentId, AttachmentTransport.UNSUPPORTED, "No implemented transport for this attachment.")
        }
    }
}
