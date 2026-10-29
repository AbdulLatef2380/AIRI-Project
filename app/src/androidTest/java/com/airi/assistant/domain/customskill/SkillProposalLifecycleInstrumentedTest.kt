package com.airi.assistant.domain.customskill

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airi.assistant.ai.skills.SkillManifest
import com.airi.assistant.ai.skills.SkillRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillProposalLifecycleInstrumentedTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun rejectedProposalIsRemovedAndNeverPersistedAsSkill() {
        val store = SkillProposalStore(context)
        val repository = CustomSkillRepository(context)
        val proposal = store.create(sampleSkill("reject"), sampleManifest("reject"), ProposalSource.AIRI_GENERATED)

        assertNotNull(store.get(proposal.id))
        assertTrue(store.pending().any { it.id == proposal.id })
        assertNull(repository.getSkillById(proposal.skill.id))

        assertTrue(store.reject(proposal.id))
        assertNull(store.get(proposal.id))
        assertFalse(store.pending().any { it.id == proposal.id })
        assertNull(repository.getSkillById(proposal.skill.id))
    }

    @Test
    fun acceptedProposalIsPersistedAndActivatedOnlyAfterExplicitAccept() {
        val store = SkillProposalStore(context)
        val repository = CustomSkillRepository(context)
        val registry = SkillRegistry(context)
        val proposal = store.create(sampleSkill("accept"), sampleManifest("accept"), ProposalSource.AIRI_GENERATED)

        assertNull(repository.getSkillById(proposal.skill.id))
        assertFalse(registry.isSkillEnabled(proposal.skill.id))

        val accepted = store.accept(proposal.id) { candidate ->
            registry.registerDynamicFromManifest(
                manifest = candidate.manifest,
                endpoint = candidate.skill.config.endpoint,
                method = candidate.skill.config.method,
                bodyTemplate = candidate.skill.config.bodyTemplate,
                headers = candidate.skill.config.headers,
                type = candidate.skill.type,
                createdAt = candidate.skill.createdAt,
            )
        }

        assertTrue(accepted)
        assertNotNull(repository.getSkillById(proposal.skill.id))
        assertTrue(registry.isSkillEnabled(proposal.skill.id))
        assertTrue(store.get(proposal.id)?.status == ProposalStatus.PERSISTED)
        assertTrue(store.pending().none { it.id == proposal.id })
    }

    private fun sampleSkill(suffix: String) = CustomSkill(
        id = "proposal_test_$suffix",
        name = "Proposal Test $suffix",
        description = "A validated test skill proposal for lifecycle verification.",
        type = SkillType.API,
        config = SkillConfig(
            endpoint = "https://example.com/api/$suffix",
            method = "POST",
            bodyTemplate = "{\"input\":\"{{input}}\"}",
        ),
        isPremium = false,
        createdAt = System.currentTimeMillis(),
    )

    private fun sampleManifest(suffix: String) = SkillManifest(
        id = "proposal_test_$suffix",
        name = "Proposal Test $suffix",
        description = "A validated test skill proposal for lifecycle verification.",
        version = "1.0.0",
        author = "AIRI test",
        endpoint = "https://example.com/api/$suffix",
        requiresConfirmation = true,
    )
}
