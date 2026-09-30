package com.airi.assistant.ai.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInvocationAccessPolicyTest {

    @Test
    fun disabledSkillIsRejectedBeforeExecution() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(enabled = false),
            context = SkillContext(),
            hasPermission = { true }
        )

        assertDeny(decision, SkillInvocationAccessPolicy.DenyReason.DISABLED)
    }

    @Test
    fun missingDeclaredPermissionIsRejected() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(requiredPermissions = listOf("android.permission.READ_EXTERNAL_STORAGE")),
            context = SkillContext(),
            hasPermission = { false }
        )

        assertDeny(decision, SkillInvocationAccessPolicy.DenyReason.MISSING_PERMISSION)
    }

    @Test
    fun officialManifestPermissionIsEnforcedWhenLegacyInstanceOmitsIt() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(id = "calendar_events", official = true),
            context = SkillContext(),
            hasPermission = { false }
        )

        assertDeny(decision, SkillInvocationAccessPolicy.DenyReason.MISSING_PERMISSION)
    }

    @Test
    fun connectorBoundSkillIsRejectedWhenConnectorIsUnhealthy() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(requiredConnectors = listOf("notion")),
            context = SkillContext(),
            hasPermission = { true },
            isConnectorHealthy = { false }
        )

        assertDeny(decision, SkillInvocationAccessPolicy.DenyReason.CONNECTOR_UNHEALTHY)
    }

    @Test
    fun officialManifestConnectorDependencyIsEnforcedAndDefaultsClosed() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(id = "drive_search", official = true),
            context = SkillContext(),
            hasPermission = { true }
        )
        assertDeny(decision, SkillInvocationAccessPolicy.DenyReason.CONNECTOR_UNHEALTHY)
    }

    @Test
    fun officialSkillWithoutManifestIsRejected() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(id = "missing_official_manifest", official = true),
            context = SkillContext(),
            hasPermission = { true }
        )
        assertDeny(decision, SkillInvocationAccessPolicy.DenyReason.INVALID_MANIFEST)
    }

    @Test
    fun declaredModelAccessIsRejectedWhenModelBridgeIsMissing() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(modelAccess = SkillModelAccess.CHAT),
            context = SkillContext(),
            hasPermission = { true }
        )
        assertDeny(decision, SkillInvocationAccessPolicy.DenyReason.MODEL_UNAVAILABLE)
    }

    @Test
    fun memorySkillIsRejectedWhenMemoryIsUnavailable() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(memoryAccess = SkillMemoryAccess.READ_ONLY),
            context = SkillContext(),
            hasPermission = { true }
        )

        assertDeny(decision, SkillInvocationAccessPolicy.DenyReason.MEMORY_UNAVAILABLE)
    }

    @Test
    fun skillWithoutRestrictedCapabilitiesIsAllowed() {
        val decision = SkillInvocationAccessPolicy.authorize(
            skill = TestSkill(),
            context = SkillContext(sessionId = "session-1"),
            hasPermission = { true }
        )

        assertTrue(decision is SkillInvocationAccessPolicy.Decision.Allow)
        val allowed = decision as SkillInvocationAccessPolicy.Decision.Allow
        assertEquals("session-1", allowed.context.sessionId)
    }

    private fun assertDeny(
        decision: SkillInvocationAccessPolicy.Decision,
        expectedReason: SkillInvocationAccessPolicy.DenyReason
    ) {
        assertTrue(decision is SkillInvocationAccessPolicy.Decision.Deny)
        assertEquals(expectedReason, (decision as SkillInvocationAccessPolicy.Decision.Deny).reason)
    }

    private class TestSkill(
        private val enabled: Boolean = true,
        private val id: String = "test_skill",
        private val official: Boolean = false,
        override val requiredPermissions: List<String> = emptyList(),
        override val requiredConnectors: List<String> = emptyList(),
        override val memoryAccess: SkillMemoryAccess = SkillMemoryAccess.NONE,
        override val modelAccess: SkillModelAccess = SkillModelAccess.NONE
    ) : AiriSkill {
        override val skillId: String = id
        override val isOfficial: Boolean = official
        override val name: String = id
        override val description: String = "Test-only skill"
        override val isEnabled: Boolean = enabled

        override fun score(input: String, context: SkillContext): Int = 0

        override suspend fun execute(params: Map<String, Any>): SkillResult =
            error("Execution is not used by policy tests")
    }
}
