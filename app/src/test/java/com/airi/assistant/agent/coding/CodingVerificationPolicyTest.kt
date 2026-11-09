package com.airi.assistant.agent.coding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingVerificationPolicyTest {
    private val policy = CodingVerificationPolicy(
        bounds = CodingVerificationPolicy.Bounds(
            maxCommandChars = 80,
            maxTimeoutMs = 100,
            maxOutputChars = 20,
            maxArtifactPathChars = 30,
        ),
    )

    @Test
    fun allowsOnlyWorkspaceScopedGradleTestAndBuildCommands() {
        val tests = policy.evaluate("run_tests", "./gradlew :app:testDebugUnitTest", "/workspace", 100, 20, "/reports/tests.xml")
        assertTrue(tests is CodingVerificationPolicy.Decision.Allowed)
        val request = (tests as CodingVerificationPolicy.Decision.Allowed).value
        assertEquals("/workspace", request.workingDirectory)
        assertEquals("/reports/tests.xml", request.artifactPath)

        val build = policy.evaluate("build", "./gradlew :app:assembleDebug", "/", 100, 20)
        assertTrue(build is CodingVerificationPolicy.Decision.Allowed)
    }

    @Test
    fun rejectsGenericTerminalCommandsShellSyntaxAndUnsafeTasks() {
        assertDenied("verification_command_not_allowlisted") {
            policy.evaluate("run_tests", "python -m pytest")
        }
        assertDenied("verification_shell_syntax_denied") {
            policy.evaluate("build", "./gradlew :app:assembleDebug && cat secrets")
        }
        assertDenied("verification_command_not_allowlisted") {
            policy.evaluate("build", "./gradlew clean")
        }
        assertDeniedPrefix("artifact_") {
            policy.evaluate("run_tests", "./gradlew :app:testDebugUnitTest", "/", 100, 20, "../outside.xml")
        }
    }

    @Test
    fun validatesExitCodeDurationOutputAndArtifact() {
        val passed = policy.validateResult(
            CodingVerificationResult("run_tests", 0, 100, "12 tests passed", "/reports/tests.xml"),
        )
        assertTrue(passed is CodingVerificationPolicy.Decision.Allowed)
        assertTrue((passed as CodingVerificationPolicy.Decision.Allowed).value.passed)

        val failed = policy.validateResult(CodingVerificationResult("build", 1, 10, "compile failed"))
        assertTrue(failed is CodingVerificationPolicy.Decision.Allowed)
        assertFalse((failed as CodingVerificationPolicy.Decision.Allowed).value.passed)

        assertDenied("verification_duration_exceeded") {
            policy.validateResult(CodingVerificationResult("run_tests", 0, 101, "ok"))
        }
        assertDenied("verification_output_exceeded") {
            policy.validateResult(CodingVerificationResult("run_tests", 0, 1, "x".repeat(21)))
        }
    }

    private fun assertDenied(expected: String, block: () -> CodingVerificationPolicy.Decision<*>) {
        val decision = block()
        assertTrue("Expected denial but got $decision", decision is CodingVerificationPolicy.Decision.Denied)
        assertEquals(expected, (decision as CodingVerificationPolicy.Decision.Denied).reason)
    }

    private fun assertDeniedPrefix(expected: String, block: () -> CodingVerificationPolicy.Decision<*>) {
        val decision = block()
        assertTrue("Expected denial but got $decision", decision is CodingVerificationPolicy.Decision.Denied)
        assertTrue((decision as CodingVerificationPolicy.Decision.Denied).reason.startsWith(expected))
    }
}
