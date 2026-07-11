package com.airi.assistant.ui.screens

import com.airi.assistant.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.integrations.github.GithubService
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.launch

/**
 * Task 6.2 – Git Repository Browser.
 * Lists repos, branches, and commit history via GithubService.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitRepositoryScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val service = remember { GithubService(SecureStorage(context)) }

    var repos     by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected  by remember { mutableStateOf<String?>(null) }
    var branches  by remember { mutableStateOf<List<String>>(emptyList()) }
    var commits   by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading   by remember { mutableStateOf(false) }
    var error     by remember { mutableStateOf<String?>(null) }

    fun loadRepos() {
        loading = true; error = null
        scope.launch {
            val result = service.getRepos(20)
            loading = false
            if (result.success) {
                repos = result.data.lines().filter { it.isNotBlank() }
            } else {
                error = result.error ?: "Unknown error"
            }
            }
        }
    }

    fun loadBranchesAndCommits(repoFullName: String) {
        val parts = repoFullName.split("/")
        if (parts.size < 2) return
        val owner = parts[0]; val repo = parts[1]
        selected = repoFullName; loading = true
        scope.launch {
            val br = service.listBranches(owner, repo)
            val cm = service.getCommitHistory(owner, repo)
            branches = if (br.success) br.data.split(", ") else emptyList()
            commits = if (cm.success) cm.data.lines().filter { it.isNotBlank() } else emptyList()
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadRepos() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected != null) selected!! else "Repositories",
                        color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) { selected = null; branches = emptyList(); commits = emptyList() } else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = AiriTheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { if (selected == null) loadRepos() else loadBranchesAndCommits(selected!!) }, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, "Refresh", tint = CosmicAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = CosmicAccent)
            } else if (error != null) {
                Column(modifier = Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.git_repo_error), color = AiriTheme.onBackground)
                    Text(error ?: "", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { loadRepos() }) { Text(stringResource(R.string.git_repo_retry)) }
                }
            } else if (selected == null) {
                // Repo list
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(repos) { repo ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AiriTheme.surface,
                            modifier = Modifier.fillMaxWidth().clickable { loadBranchesAndCommits(repo) }
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
                // Branches + commits
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (branches.isNotEmpty()) {
                        item { Text(stringResource(R.string.git_repo_branches), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent) }
                        items(branches) { branch ->
                            Surface(shape = RoundedCornerShape(8.dp), color = AiriTheme.surface) {
                                Text(branch, modifier = Modifier.padding(10.dp), fontSize = 13.sp, color = AiriTheme.onBackground)
                            }
                        }
                    }
                    if (commits.isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.git_repo_commits), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent) }
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
