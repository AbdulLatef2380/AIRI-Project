package com.airi.assistant.execution

import java.util.Collections
import java.util.LinkedHashMap

/** Transport selected by an adapter; this is a decision, not an executor. */
enum class AttachmentTransport {
    NATIVE_INLINE,
    NATIVE_FILE_REFERENCE,
    EXTRACTED_TEXT,
    LOCAL_VISION,
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
    PREPARED_EXTRACTED_TEXT,
    SENT_EXTRACTED_TEXT,
    PREPARED_LOCAL_VISION,
    SENT_LOCAL_VISION,
    SENT_SAMPLED_MEDIA,
    REJECTED_WITH_REASON,
}

enum class AttachmentDeliveryStage {
    LOCAL_ATTACHMENT_RESOLVED,
    PROVIDER_PAYLOAD_BUILT,
    EXECUTION_REQUEST_BUILT,
    PAYLOAD_CONTAINS_CONTENT,
    HTTP_REQUEST_DISPATCHED,
    PROVIDER_RESPONSE_RECEIVED,
    MODEL_RESPONSE_COMPLETED,
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
    provider: CloudProvider?,
) {
    @Volatile private var provider: CloudProvider? = provider
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

    fun setProvider(provider: CloudProvider) {
        synchronized(entries) { this.provider = provider }
    }

    fun markPayloadContainsContent(attachmentId: String, bytesIncluded: Long) {
        synchronized(entries) {
            entries[attachmentId]?.let {
                it.stages.add(AttachmentDeliveryStage.PAYLOAD_CONTAINS_CONTENT)
                it.payloadIncluded = bytesIncluded > 0L
                it.bytesIncluded = bytesIncluded.coerceAtLeast(0L)
            }
        }
    }

    fun markProviderResponse(success: Boolean, attachmentIds: Collection<String> = entries.keys) {
        synchronized(entries) {
            attachmentIds.forEach { id ->
                entries[id]?.let {
                    if (success &&
                        it.payloadIncluded &&
                        AttachmentDeliveryStage.HTTP_REQUEST_DISPATCHED in it.stages &&
                        AttachmentDeliveryStage.PROVIDER_RESPONSE_RECEIVED in it.stages
                    ) {
                        it.status = AttachmentDeliveryStatus.SENT_NATIVE
                        it.failureReason = ""
                    } else if (success) {
                        it.status = AttachmentDeliveryStatus.REJECTED_WITH_REASON
                        it.failureReason = "Provider delivery was not proven by request and response evidence."
                    } else {
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
                it.payloadIncluded = chars > 0
                if (chars > 0) {
                    it.status = AttachmentDeliveryStatus.PREPARED_EXTRACTED_TEXT
                    it.stages.add(AttachmentDeliveryStage.PAYLOAD_CONTAINS_CONTENT)
                    it.failureReason = ""
                } else {
                    it.status = AttachmentDeliveryStatus.REJECTED_WITH_REASON
                    it.failureReason = "No extracted text was included in the execution request."
                }
            }
        }
    }

    fun markLocalVisionContent(attachmentId: String, bytesIncluded: Long) {
        synchronized(entries) {
            entries[attachmentId]?.let {
                it.bytesIncluded = bytesIncluded.coerceAtLeast(0L)
                it.payloadIncluded = bytesIncluded > 0L
                if (bytesIncluded > 0L) {
                    it.status = AttachmentDeliveryStatus.PREPARED_LOCAL_VISION
                    it.stages.add(AttachmentDeliveryStage.PAYLOAD_CONTAINS_CONTENT)
                    it.failureReason = ""
                } else {
                    it.status = AttachmentDeliveryStatus.REJECTED_WITH_REASON
                    it.failureReason = "No image content was included in local vision inference."
                }
            }
        }
    }

    fun markExecutionRequestBuilt() {
        synchronized(entries) {
            entries.values.forEach { it.stages.add(AttachmentDeliveryStage.EXECUTION_REQUEST_BUILT) }
        }
    }

    fun markExecutionCompleted() {
        synchronized(entries) {
            entries.values.forEach {
                it.stages.add(AttachmentDeliveryStage.MODEL_RESPONSE_COMPLETED)
                when (it.status) {
                    AttachmentDeliveryStatus.PREPARED_EXTRACTED_TEXT ->
                        it.status = AttachmentDeliveryStatus.SENT_EXTRACTED_TEXT
                    AttachmentDeliveryStatus.PREPARED_LOCAL_VISION ->
                        it.status = AttachmentDeliveryStatus.SENT_LOCAL_VISION
                    else -> Unit
                }
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

    fun isTransportSuccessful(): Boolean = snapshot().let { evidence ->
        evidence.isNotEmpty() && evidence.all { item ->
            when (item.status) {
                AttachmentDeliveryStatus.SENT_NATIVE ->
                    item.payloadIncluded &&
                        AttachmentDeliveryStage.HTTP_REQUEST_DISPATCHED in item.stages &&
                        AttachmentDeliveryStage.PROVIDER_RESPONSE_RECEIVED in item.stages
                AttachmentDeliveryStatus.SENT_EXTRACTED_TEXT,
                AttachmentDeliveryStatus.SENT_LOCAL_VISION,
                AttachmentDeliveryStatus.SENT_SAMPLED_MEDIA ->
                    item.payloadIncluded &&
                        AttachmentDeliveryStage.EXECUTION_REQUEST_BUILT in item.stages &&
                        AttachmentDeliveryStage.MODEL_RESPONSE_COMPLETED in item.stages
                AttachmentDeliveryStatus.PREPARED_EXTRACTED_TEXT,
                AttachmentDeliveryStatus.PREPARED_LOCAL_VISION,
                AttachmentDeliveryStatus.REJECTED_WITH_REASON -> false
            }
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
        localVisionReady: Boolean = false,
        capabilitySupported: Boolean = false,
        frameSamplingAvailable: Boolean = false,
    ): AttachmentResolution {
        val normalized = contentType.lowercase()
        return when {
            nativeInlineReady -> AttachmentResolution(attachmentId, AttachmentTransport.NATIVE_INLINE)
            extractedTextAvailable -> AttachmentResolution(attachmentId, AttachmentTransport.EXTRACTED_TEXT)
            (normalized.startsWith("image/") || normalized == "image") && localVisionReady ->
                AttachmentResolution(attachmentId, AttachmentTransport.LOCAL_VISION)
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
