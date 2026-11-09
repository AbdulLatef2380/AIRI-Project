package com.airi.assistant.ai.agent

import com.airi.assistant.agent.loop.tool.ToolErrorCodes
import com.airi.assistant.agent.loop.tool.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFacingRepairContractTest {
    private fun request(code: String, policy: ToolSchema.SideEffectPolicy, attempt: Int = 0) =
        ModelFacingRepairContract.Request("github_get_file", code, policy, "اقرأ README", attempt)

    @Test fun invalidReadArgumentsAreRepairableAndBounded() {
        val result = ModelFacingRepairContract.buildPrompt(request(ToolErrorCodes.INVALID_ARGUMENT, ToolSchema.SideEffectPolicy.READ_ONLY))
        assertEquals(ModelFacingRepairContract.Decision.REPAIR_ARGUMENTS, result.decision)
        assertTrue(result.prompt!!.contains("اقرأ README"))
        assertEquals(ModelFacingRepairContract.Decision.STOP_FINAL,
            ModelFacingRepairContract.decide(request(ToolErrorCodes.INVALID_ARGUMENT, ToolSchema.SideEffectPolicy.READ_ONLY, 2)))
    }

    @Test fun unknownWriteOutcomeNeverRetriesWithoutIdempotency() {
        assertEquals(
            ModelFacingRepairContract.Decision.STOP_UNCERTAIN_WRITE,
            ModelFacingRepairContract.decide(request(ToolErrorCodes.EXECUTION_FAILED, ToolSchema.SideEffectPolicy.REQUIRES_USER_CONFIRMATION)),
        )
    }

    @Test fun authAndPermissionAreExplanationsNotRepairs() {
        assertEquals(ModelFacingRepairContract.Decision.EXPLAIN_AUTH,
            ModelFacingRepairContract.decide(request(ToolErrorCodes.AUTH_REQUIRED, ToolSchema.SideEffectPolicy.READ_ONLY)))
        assertEquals(ModelFacingRepairContract.Decision.EXPLAIN_PERMISSION,
            ModelFacingRepairContract.decide(request(ToolErrorCodes.PERMISSION_DENIED, ToolSchema.SideEffectPolicy.READ_ONLY)))
    }
}
