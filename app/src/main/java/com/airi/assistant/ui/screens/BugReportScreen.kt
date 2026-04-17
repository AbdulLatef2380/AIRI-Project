package com.airi.assistant.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.reporting.BugReportManager
import com.airi.assistant.reporting.BugReportRequest
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val bugReportManager = remember(context.applicationContext) {
        BugReportManager(context.applicationContext)
    }

    var description by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isLoading) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.report_bug),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.BugReport,
                    contentDescription = null,
                    tint = CosmicAccent,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.report_bug_title),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = stringResource(R.string.report_bug_subtitle),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = {
                    description = it
                    validationError = null
                },
                label = { Text(stringResource(R.string.bug_description_required)) },
                placeholder = { Text(stringResource(R.string.bug_description_placeholder)) },
                minLines = 5,
                maxLines = 9,
                enabled = !isLoading,
                isError = validationError != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = bugReportFieldColors()
            )

            validationError?.let {
                Text(
                    text = it,
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            OutlinedTextField(
                value = steps,
                onValueChange = { steps = it },
                label = { Text(stringResource(R.string.steps_to_reproduce_optional)) },
                placeholder = { Text(stringResource(R.string.steps_to_reproduce_placeholder)) },
                minLines = 4,
                maxLines = 8,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = bugReportFieldColors()
            )

            Button(
                onClick = {
                    val trimmed = description.trim()
                    if (trimmed.isBlank()) {
                        validationError = context.getString(R.string.bug_description_empty_error)
                        return@Button
                    }

                    isLoading = true
                    scope.launch {
                        val result = bugReportManager.createEmailIntent(
                            BugReportRequest(
                                description = trimmed,
                                stepsToReproduce = steps,
                                currentScreenName = "BugReportScreen",
                                lastUserAction = "Settings > Report a Bug > Submit"
                            )
                        )

                        result
                            .onSuccess { intent ->
                                try {
                                    context.startActivity(intent)
                                    snackbarHost.showSnackbar(context.getString(R.string.bug_report_email_opened))
                                } catch (e: ActivityNotFoundException) {
                                    snackbarHost.showSnackbar(context.getString(R.string.bug_report_no_email_app))
                                } catch (e: Exception) {
                                    snackbarHost.showSnackbar(e.localizedMessage ?: context.getString(R.string.bug_report_failed))
                                }
                            }
                            .onFailure { error ->
                                snackbarHost.showSnackbar(error.localizedMessage ?: context.getString(R.string.bug_report_failed))
                            }

                        isLoading = false
                    }
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CosmicAccent,
                    contentColor = Color.Black,
                    disabledContainerColor = CosmicAccent.copy(alpha = 0.35f),
                    disabledContentColor = Color.Black.copy(alpha = 0.45f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(R.string.collecting_diagnostics), fontWeight = FontWeight.Bold)
                } else {
                    Text(stringResource(R.string.submit_bug_report), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun bugReportFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CosmicAccent,
    unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
    errorBorderColor = Color(0xFFFF6B6B),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.45f),
    focusedLabelColor = CosmicAccent,
    unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
    cursorColor = CosmicAccent
)