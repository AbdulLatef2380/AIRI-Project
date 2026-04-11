package com.airi.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.airi.assistant.ui.components.StarBackground
import com.airi.assistant.ui.screens.ChatScreen
import com.airi.assistant.ui.screens.LoginScreen
import com.airi.assistant.ui.screens.WelcomeScreen
import com.airi.assistant.ui.theme.AIRITheme

/**
 * Navigation state
 */
enum class Screen {
    WELCOME,
    LOGIN,
    CHAT
}

/**
 * Root App Composable - Navigation Controller
 * 
 * Manages navigation between Welcome → Login → Chat screens
 */
@Composable
fun AiriApp() {
    var currentScreen by remember { mutableStateOf(Screen.WELCOME) }

    AIRITheme {
        Box(modifier = Modifier.fillMaxSize()) {
            // Star background runs behind all screens
            StarBackground()

            // Current screen
            when (currentScreen) {
                Screen.WELCOME -> {
                    WelcomeScreen(
                        onStart = { currentScreen = Screen.LOGIN }
                    )
                }
                Screen.LOGIN -> {
                    LoginScreen(
                        onLoginSuccess = { currentScreen = Screen.CHAT }
                    )
                }
                Screen.CHAT -> {
                    ChatScreen()
                }
            }
        }
    }
}
