package com.airi.assistant.connector.app

import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMutationPolicyTest {

    @Test
    fun permitsReadActions() {
        assertTrue(GitHubMutationPolicy.evaluate("list_repos") is GitHubMutationPolicy.Decision.Allowed)
        assertTrue(GitHubMutationPolicy.evaluate("get_file") is GitHubMutationPolicy.Decision.Allowed)
        assertTrue(GitHubMutationPolicy.evaluate("search_code") is GitHubMutationPolicy.Decision.Allowed)
    }

    @Test
    fun requiresTaskApprovalForIssueCreation() {
        val decision = GitHubMutationPolicy.evaluate("create_issue")

        assertTrue(decision is GitHubMutationPolicy.Decision.RequiresTaskApproval)
    }
}
