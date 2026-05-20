package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit
) {
    val systemPrompt       by viewModel.systemPrompt.collectAsState()
    val responseStyleState by viewModel.responseStyle.collectAsState()
    var customInstructions by rememberSaveable { mutableStateOf(systemPrompt) }
    var responseStyle      by rememberSaveable { mutableStateOf(responseStyleState) }

    val responseStyles = listOf(
        "concise"  to stringResource(R.string.style_concise),
        "balanced" to stringResource(R.string.style_balanced),
        "detailed" to stringResource(R.string.style_detailed)
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Text("Customization", fontWeight = FontWeight.Bold, color = Color.White)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsSurface {
                SettingsCategoryHeader(
                    icon  = Icons.Outlined.Psychology,
                    title = stringResource(R.string.personalization)
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    stringResource(R.string.custom_instructions),
                    fontSize = 13.sp,
                    color    = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value         = customInstructions,
                    onValueChange = {
                        customInstructions = it
                        viewModel.setSystemPrompt(it)
                    },
                    placeholder = {
                        Text(
                            stringResource(R.string.custom_instructions_placeholder),
                            color    = Color.White.copy(alpha = 0.28f),
                            fontSize = 12.sp
                        )
                    },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White
                    )
                )
                Text(
                    stringResource(R.string.applied_next_session),
                    fontSize = 10.sp,
                    color    = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.response_style),
                    fontSize = 13.sp,
                    color    = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    responseStyles.forEach { (code, label) ->
                        val selected = responseStyle == code
                        FilterChip(
                            selected = selected,
                            onClick  = {
                                responseStyle = code
                                viewModel.setResponseStyle(code)
                            },
                            label  = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CosmicAccent.copy(alpha = 0.2f),
                                selectedLabelColor     = CosmicAccent,
                                containerColor         = Color.White.copy(alpha = 0.06f),
                                labelColor             = Color.White.copy(alpha = 0.6f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor         = Color.White.copy(alpha = 0.1f),
                                selectedBorderColor = CosmicAccent.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }

            SettingsSurface {
                SettingsCategoryHeader(
                    icon  = Icons.Outlined.Storage,
                    title = stringResource(R.string.memory)
                )
                Spacer(Modifier.height(12.dp))
                SettingsNavigationRow(
                    label    = stringResource(R.string.view_stored_memory),
                    sublabel = stringResource(R.string.browse_conversation_history)
                ) { onNavigate(AiriRoute.MEMORY) }
                Divider(
                    color    = Color.White.copy(alpha = 0.06f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                SettingsActionRow(
                    label       = stringResource(R.string.clear_all_memory),
                    sublabel    = stringResource(R.string.reset_ai_context),
                    destructive = true
                ) { viewModel.clearMemory() }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
