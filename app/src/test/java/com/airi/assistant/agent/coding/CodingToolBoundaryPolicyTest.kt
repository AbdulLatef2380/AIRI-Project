package com.airi.assistant.agent.coding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingToolBoundaryPolicyTest {
    private val policy = CodingToolBoundaryPolicy(
        CodingToolBoundaryPolicy.Bounds(
            maxFileBytes = 10,
            maxFilesPerListing = 2,
            maxSearchResults = 3,
            maxSearchQueryChars = 5,
            maxPatchBytes = 8,
            maxTerminalTimeoutMs = 100,
            maxTerminalOutputChars = 20,
        ),
    )

    @Test
    fun normalizesWorkspacePathsAndRejectsTraversal() {
        assertEquals(
            "/src/main.kt",
            (policy.readFile("/src/./main.kt") as CodingToolBoundaryPolicy.Decision.Allowed).value,
        )
        assertDenied("path_traversal") { policy.readFile("src/../secrets.txt") }
        assertDenied("path_outside_workspace") { policy.readFile("//etc/passwd") }
        assertDenied("path_invalid") { policy.readFile("C:/secret.txt") }
    }

    @Test
    fun enforcesFileListingSearchAndMutationBounds() {
        assertTrue(policy.listFiles("/src", limit = 2) is CodingToolBoundaryPolicy.Decision.Allowed)
        assertDenied("file_listing_limit_exceeded") { policy.listFiles(limit = 3) }
        assertDenied("search_query_limit_exceeded") { policy.searchFiles("abcdef") }
        assertDenied("search_result_limit_exceeded") { policy.searchFiles("main", limit = 4) }
        assertDenied("file_write_limit_exceeded") { policy.writeFile("/a.txt", contentBytes = 11) }
        assertDenied("patch_limit_exceeded") { policy.applyPatch("/a.txt", patchBytes = 9) }
    }

    @Test
    fun terminalRequiresSafeCommandWorkspaceCwdTimeoutAndOutputBounds() {
        assertTrue(
            policy.terminalExecute("grep -n main ./src", "/", 100, 20)
                is CodingToolBoundaryPolicy.Decision.Allowed,
        )
        assertDeniedPrefix("terminal_denied:") {
            policy.terminalExecute("echo hi; rm -rf .", "/", 100, 20)
        }
        assertDenied("path_traversal") {
            policy.terminalExecute("ls", "/workspace/../outside", 100, 20)
        }
        assertDenied("terminal_timeout_limit_exceeded") {
            policy.terminalExecute("ls", "/", 101, 20)
        }
        assertDenied("terminal_output_limit_exceeded") {
            policy.terminalExecute("ls", "/", 100, 21)
        }
        val approval = policy.terminalExecute("rm ./draft.txt", "/", 100, 20)
        assertTrue(approval is CodingToolBoundaryPolicy.Decision.Allowed)
        assertTrue((approval as CodingToolBoundaryPolicy.Decision.Allowed).value.requiresApproval)
    }

    private fun assertDenied(expected: String, block: () -> CodingToolBoundaryPolicy.Decision<*>) {
        val decision = block()
        assertTrue("Expected denial but got $decision", decision is CodingToolBoundaryPolicy.Decision.Denied)
        assertEquals(expected, (decision as CodingToolBoundaryPolicy.Decision.Denied).reason)
    }

    private fun assertDeniedPrefix(expected: String, block: () -> CodingToolBoundaryPolicy.Decision<*>) {
        val decision = block()
        assertTrue("Expected denial but got $decision", decision is CodingToolBoundaryPolicy.Decision.Denied)
        assertTrue((decision as CodingToolBoundaryPolicy.Decision.Denied).reason.startsWith(expected))
    }
}
