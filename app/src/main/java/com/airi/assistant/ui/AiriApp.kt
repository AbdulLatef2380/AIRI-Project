package com.airi.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airi.assistant.ui.components.StarBackground
import com.airi.assistant.ui.screens.ChatScreen
import com.airi.assistant.ui.screens.HistoryScreen
import com.airi.assistant.ui.screens.IntegrationsScreen
import com.airi.assistant.ui.screens.LoginScreen
import com.airi.assistant.ui.screens.MemoryScreen
import com.airi.assistant.ui.screens.ModelSettingsScreen
import com.airi.assistant.ui.screens.SettingsScreen
import com.airi.assistant.ui.screens.WelcomeScreen
import com.airi.assistant.ui.theme.AIRITheme
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.google.firebase.auth.FirebaseAuth

object AiriRoute {
    const val WELCOME      = "screen_welcome"
    const val LOGIN        = "screen_login"
    const val CHAT         = "screen_chat"
    const val HISTORY      = "screen_history"
    const val MODELS       = "screen_models"
    const val SETTINGS     = "screen_settings"
    const val MEMORY       = "screen_memory"
    const val INTEGRATIONS = "screen_integrations"
}

@Composable
fun AiriApp() {
    val navController  = rememberNavController()
    val firebaseAuth   = remember { FirebaseAuth.getInstance() }
    val chatViewModel: ChatViewModel = viewModel()
    val startDest = if (firebaseAuth.currentUser != null) AiriRoute.CHAT else AiriRoute.WELCOME

    AIRITheme {
        Box(modifier = Modifier.fillMaxSize()) {
            StarBackground()
            NavHost(navController = navController, startDestination = startDest) {

                composable(AiriRoute.WELCOME) {
                    WelcomeScreen(onStart = { navController.navigate(AiriRoute.LOGIN) })
                }

                composable(AiriRoute.LOGIN) {
                    LoginScreen(
                        onLogin = { email, password, onResult ->
                            firebaseAuth.signInWithEmailAndPassword(email, password)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        navController.navigate(AiriRoute.CHAT) {
                                            popUpTo(AiriRoute.WELCOME) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                        onResult(null)
                                    } else {
                                        firebaseAuth.createUserWithEmailAndPassword(email, password)
                                            .addOnCompleteListener { reg ->
                                                if (reg.isSuccessful) {
                                                    navController.navigate(AiriRoute.CHAT) {
                                                        popUpTo(AiriRoute.WELCOME) { inclusive = true }
                                                        launchSingleTop = true
                                                    }
                                                    onResult(null)
                                                } else {
                                                    onResult(reg.exception?.localizedMessage ?: "Authentication failed")
                                                }
                                            }
                                    }
                                }
                        }
                    )
                }

                composable(AiriRoute.CHAT) {
                    ChatScreen(
                        viewModel = chatViewModel,
                        onNavigate = { route ->
                            navController.navigate(route) { launchSingleTop = true }
                        },
                        onLogout = {
                            firebaseAuth.signOut()
                            chatViewModel.clearMessages()
                            navController.navigate(AiriRoute.LOGIN) {
                                popUpTo(AiriRoute.CHAT) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(AiriRoute.MODELS) {
                    ModelSettingsScreen(
                        viewModel = chatViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(AiriRoute.HISTORY) {
                    HistoryScreen(
                        viewModel = chatViewModel,
                        onBack = { navController.popBackStack() },
                        onSessionSelected = {
                            navController.navigate(AiriRoute.CHAT) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(AiriRoute.SETTINGS) {
                    SettingsScreen(
                        viewModel = chatViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigate = { route ->
                            navController.navigate(route) { launchSingleTop = true }
                        },
                        onLogout = {
                            firebaseAuth.signOut()
                            chatViewModel.clearMessages()
                            navController.navigate(AiriRoute.LOGIN) {
                                popUpTo(AiriRoute.CHAT) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(AiriRoute.MEMORY) {
                    MemoryScreen(
                        viewModel = chatViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(AiriRoute.INTEGRATIONS) {
                    IntegrationsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
