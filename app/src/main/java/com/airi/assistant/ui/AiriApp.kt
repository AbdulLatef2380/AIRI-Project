package com.airi.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.airi.assistant.ui.components.StarBackground
import com.airi.assistant.ui.screens.AppInfoScreen
import com.airi.assistant.ui.screens.ChatScreen
import com.airi.assistant.ui.screens.HistoryScreen
import com.airi.assistant.ui.screens.LoginScreen
import com.airi.assistant.ui.screens.ModelSettingsScreen
import com.airi.assistant.ui.screens.SettingsScreen
import com.airi.assistant.ui.screens.TemplatesScreen
import com.airi.assistant.ui.screens.WelcomeScreen
import com.airi.assistant.ui.theme.AIRITheme

/**
 * Navigation state
 */
enum class Screen {
    WELCOME,
    LOGIN,
    CHAT,
    TEMPLATES,
    MODEL_SETTINGS,
    SETTINGS,
    HISTORY,
    APP_INFO
}

/**
 * Root App Composable - Navigation Controller
 * 
 * Manages navigation between Welcome → Login → Chat screens
 */
@Composable
fun AiriApp() {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.WELCOME) }

    AIRITheme {
        Box(modifier = Modifier.fillMaxSize()) {
            StarBackground()

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
                    ChatScreen(
                        onNavigate = { currentScreen = it },
                        onLogout = { currentScreen = Screen.LOGIN }
                    )
                }
                Screen.TEMPLATES -> {
                    TemplatesScreen(
                        onBack = { currentScreen = Screen.CHAT },
                        onOpenModelSettings = { currentScreen = Screen.MODEL_SETTINGS }
                    )
                }
                Screen.MODEL_SETTINGS -> {
                    ModelSettingsScreen(
                        onBack = { currentScreen = Screen.CHAT },
                        onOpenAppInfo = { currentScreen = Screen.APP_INFO }
                    )
                }
                Screen.SETTINGS -> {
                    SettingsScreen(
                        onBack = { currentScreen = Screen.CHAT },
                        onOpenAppInfo = { currentScreen = Screen.APP_INFO }
                    )
                }
                Screen.HISTORY -> {
                    HistoryScreen(
                        onBack = { currentScreen = Screen.CHAT }
                    )
                }
                Screen.APP_INFO -> {
                    AppInfoScreen(
                        onBack = { currentScreen = Screen.CHAT }
                    )
                }
            }
        }
    }
}
