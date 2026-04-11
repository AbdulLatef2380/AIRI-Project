package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.InputBarBackground
import com.airi.assistant.ui.theme.MessageBubbleAI
import com.airi.assistant.ui.theme.MessageBubbleUser
import com.airi.assistant.ui.theme.OverlayBackground
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ChatViewModel

/**
 * Main Chat Screen - CRITICAL INTEGRATION POINT
 * 
 * This screen connects the UI to the UnifiedCognitiveLoop through ChatViewModel
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar()
            MessageList(
                messages = viewModel.messages,
                modifier = Modifier.weight(1f)
            )
            InputBar(onSend = { input ->
                viewModel.sendMessage(input)
            })
        }

        // Agent Overlay - Shows live execution
        AgentOverlay(
            state = viewModel.agentState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * Top Bar showing agent status
 */
@Composable
fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(0.4f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("AIRI", color = CosmicAccent)
        Text("Agent Active", color = Color.Green)
    }
}

/**
 * Message List - ChatGPT-style scrolling messages
 */
@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        reverseLayout = true,
        contentPadding = PaddingValues(12.dp)
    ) {
        items(messages.reversed()) { msg ->
            MessageBubble(msg)
        }
    }
}

/**
 * Message Bubble - Glass effect with alignment
 */
@Composable
fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .background(
                    color = if (message.isUser) MessageBubbleUser else MessageBubbleAI,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                color = Color.White
            )
        }
    }
}

/**
 * Input Bar - Fixed bottom with send button
 */
@Composable
fun InputBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(InputBarBackground)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("اكتب أمرك...") },
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )

        IconButton(onClick = {
            if (text.isNotEmpty()) {
                onSend(text)
                text = ""
            }
        }) {
            Icon(
                Icons.Default.Send,
                contentDescription = "Send",
                tint = CosmicAccent
            )
        }
    }
}

/**
 * Agent Overlay - CRITICAL: Shows real-time execution
 * 
 * This displays what the agent is currently doing during execution
 */
@Composable
fun AgentOverlay(
    state: com.airi.assistant.ui.viewmodel.AgentState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.isWorking,
        modifier = modifier.padding(top = 80.dp)
    ) {
        Box(
            modifier = Modifier
                .background(OverlayBackground, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "⚡ ${state.currentAction}",
                color = CosmicAccent
            )
        }
    }
}
