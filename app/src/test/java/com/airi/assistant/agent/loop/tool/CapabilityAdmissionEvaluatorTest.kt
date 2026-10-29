package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.CapabilityIntentDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityAdmissionEvaluatorTest {
    private val github = RuntimeToolContract.connector(
        schema = ToolSchema("connector_github_list_repos", "List GitHub repositories"),
        available = true,
        capabilityId = "github",
    )

    @Test
    fun requestedConnectorFailureIsFailedAndNeverDegradedSilently() {
        val intent = CapabilityIntentDetector.detect("اعرض مستودعاتي في GitHub")
        val catalog = RuntimeToolCatalog.assemble(
            builtins = emptyList(),
            skills = emptyList(),
            connectors = listOf(github),
            intent = intent,
            requestText = "اعرض مستودعاتي في GitHub",
        )
        val evaluation = CapabilityAdmissionEvaluator.evaluate(
            catalog = catalog,
            intent = intent,
            failures = listOf(
                CapabilitySourceFailure("connectors", "catalog_build", "boom", "IllegalStateException")
            ),
        )

        assertTrue(evaluation.result is CapabilityAssemblyResult.Failed)
        assertEquals(Availability.READY, evaluation.decisions.single().status)
        assertTrue(evaluation.decisions.single().requested)
    }

    @Test
    fun unrelatedSourceFailureIsDegradedWithExplicitEvidence() {
        val intent = CapabilityIntentDetector.detect("كم الساعة الآن؟")
        val catalog = RuntimeToolCatalog.assemble(
            builtins = listOf(BuiltinTools.CURRENT_TIME),
            skills = emptyList(),
            connectors = emptyList(),
            intent = intent,
            requestText = "كم الساعة الآن؟",
        )
        val evaluation = CapabilityAdmissionEvaluator.evaluate(
            catalog = catalog,
            intent = intent,
            failures = listOf(
                CapabilitySourceFailure("skills", "schema_build", "boom", "IOException")
            ),
            skillsRequested = false,
        )

        assertTrue(evaluation.result is CapabilityAssemblyResult.Degraded)
        assertTrue(evaluation.decisions.any { it.capabilityId == "current_time" && it.requested })
    }

    @Test
    fun unavailableRequestedConnectorGetsActionableAdmissionDecision() {
        val intent = CapabilityIntentDetector.detect("اعرض مستودعاتي في GitHub")
        val unavailable = RuntimeToolContract.connector(
            schema = github.schema,
            available = false,
            capabilityId = "github",
        )
        val catalog = RuntimeToolCatalog.assemble(
            builtins = emptyList(),
            skills = emptyList(),
            connectors = listOf(unavailable),
            intent = intent,
            requestText = "اعرض مستودعاتي في GitHub",
        )
        val evaluation = CapabilityAdmissionEvaluator.evaluate(catalog, intent)
        val decision = evaluation.decisions.single()

        assertEquals(Availability.DISCONNECTED, decision.status)
        assertEquals(NextAction.CONNECT, decision.nextAction)
        assertTrue(decision.requested)
        assertTrue(!decision.executable)
    }
}
