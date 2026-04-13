package com.airi.assistant.ui.screens

import androidx.compose.runtime.Composable
import com.airi.assistant.ui.viewmodel.ChatViewModel

@Composable
fun TemplatesScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    ModelSettingsScreen(
        viewModel = viewModel,
        onBack = onBack
    )
}
