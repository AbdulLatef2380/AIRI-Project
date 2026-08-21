package com.airi.core.attachments

object ImageAttachmentPolicy {
    const val MAX_IMAGE_DIMENSION_PX = 8_192
    const val MAX_DECODED_IMAGE_BYTES = 64L * 1024L * 1024L
    const val MAX_MODEL_IMAGE_LONGEST_SIDE_PX = 672

    sealed interface ValidationResult {
        data object Accepted : ValidationResult
        data object InvalidDimensions : ValidationResult
        data object DimensionsTooLarge : ValidationResult
        data object DecodedImageTooLarge : ValidationResult
    }

    fun validate(width: Int, height: Int, bytesPerPixel: Int = 4): ValidationResult {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0) return ValidationResult.InvalidDimensions
        if (width > MAX_IMAGE_DIMENSION_PX || height > MAX_IMAGE_DIMENSION_PX) {
            return ValidationResult.DimensionsTooLarge
        }
        val decodedBytes = width.toLong() * height.toLong() * bytesPerPixel.toLong()
        return if (decodedBytes > MAX_DECODED_IMAGE_BYTES) {
            ValidationResult.DecodedImageTooLarge
        } else {
            ValidationResult.Accepted
        }
    }
}
