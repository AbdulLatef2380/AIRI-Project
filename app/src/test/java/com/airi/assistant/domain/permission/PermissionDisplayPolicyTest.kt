package com.airi.assistant.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionDisplayPolicyTest {
    @Test
    fun doesNotTreatAnUnrequiredPermissionAsDenied() {
        val statuses = listOf(
            PermissionDisplayPolicy.status(requiredOnDevice = true, granted = true),
            PermissionDisplayPolicy.status(requiredOnDevice = true, granted = false),
            PermissionDisplayPolicy.status(requiredOnDevice = false, granted = false)
        )

        assertEquals(PermissionDisplayPolicy.Status.GRANTED, statuses[0])
        assertEquals(PermissionDisplayPolicy.Status.NOT_GRANTED, statuses[1])
        assertEquals(PermissionDisplayPolicy.Status.NOT_REQUIRED, statuses[2])
        assertEquals(2, PermissionDisplayPolicy.requiredCount(statuses))
        assertEquals(1, PermissionDisplayPolicy.grantedRequiredCount(statuses))
    }
}
