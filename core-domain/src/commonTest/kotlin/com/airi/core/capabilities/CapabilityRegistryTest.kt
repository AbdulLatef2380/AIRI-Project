package com.airi.core.capabilities

import com.airi.core.models.ModelAvailability
import com.airi.core.models.ModelDescriptor
import com.airi.core.models.ModelExecutionMode
import com.airi.core.models.ModelRegistry
import com.airi.core.models.ModelSelectionResult
import com.airi.core.skills.AiriPlatform
import com.airi.core.skills.SkillAvailability
import com.airi.core.skills.SkillDescriptor
import com.airi.core.skills.SkillRegistry
import com.airi.core.skills.SkillSelectionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CapabilityRegistryTest {

    @Test
    fun `model registry rejects unavailable selection and keeps a deterministic ready default`() {
        val models = listOf(
            model(id = "z-local", displayName = "Zulu local", availability = ModelAvailability.UNAVAILABLE),
            model(id = "a-ready", displayName = "Alpha remote", availability = ModelAvailability.READY)
        )

        val rejected = ModelRegistry.select(models, "z-local")

        assertIs<ModelSelectionResult.Rejected>(rejected)
        assertEquals("desktop_adapter_required", rejected.reason)
        assertEquals("a-ready", ModelRegistry.defaultReady(models)?.id)
    }

    @Test
    fun `model registry rejects unknown id and returns no default when none is ready`() {
        val models = listOf(
            model(id = "blocked", displayName = "Blocked", availability = ModelAvailability.REQUIRES_CONFIGURATION)
        )

        val rejected = ModelRegistry.select(models, "missing")

        assertIs<ModelSelectionResult.Rejected>(rejected)
        assertEquals("unknown_model", rejected.reason)
        assertNull(ModelRegistry.defaultReady(models))
    }

    @Test
    fun `skill registry blocks Android only skill on Desktop`() {
        val skills = listOf(
            skill(
                id = "calendar",
                displayName = "Calendar",
                platforms = setOf(AiriPlatform.ANDROID),
                availability = SkillAvailability.READY
            )
        )

        val rejected = SkillRegistry.select(skills, "calendar", AiriPlatform.DESKTOP)

        assertIs<SkillSelectionResult.Rejected>(rejected)
        assertEquals("unsupported_platform", rejected.reason)
    }

    @Test
    fun `skill registry rejects unavailable skill and returns stable matching order`() {
        val skills = listOf(
            skill(
                id = "z-code",
                displayName = "Zulu code",
                platforms = setOf(AiriPlatform.DESKTOP),
                availability = SkillAvailability.UNAVAILABLE,
                tags = setOf("code")
            ),
            skill(
                id = "a-code",
                displayName = "Alpha code",
                platforms = setOf(AiriPlatform.DESKTOP),
                availability = SkillAvailability.READY,
                tags = setOf("code")
            )
        )

        val rejected = SkillRegistry.select(skills, "z-code", AiriPlatform.DESKTOP)
        val matches = SkillRegistry.matching(skills, "review code", AiriPlatform.DESKTOP)

        assertIs<SkillSelectionResult.Rejected>(rejected)
        assertEquals("desktop_adapter_required", rejected.reason)
        assertEquals(listOf("a-code", "z-code"), matches.map { it.id })
    }

    private fun model(
        id: String,
        displayName: String,
        availability: ModelAvailability
    ) = ModelDescriptor(
        id = id,
        displayName = displayName,
        executionMode = ModelExecutionMode.LOCAL,
        availability = availability,
        unavailableReason = if (availability == ModelAvailability.READY) null else "desktop_adapter_required"
    )

    private fun skill(
        id: String,
        displayName: String,
        platforms: Set<AiriPlatform>,
        availability: SkillAvailability,
        tags: Set<String> = emptySet()
    ) = SkillDescriptor(
        id = id,
        displayName = displayName,
        intentTags = tags,
        supportedPlatforms = platforms,
        availability = availability,
        unavailableReason = if (availability == SkillAvailability.READY) null else "desktop_adapter_required"
    )
}
