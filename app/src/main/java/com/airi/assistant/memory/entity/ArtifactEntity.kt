package com.airi.assistant.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ArtifactEntity — Room entity for persisting [com.airi.assistant.workspace.ArtifactManager.Artifact]
 * metadata across process restarts.
 *
 * The file content itself is stored on disk under
 * `<filesDir>/workspace/artifacts/<sessionId>/<name>.<ext>`.
 * This table stores only the metadata (ID, path, type, timestamps) so the
 * artifact list can be reconstructed without scanning the filesystem.
 *
 * ── Part of AiriDatabase v5 migration (ask 26) ──────────────────────
 */
@Entity(
    tableName = "workspace_artifact",
    indices = [
        Index("sessionId"),
        Index("createdAtMs")
    ]
)
data class ArtifactEntity(
    @PrimaryKey
    val id:             String,
    val sessionId:      String,
    val name:           String,
    val typeName:       String,   // ArtifactType.name()
    val filePath:       String,
    val sizeBytes:      Long,
    val createdAtMs:    Long,
    val updatedAtMs:    Long,
    val version:        Int,
    val description:    String,
    val agentId:        String,
    val previewSnippet: String?
)
