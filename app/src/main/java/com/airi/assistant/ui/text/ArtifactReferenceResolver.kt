package com.airi.assistant.ui.text

import com.airi.assistant.workspace.ArtifactManager

/**
 * Extracts AIRI artifact references from assistant output without trusting
 * filesystem paths or arbitrary URLs. Ownership is checked by projectId when
 * the references are resolved against ArtifactManager.
 */
object ArtifactReferenceResolver {
    private const val MAX_REFERENCES = 8
    private val referencePattern = Regex("airi://artifact/([A-Za-z0-9_-]{1,64})")

    fun extractIds(text: String): List<String> = referencePattern.findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .take(MAX_REFERENCES)
        .toList()

    fun resolve(
        text: String,
        projectId: String,
        artifactManager: ArtifactManager,
    ): List<ArtifactManager.Artifact> {
        if (projectId.isBlank()) return emptyList()
        return extractIds(text).mapNotNull { id ->
            artifactManager.getArtifactForProject(id, projectId)
        }
    }
}
***
