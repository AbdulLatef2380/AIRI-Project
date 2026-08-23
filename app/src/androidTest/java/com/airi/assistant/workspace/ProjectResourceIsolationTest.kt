package com.airi.assistant.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airi.assistant.knowledge.ProjectKnowledgeManager
import com.airi.assistant.media.MediaLibrary
import com.airi.assistant.memory.repository.MemoryManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectResourceIsolationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun projectResourcesNeverCrossProjectBoundary() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val projectA = "isolation-a-$suffix"
        val projectB = "isolation-b-$suffix"
        lateinit var knowledge: ProjectKnowledgeManager
        val files = ProjectFileManager(context, MediaLibrary(context)) { file ->
            knowledge.deleteIndexForFile(file.id)
        }
        knowledge = ProjectKnowledgeManager(context, files)
        val artifacts = ArtifactManager(context)
        val memory = MemoryManager(context)
        val memorySession = "memory-$suffix"

        val importedA = files.importFromBytes(
            projectId = projectA,
            name = "alpha.txt",
            mimeType = "text/plain",
            bytes = "alpha-only-${suffix} project A evidence".toByteArray()
        ) as ProjectFileManager.ImportResult.Imported
        val importedB = files.importFromBytes(
            projectId = projectB,
            name = "beta.txt",
            mimeType = "text/plain",
            bytes = "beta-only-${suffix} project B evidence".toByteArray()
        ) as ProjectFileManager.ImportResult.Imported

        assertEquals(listOf(importedA.file.id), files.forProject(projectA).map { it.id })
        assertEquals(listOf(importedB.file.id), files.forProject(projectB).map { it.id })

        assertEquals(ProjectKnowledgeManager.IndexStatus.INDEXED, knowledge.indexProjectFile(importedA.file.id).status)
        assertEquals(ProjectKnowledgeManager.IndexStatus.INDEXED, knowledge.indexProjectFile(importedB.file.id).status)
        assertTrue(knowledge.search(projectA, "alpha-only-$suffix").isNotEmpty())
        assertTrue(knowledge.search(projectB, "alpha-only-$suffix").isEmpty())
        assertTrue(files.delete(importedA.file.id))
        assertTrue(files.forProject(projectA).isEmpty())
        assertTrue(knowledge.search(projectA, "alpha-only-$suffix").isEmpty())
        val restoredA = files.restore(importedA.file.id)
        assertEquals(importedA.file.id, restoredA?.id)
        assertTrue(files.deletedForProject(projectA).isEmpty())
        assertEquals(ProjectFileManager.IndexState.NOT_REQUESTED, restoredA?.indexState)
        assertEquals(ProjectKnowledgeManager.IndexStatus.INDEXED, knowledge.indexProjectFile(importedA.file.id).status)

        val storedMemory = memory.storeExplicitMemory(
            MemoryManager.ExplicitMemoryRequest(
                content = "memory-alpha-$suffix",
                sessionId = memorySession,
                projectId = projectA,
                provenance = "Cross-project isolation fixture"
            )
        )
        assertTrue(storedMemory is MemoryManager.ExplicitMemoryResult.Stored)
        assertTrue(
            memory.getScopedLongTermMemories(memorySession, projectId = projectA)
                .any { entry -> entry.content == "memory-alpha-$suffix" }
        )
        assertTrue(memory.getScopedLongTermMemories(memorySession, projectId = projectB).isEmpty())

        val artifactA = artifacts.createArtifact(
            sessionId = projectA,
            name = "alpha-result",
            type = ArtifactManager.ArtifactType.TEXT,
            content = "artifact-alpha-$suffix",
            provenance = ArtifactProvenance(projectId = projectA)
        )
        val artifactB = artifacts.createArtifact(
            sessionId = projectB,
            name = "beta-result",
            type = ArtifactManager.ArtifactType.TEXT,
            content = "artifact-beta-$suffix",
            provenance = ArtifactProvenance(projectId = projectB)
        )

        assertEquals(listOf(artifactA.id), artifacts.forProject(projectA).map { it.id })
        assertEquals(listOf(artifactB.id), artifacts.forProject(projectB).map { it.id })
        assertNull(artifacts.getArtifactForProject(artifactA.id, projectB))
        assertNull(artifacts.readContentForProject(artifactA.id, projectB))
        assertTrue(artifacts.readContentForProject(artifactA.id, projectA)?.contains("artifact-alpha-$suffix") == true)

        knowledge.deleteIndexForFile(importedA.file.id)
        knowledge.deleteIndexForFile(importedB.file.id)
        assertTrue(files.delete(importedA.file.id))
        assertTrue(files.delete(importedB.file.id))
        assertTrue(files.purge(importedA.file.id))
        assertTrue(files.purge(importedB.file.id))
        artifacts.deleteArtifact(artifactA.id)
        artifacts.deleteArtifact(artifactB.id)
        memory.forgetMemory((storedMemory as MemoryManager.ExplicitMemoryResult.Stored).memoryId)
    }
}
