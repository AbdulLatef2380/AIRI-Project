package com.airi.desktop

import com.airi.core.attachments.AttachmentPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

data class DesktopAttachment(
    val id: String,
    val displayName: String,
    val contentType: AttachmentPolicy.ContentType,
    val sizeBytes: Long,
    val storedFileName: String
)

sealed interface DesktopAttachmentResult {
    data class Accepted(val attachment: DesktopAttachment) : DesktopAttachmentResult
    data class Rejected(val reason: String) : DesktopAttachmentResult
}

class DesktopAttachmentStore(
    private val storageDirectory: Path = Path.of(System.getProperty("user.home"), ".airi-desktop", "attachments")
) {
    fun stage(source: Path, currentAttachmentCount: Int): DesktopAttachmentResult {
        if (currentAttachmentCount >= AttachmentPolicy.MAX_ATTACHMENTS_PER_MESSAGE) {
            return DesktopAttachmentResult.Rejected("attachment_limit_reached")
        }
        if (!Files.isRegularFile(source)) return DesktopAttachmentResult.Rejected("not_a_regular_file")

        val sizeBytes = runCatching { Files.size(source) }.getOrElse {
            return DesktopAttachmentResult.Rejected("unreadable_file")
        }
        val displayName = AttachmentPolicy.normalizedDisplayName(source.fileName?.toString())
        val mimeType = runCatching { Files.probeContentType(source) }.getOrNull()
        val contentType = AttachmentPolicy.contentType(mimeType, displayName)
        when (AttachmentPolicy.validateSize(sizeBytes, contentType)) {
            AttachmentPolicy.ValidationResult.Accepted -> Unit
            AttachmentPolicy.ValidationResult.TooLarge -> return DesktopAttachmentResult.Rejected("attachment_too_large")
            AttachmentPolicy.ValidationResult.TextTooLarge -> return DesktopAttachmentResult.Rejected("text_attachment_too_large")
        }

        val id = UUID.randomUUID().toString()
        val suffix = displayName.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
        val storedFileName = buildString {
            append(id)
            if (suffix != null) append('.').append(suffix.lowercase())
        }
        val root = storageDirectory.toAbsolutePath().normalize()
        val target = root.resolve(storedFileName).normalize()
        if (!target.startsWith(root)) {
            return DesktopAttachmentResult.Rejected("invalid_storage_target")
        }

        return runCatching {
            Files.createDirectories(root)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            DesktopAttachmentResult.Accepted(
                DesktopAttachment(id, displayName, contentType, sizeBytes, storedFileName)
            )
        }.getOrElse {
            runCatching { Files.deleteIfExists(target) }
            DesktopAttachmentResult.Rejected("attachment_copy_failed")
        }
    }

    fun delete(attachment: DesktopAttachment) {
        val root = storageDirectory.toAbsolutePath().normalize()
        val target = root.resolve(attachment.storedFileName).normalize()
        if (target.startsWith(root)) {
            runCatching { Files.deleteIfExists(target) }
        }
    }
}
