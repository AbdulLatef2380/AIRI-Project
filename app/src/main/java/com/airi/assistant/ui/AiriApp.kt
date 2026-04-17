package com.airi.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airi.assistant.ui.components.StarBackground
import com.airi.assistant.ui.screens.AgentControlScreen
import com.airi.assistant.ui.screens.AgentLogsScreen
import com.airi.assistant.ui.screens.AgentTraceDetailScreen
import com.airi.assistant.ui.screens.ChatScreen
import com.airi.assistant.ui.screens.HistoryScreen
import com.airi.assistant.ui.screens.IntegrationsScreen
import com.airi.assistant.ui.screens.LoginScreen
import com.airi.assistant.ui.screens.MemoryScreen
import com.airi.assistant.ui.screens.ModelSettingsScreen
import com.airi.assistant.ui.screens.PrivacyPolicyScreen
import com.airi.assistant.ui.screens.ProfileScreen
import com.airi.assistant.ui.screens.SettingsScreen
import com.airi.assistant.ui.screens.TermsScreen
import com.airi.assistant.ui.screens.WelcomeScreen
import com.airi.assistant.ui.theme.AIRITheme
import com.airi.assistant.ui.viewmodel.AgentViewModel
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException

object AiriRoute {
    const val WELCOME             = "screen_welcome"
    const val LOGIN               = "screen_login"
    const val CHAT                = "screen_chat"
    const val HISTORY             = "screen_history"
    const val MODELS              = "screen_models"
    const val SETTINGS            = "screen_settings"
    const val MEMORY              = "screen_memory"
    const val INTEGRATIONS        = "screen_integrations"
    const val PROFILE             = "screen_profile"
    const val AGENT_CONTROL       = "screen_agent_control"
    const val AGENT_LOGS          = "screen_agent_logs"
    const val AGENT_TRACE_DETAIL  = "screen_agent_trace_detail"
    const val PRIVACY_POLICY      = "screen_privacy_policy"
    const val TERMS               = "screen_terms"
}

private fun mapAuthError(exception: Exception?): String {
    if (exception == null) return "Authentication failed"
    if (exception is FirebaseAuthException) {
        return when (exception.errorCode) {
            "ERROR_WRONG_PASSWORD"         -> "Incorrect password. Please try again."
            "ERROR_USER_NOT_FOUND"         -> "No account found with this email."
            "ERROR_EMAIL_ALREADY_IN_USE"   -> "This email is already registered. Sign in instead."
            "ERROR_INVALID_EMAIL"          -> "Please enter a valid email address."
            "ERROR_WEAK_PASSWORD"          -> "Password must be at least 6 characters."
            "ERROR_USER_DISABLED"          -> "This account has been disabled. Contact support."
            "ERROR_TOO_MANY_REQUESTS"      -> "Too many attempts. Please wait and try again."
            "ERROR_NETWORK_REQUEST_FAILED" -> "No internet connection. Check your network."
            "ERROR_INVALID_CREDENTIAL"     -> "Incorrect email or password."
            else -> exception.localizedMessage ?: "Authentication failed"
        }
    }
    val msg = exception.localizedMessage ?: ""
    return when {
        msg.contains("network", true) || msg.contains("timeout", true) ->
            "No internet connection. Check your network."
        else -> msg.ifBlank { "Authentication failed" }
    }
}

@Composable
fun AiriApp() {
    val navController        = rememberNavController()
    val firebaseAuth         = remember { FirebaseAuth.getInstance() }
    val chatViewModel: ChatViewModel = viewModel()
    val agentViewModel: AgentViewModel = viewModel()

    val currentUser = firebaseAuth.currentUser
    val startDest = if (currentUser != null && currentUser.isEmailVerified) {
        AiriRoute.CHAT
    } else if (currentUser != null && !currentUser.isEmailVerified && currentUser.providerData.any { it.providerId == "google.com" }) {
        AiriRoute.CHAT
    } else {
        AiriRoute.WELCOME
    }

    val themeMode by chatViewModel.themeMode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "light"  -> false
        "system" -> systemDark
        else     -> true
    }

    AIRITheme(darkTheme = isDark) {
        Box(modifier = Modifier.fillMaxSize()) {
            StarBackground()
            NavHost(navController = navController, startDestination = startDest) {

                composable(AiriRoute.WELCOME) {
                    WelcomeScreen(onStart = { navController.navigate(AiriRoute.LOGIN) })
                }

                composable(AiriRoute.LOGIN) {
                    LoginScreen(
                        onSignIn = { email, password, onResult ->
                            firebaseAuth.signInWithEmailAndPassword(email, password)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val user = firebaseAuth.currentUser
                                        val isGoogle = user?.providerData?.any { it.providerId == "google.com" } == true
                                        if (user != null && !user.isEmailVerified && !isGoogle) {
                                            firebaseAuth.signOut()
                                            onResult("Please verify your email first. Check your inbox for a verification link.")
                                        } else {
                                            navController.navigate(AiriRoute.CHAT) {
                                                popUpTo(AiriRoute.WELCOME) { inclusive = true }
                                                launchSingleTop = true
                                            }
                                            onResult(null)
                                        }
                                    } else {
                                        onResult(mapAuthError(task.exception))
                                    }
                                }
                        },
                        onCreateAccount = { email, password, onResult ->
                            firebaseAuth.createUserWithEmailAndPassword(email, password)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val user = firebaseAuth.currentUser
                                        user?.sendEmailVerification()
                                            ?.addOnCompleteListener {
                                                firebaseAuth.signOut()
                                                onResult("VERIFY_EMAIL_SENT:$email")
                                            }
                                            ?: run {
                                                firebaseAuth.signOut()
                                                onResult("VERIFY_EMAIL_SENT:$email")
                                            }
                                    } else {
                                        onResult(mapAuthError(task.exception))
                                    }
                                }
                        },
                        onGoogleLoginSuccess = {
                            navController.navigate(AiriRoute.CHAT) {
                                popUpTo(AiriRoute.WELCOME) { inclusive = true }
                                launchSingleTop = true
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

                composable(AiriRoute.PROFILE) {
                    ProfileScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(AiriRoute.AGENT_CONTROL) {
                    AgentControlScreen(
                        viewModel = agentViewModel,
                        onBack    = { navController.popBackStack() }
                    )
                }

                composable(AiriRoute.AGENT_LOGS) {
                    AgentLogsScreen(
                        viewModel       = agentViewModel,
                        onBack          = { navController.popBackStack() },
                        onTraceSelected = {
                            navController.navigate(AiriRoute.AGENT_TRACE_DETAIL) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(AiriRoute.AGENT_TRACE_DETAIL) {
                    AgentTraceDetailScreen(
                        viewModel = agentViewModel,
                        onBack    = { navController.popBackStack() }
                    )
                }

                composable(AiriRoute.PRIVACY_POLICY) {
                    PrivacyPolicyScreen(onBack = { navController.popBackStack() })
                }

                composable(AiriRoute.TERMS) {
                    TermsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
