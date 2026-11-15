package com.airi.assistant.ui.text

import com.airi.assistant.workspace.ArtifactManager

/** Result state for an internal artifact reference found in assistant output. */
enum class ArtifactReferenceState { RESOLVED, MISSING, INVALID_SCOPE }

data class ArtifactReferenceResolution(
    val id: String,
    val state: ArtifactReferenceState,
    val artifact: ArtifactManager.Artifact? = null,
) {
    val isUsable: Boolean get() = state == ArtifactReferenceState.RESOLVED && artifact != null
}

/** Extracts only bounded AIRI links and verifies ownership before exposing cards. */
object ArtifactReferenceResolver {
    private const val MAX_REFERENCES = 8
    private val referencePattern = Regex("airi://artifact/([A-Za-z0-9_-]{1,64})")

    fun extractIds(text: String): List<String> = referencePattern.findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .take(MAX_REFERENCES)
        .toList()

    fun resolveDetailed(
        text: String,
        projectId: String,
        artifactManager: ArtifactManager,
    ): List<ArtifactReferenceResolution> {
        if (projectId.isBlank()) {
            return extractIds(text).map { ArtifactReferenceResolution(it, ArtifactReferenceState.INVALID_SCOPE) }
        }
        return extractIds(text).map { id ->
            val artifact = artifactManager.getArtifactForProject(id, projectId)
            ArtifactReferenceResolution(
                id = id,
                state = if (artifact != null) ArtifactReferenceState.RESOLVED else ArtifactReferenceState.MISSING,
                artifact = artifact,
            )
        }
    }

    /** Compatibility projection: only owned, existing artifacts can become success cards. */
    fun resolve(
        text: String,
        projectId: String,
        artifactManager: ArtifactManager,
    ): List<ArtifactManager.Artifact> = resolveDetailed(text, projectId, artifactManager)
        .mapNotNull { it.artifact.takeIf { artifact -> it.isUsable && artifact != null } }
}
