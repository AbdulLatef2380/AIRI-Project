package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallDependencyResolverTest {
    @Test
    fun referenceBeforePriorResultIsRejected() {
        val resolver = ToolCallDependencyResolver()
        val result = resolver.resolve(mapOf("file" to "${'$'}call-1"))
        assertUnresolved(result, "${'$'}call-1")
    }

    @Test
    fun referenceAfterPriorResultIsMaterializedInBothForms() {
        val resolver = ToolCallDependencyResolver()
        resolver.registerResult("call-1", "repo-42")

        val result = resolver.resolve(
            mapOf("repository" to "${'$'}call-1", "copy" to "{{call-1}}/README.md"),
        )

        assertEquals(
            ToolCallDependencyResolver.Resolution.Resolved(
                mapOf("repository" to "repo-42", "copy" to "repo-42/README.md"),
            ),
            result,
        )
    }

    @Test
    fun unknownReferenceIsRejectedWithDependencyUnresolvedContract() {
        val resolver = ToolCallDependencyResolver()
        val result = resolver.resolve(mapOf("file" to "{{call-unknown}}"))
        assertUnresolved(result, "{{call-unknown}}")
        assertEquals("dependency_unresolved", ToolErrorCodes.DEPENDENCY_UNRESOLVED)
    }

    @Test
    fun malformedReferenceIsRejected() {
        val resolver = ToolCallDependencyResolver()
        val result = resolver.resolve(mapOf("file" to "{{call-1}"))
        assertTrue(result is ToolCallDependencyResolver.Resolution.Unresolved)
        assertEquals("malformed reference", (result as ToolCallDependencyResolver.Resolution.Unresolved).reason)
    }

    private fun assertUnresolved(result: ToolCallDependencyResolver.Resolution, reference: String) {
        assertTrue(result is ToolCallDependencyResolver.Resolution.Unresolved)
        assertEquals(reference, (result as ToolCallDependencyResolver.Resolution.Unresolved).reference)
    }
}
