package com.airi.assistant.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.launch

/**
 * Git repository browser backed by the same ConnectorRuntimeManager used by
 * AgentLoop. The screen no longer reads a second credential-storage service
 * path, so connection state, access profiles, health checks, and errors are
 * identical in the UI and agent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitRepositoryScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val runtime = remember { ServiceLocator.connectorRuntimeManager }

    var repos by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var commits by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun outputText(result: ConnectorOutput): String? = when (result) {
        is ConnectorOutput.Success -> result.text
        is ConnectorOutput.Failure -> "${result.code}: ${result.message}"
        is ConnectorOutput.ApprovalRequired -> "approval_required: ${result.message}"
        is ConnectorOutput.Streaming -> "streaming output is not supported by this screen"
    }

    fun loadRepos() {
        loading = true
        error = null
        scope.launch {
            when (val result = runtime.execute(
                connectorId = "github",
                input = ConnectorInput(
                    action = "list_repos",
                    params = mapOf("limit" to "20"),
                ),
            )) {
                is ConnectorOutput.Success -> {
                    repos = result.text.lineSequence()
                        .filter { it.trimStart().startsWith("• ") }
                        .map { it.trim().removePrefix("• ").substringBefore(" [") }
                        .filter { it.contains("/") }
                        .toList()
                    error = null
                }
                else -> error = outputText(result)
            }
            loading = false
        }
    }

    fun loadBranchesAndCommits(repoFullName: String) {
        if (!repoFullName.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            error = "invalid_params: invalid repository owner/name"
            return
        }
        selected = repoFullName
        loading = true
        error = null
        scope.launch {
            val branchResult = runtime.execute(
                connectorId = "github",
                input = ConnectorInput(
                    action = "list_branches",
                    params = mapOf("repo" to repoFullName),
                ),
            )
            val commitResult = runtime.execute(
                connectorId = "github",
                input = ConnectorInput(
                    action = "list_commits",
                    params = mapOf("repo" to repoFullName, "branch" to "main", "limit" to "20"),
                ),
            )
            branches = (branchResult as? ConnectorOutput.Success)?.text
                ?.lines()?.filter(String::isNotBlank).orEmpty()
            commits = (commitResult as? ConnectorOutput.Success)?.text
                ?.lines()?.filter(String::isNotBlank).orEmpty()
            val failures = listOf(branchResult, commitResult).filterIsInstance<ConnectorOutput.Failure>()
            error = failures.firstOrNull()?.let { "${it.code}: ${it.message}" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadRepos() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selected ?: "Repositories",
                        color = AiriTheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) {
                            selected = null
                            branches = emptyList()
                            commits = emptyList()
                            error = null
                        } else onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = AiriTheme.onBackground)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (selected == null) loadRepos() else loadBranchesAndCommits(selected!!) },
                        enabled = !loading,
                    ) {
                        Icon(Icons.Outlined.Refresh, "Refresh", tint = CosmicAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background),
            )
        },
        containerColor = AiriTheme.background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = CosmicAccent)
            } else if (error != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.git_repo_error), color = AiriTheme.onBackground)
                    Text(error.orEmpty(), fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { if (selected == null) loadRepos() else loadBranchesAndCommits(selected!!) }) {
                        Text(stringResource(R.string.git_repo_retry))
                    }
                }
            } else if (selected == null) {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(repos) { repo ->
                        Surface(
                            shape = AIRIShapes.md,
                            color = AiriTheme.surface,
                            modifier = Modifier.fillMaxWidth().clickable { loadBranchesAndCommits(repo) },
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ForkRight, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(repo, fontSize = 14.sp, color = AiriTheme.onBackground)
                            }
                        }
                    }
                    if (repos.isEmpty()) item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.git_repo_empty), color = AiriTheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (branches.isNotEmpty()) {
                        item {
                            Text(stringResource(R.string.git_repo_branches), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent)
                        }
                        items(branches) { branch ->
                            Surface(shape = AIRIShapes.xs, color = AiriTheme.surface) {
                                Text(branch, modifier = Modifier.padding(10.dp), fontSize = 13.sp, color = AiriTheme.onBackground)
                            }
                        }
                    }
                    if (commits.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.git_repo_commits), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent)
                        }
                        items(commits) { commit ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Outlined.Commit, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(commit, fontSize = 12.sp, color = AiriTheme.onBackground, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
