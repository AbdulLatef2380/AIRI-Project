package com.airi.core.models

enum class RequestModality {
    TEXT_ONLY,
    IMAGE_ONLY,
    TEXT_AND_IMAGE
}

data class ModelInputCapabilities(
    val acceptsText: Boolean,
    val acceptsImages: Boolean
)

sealed interface MultimodalRoutingResult {
    data class Route(val modality: RequestModality) : MultimodalRoutingResult
    data class Rejected(val reason: String) : MultimodalRoutingResult
}

object MultimodalRoutingPolicy {
    fun decide(
        hasText: Boolean,
        imageCount: Int,
        capabilities: ModelInputCapabilities
    ): MultimodalRoutingResult {
        require(imageCount >= 0) { "Image count cannot be negative." }
        val modality = when {
            imageCount == 0 && hasText -> RequestModality.TEXT_ONLY
            imageCount > 0 && hasText -> RequestModality.TEXT_AND_IMAGE
            imageCount > 0 -> RequestModality.IMAGE_ONLY
            else -> return MultimodalRoutingResult.Rejected("A request must include text or at least one image.")
        }
        if (modality == RequestModality.TEXT_ONLY && !capabilities.acceptsText) {
            return MultimodalRoutingResult.Rejected("The selected model does not accept text requests.")
        }
        if (modality != RequestModality.TEXT_ONLY && !capabilities.acceptsImages) {
            return MultimodalRoutingResult.Rejected("No vision-capable model is ready for this image request.")
        }
        if (modality == RequestModality.TEXT_AND_IMAGE && !capabilities.acceptsText) {
            return MultimodalRoutingResult.Rejected("The selected vision model does not accept text with images.")
        }
        return MultimodalRoutingResult.Route(modality)
    }
}
