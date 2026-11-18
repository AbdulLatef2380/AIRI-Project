package com.airi.assistant.domain.customskill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSkillApprovalPolicyTest {
    @Test
    fun readOnlyCustomSkillsDoNotNeedMutationApproval() {
        assertFalse(CustomSkillApprovalPolicy.requiresExplicitApproval(SkillPermission.READ_ONLY))
    }

    @Test
    fun writeAndExternalCallSkillsRequireExplicitApproval() {
        assertTrue(CustomSkillApprovalPolicy.requiresExplicitApproval(SkillPermission.WRITE))
        assertTrue(CustomSkillApprovalPolicy.requiresExplicitApproval(SkillPermission.EXTERNAL_CALL))
    }
}
