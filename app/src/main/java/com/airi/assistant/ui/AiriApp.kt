package com.airi.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.auth.AuthService
import com.airi.assistant.domain.experiment.ExperimentManager
import com.airi.assistant.domain.growth.OnboardingManager
import com.airi.assistant.domain.growth.ReferralManager
import com.airi.assistant.ui.screens.AIModelsSettingsScreen
import com.airi.assistant.ui.screens.AboutScreen
import com.airi.assistant.ui.screens.AgentControlScreen
import com.airi.assistant.ui.screens.AgentLogsScreen
import com.airi.assistant.ui.screens.DebugPanelScreen
import com.airi.assistant.ui.screens.ExecDiagnosticsScreen
import com.airi.assistant.ui.debug.DebugScreen
import com.airi.assistant.ui.screens.AgentTraceDetailScreen
import com.airi.assistant.ui.screens.ChatScreen
import com.airi.assistant.ui.screens.CustomizationSettingsScreen
import com.airi.assistant.ui.screens.GeneralSettingsScreen
import com.airi.assistant.ui.screens.HistoryScreen
import com.airi.assistant.ui.screens.ConnectorsScreen
import com.airi.assistant.ui.screens.IntegrationsScreen
import com.airi.assistant.ui.screens.LoginScreen
import com.airi.assistant.ui.screens.MemoryScreen
import com.airi.assistant.ui.screens.ModelPerformanceScreen
import com.airi.assistant.ui.screens.ModelSettingsScreen
import com.airi.assistant.ui.screens.ObservabilityScreen
import com.airi.assistant.ui.screens.PerformanceScreen
import com.airi.assistant.ui.screens.OnboardingScreen
import com.airi.assistant.ui.screens.PaywallScreen
import com.airi.assistant.ui.screens.PrivacyDataSettingsScreen
import com.airi.assistant.ui.screens.ProfileScreen
import com.airi.assistant.ui.screens.ReferralScreen
import com.airi.assistant.ui.screens.SettingsScreen
import com.airi.assistant.ui.screens.SkillBuilderScreen
import com.airi.assistant.ui.screens.KnowledgeScreen
import com.airi.assistant.ui.screens.PlanningScreen
import com.airi.assistant.ui.screens.SkillManagerScreen
import com.airi.assistant.ui.screens.TaskDashboardScreen
import com.airi.assistant.ui.screens.TemplatesScreen
import com.airi.assistant.ui.screens.WelcomeScreen
import com.airi.assistant.ui.theme.AIRITheme
import com.airi.assistant.ui.theme.Surface0
import com.airi.assistant.ui.viewmodel.AgentViewModel
import com.airi.assistant.ui.viewmodel.ChatViewModel

object AiriRoute {
    const val ONBOARDING         = "screen_onboarding"
    const val WELCOME            = "screen_welcome"
    const val LOGIN              = "screen_login"
    const val CHAT               = "screen_chat"
    const val HISTORY            = "screen_history"
    const val MODELS             = "screen_models"
    const val SETTINGS           = "screen_settings"
    const val MEMORY             = "screen_memory"
    const val INTEGRATIONS       = "screen_integrations"
    const val CONNECTORS         = "screen_connectors"
    const val PROFILE            = "screen_profile"
    const val AGENT_CONTROL      = "screen_agent_control"
    const val AGENT_LOGS         = "screen_agent_logs"
    const val AGENT_TRACE_DETAIL = "screen_agent_trace_detail"
    const val OBSERVABILITY      = "screen_observability"
    const val PAYWALL            = "screen_paywall"
    const val REFERRALS          = "screen_referrals"
    const val SKILL_MANAGER      = "screen_skill_manager"
    const val SKILL_BUILDER      = "screen_skill_builder"
    const val PERFORMANCE        = "screen_performance"
    const val MODEL_PERFORMANCE  = "screen_model_performance"
    const val DEBUG_PANEL        = "screen_debug_panel"
    const val DEBUG_SCREEN       = "screen_debug_runtime"
    const val EXEC_DIAGNOSTICS   = "screen_exec_diagnostics"
    const val VOICE_SETTINGS     = "screen_voice_settings"
    const val TASK_DASHBOARD     = "screen_task_dashboard"
    const val TEMPLATES          = "screen_templates"
    const val SETTINGS_GENERAL       = "screen_settings_general"
    const val SETTINGS_AI_MODELS     = "screen_settings_ai_models"
    const val SETTINGS_CUSTOMIZATION = "screen_settings_customization"
    const val SETTINGS_PRIVACY       = "screen_settings_privacy"
    const val SETTINGS_ABOUT         = "screen_settings_about"
    const val KNOWLEDGE              = "screen_knowledge"
    const val PLANNING               = "screen_planning"
    const val VIRTUAL_WORKSPACE      = "screen_virtual_workspace"
    const val CONNECTOR_RUNTIME      = "screen_connector_runtime"
    const val DEEP_DIAGNOSTICS       = "screen_deep_diagnostics"

    fun skillBuilder(skillId: String = "new") = "$SKILL_BUILDER/$skillId"
}

private val NO_BOTTOM_NAV_ROUTES = setOf(
    AiriRoute.ONBOARDING,
    AiriRoute.WELCOME,
    AiriRoute.LOGIN,
    AiriRoute.PLANNING,
    AiriRoute.VIRTUAL_WORKSPACE,
    AiriRoute.CONNECTOR_RUNTIME,
    AiriRoute.DEEP_DIAGNOSTICS,
)

@Composable
fun AiriApp() {
    val navController                = rememberNavController()
    val authService: AuthService     = remember { ServiceLocator.authService }
    val chatViewModel: ChatViewModel = viewModel()
    val agentViewModel: AgentViewModel = viewModel()
    val startDest = when {
        authService.isSignedIn() -> AiriRoute.CHAT
        !OnboardingManager.isCompleted() -> AiriRoute.ONBOARDING
        else -> AiriRoute.LOGIN
    }

    AIRITheme {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        val showBottomNav = currentRoute != null && currentRoute !in NO_BOTTOM_NAV_ROUTES

        Scaffold(
            containerColor = Surface0,
            bottomBar = {
                if (showBottomNav) {
                    AiriBottomNav(
                        currentRoute   = currentRoute,
                        navController  = navController
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Surface0)
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                NavHost(
                    navController     = navController,
                    startDestination  = startDest,
                    modifier          = Modifier.fillMaxSize()
                ) {

                    composable(AiriRoute.ONBOARDING) {
                        OnboardingScreen(
                            onComplete = {
                                navController.navigate(AiriRoute.LOGIN) {
                                    popUpTo(AiriRoute.ONBOARDING) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onSkip = {
                                navController.navigate(AiriRoute.LOGIN) {
                                    popUpTo(AiriRoute.ONBOARDING) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(AiriRoute.WELCOME) {
                        WelcomeScreen(onStart = { navController.navigate(AiriRoute.LOGIN) })
                    }

                    composable(AiriRoute.LOGIN) {
                        LoginScreen(
                            onSignIn = { email, password, onResult ->
                                authService.signIn(email, password) { error ->
                                    if (error == null) {
                                        AnalyticsService.login("email")
                                        AnalyticsService.funnelStep("open_to_login")
                                        ReferralManager.completePendingReferral(authService.currentUser()?.uid)
                                        ExperimentManager.init(
                                            ServiceLocator.context!!,
                                            authService.currentUser()?.uid ?: "anonymous"
                                        )
                                        navController.navigate(AiriRoute.CHAT) {
                                            popUpTo(AiriRoute.LOGIN) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                    onResult(error)
                                }
                            },
                            onCreateAccount = { email, password, onResult ->
                                authService.createAccount(email, password) { error ->
                                    if (error == null) {
                                        AnalyticsService.signup("email")
                                        AnalyticsService.funnelStep("open_to_signup")
                                        ReferralManager.completePendingReferral(authService.currentUser()?.uid)
                                        ExperimentManager.init(
                                            ServiceLocator.context!!,
                                            authService.currentUser()?.uid ?: "anonymous"
                                        )
                                        navController.navigate(AiriRoute.CHAT) {
                                            popUpTo(AiriRoute.LOGIN) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                    onResult(error)
                                }
                            },
                            onGoogleLoginSuccess = {
                                AnalyticsService.login("google")
                                AnalyticsService.funnelStep("open_to_login")
                                ReferralManager.completePendingReferral(authService.currentUser()?.uid)
                                navController.navigate(AiriRoute.CHAT) {
                                    popUpTo(AiriRoute.LOGIN) { inclusive = true }
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
                                authService.signOut()
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

                    composable(AiriRoute.VOICE_SETTINGS) {
                        com.airi.assistant.ui.screens.VoiceSettingsScreen(
                            onBack = { navController.popBackStack() }
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
                                authService.signOut()
                                chatViewModel.clearMessages()
                                navController.navigate(AiriRoute.LOGIN) {
                                    popUpTo(AiriRoute.CHAT) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(AiriRoute.SETTINGS_GENERAL) {
                        GeneralSettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.SETTINGS_AI_MODELS) {
                        AIModelsSettingsScreen(
                            viewModel  = chatViewModel,
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } }
                        )
                    }

                    composable(AiriRoute.SETTINGS_CUSTOMIZATION) {
                        CustomizationSettingsScreen(
                            viewModel  = chatViewModel,
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } }
                        )
                    }

                    composable(AiriRoute.SETTINGS_PRIVACY) {
                        PrivacyDataSettingsScreen(
                            viewModel  = chatViewModel,
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
                            onLogout   = {
                                authService.signOut()
                                chatViewModel.clearMessages()
                                navController.navigate(AiriRoute.LOGIN) {
                                    popUpTo(AiriRoute.CHAT) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(AiriRoute.SETTINGS_ABOUT) {
                        AboutScreen(
                            onBack = { navController.popBackStack() }
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

                    composable(AiriRoute.CONNECTORS) {
                        ConnectorsScreen(
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
                            viewModel  = agentViewModel,
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route ->
                                navController.navigate(route) { launchSingleTop = true }
                            }
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

                    composable(AiriRoute.OBSERVABILITY) {
                        ObservabilityScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.PAYWALL) {
                        PaywallScreen(
                            onBack           = { navController.popBackStack() },
                            onPurchaseSuccess = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.REFERRALS) {
                        ReferralScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.PERFORMANCE) {
                        PerformanceScreen(
                            viewModel = chatViewModel,
                            onBack    = { navController.popBackStack() },
                            onOpenModelPerformance = { navController.navigate(AiriRoute.MODEL_PERFORMANCE) }
                        )
                    }

                    composable(AiriRoute.MODEL_PERFORMANCE) {
                        ModelPerformanceScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.SKILL_MANAGER) {
                        SkillManagerScreen(
                            onBack             = { navController.popBackStack() },
                            onCreate           = { navController.navigate(AiriRoute.skillBuilder()) },
                            onEdit             = { skillId -> navController.navigate(AiriRoute.skillBuilder(skillId)) },
                            onBrowseTemplates  = { navController.navigate(AiriRoute.TEMPLATES) { launchSingleTop = true } }
                        )
                    }

                    composable(AiriRoute.TEMPLATES) {
                        TemplatesScreen(
                            viewModel     = chatViewModel,
                            onBack        = { navController.popBackStack() },
                            onUseTemplate = { templateId ->
                                navController.navigate(AiriRoute.skillBuilder(templateId)) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(
                        route     = "${AiriRoute.SKILL_BUILDER}/{skillId}",
                        arguments = listOf(navArgument("skillId") { type = NavType.StringType })
                    ) { entry ->
                        val skillId = entry.arguments?.getString("skillId")?.takeIf { it != "new" }
                        SkillBuilderScreen(
                            skillId = skillId,
                            onBack  = { navController.popBackStack() },
                            onSaved = {
                                navController.navigate(AiriRoute.SKILL_MANAGER) {
                                    popUpTo(AiriRoute.SKILL_MANAGER) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(AiriRoute.DEBUG_PANEL) {
                        DebugPanelScreen(
                            viewModel = chatViewModel,
                            onBack    = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.DEBUG_SCREEN) {
                        DebugScreen(
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route ->
                                navController.navigate(route) { launchSingleTop = true }
                            }
                        )
                    }

                    composable(AiriRoute.EXEC_DIAGNOSTICS) {
                        ExecDiagnosticsScreen(
                            viewModel = chatViewModel,
                            onBack    = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.TASK_DASHBOARD) {
                        TaskDashboardScreen(
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route ->
                                navController.navigate(route) { launchSingleTop = true }
                            }
                        )
                    }

                    composable(AiriRoute.KNOWLEDGE) {
                        KnowledgeScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.PLANNING) {
                        PlanningScreen(
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route ->
                                navController.navigate(route) { launchSingleTop = true }
                            }
                        )
                    }

                    composable(AiriRoute.VIRTUAL_WORKSPACE) {
                        // AiriVirtualWorkspace is a backend service; surface diagnostics here
                        ExecDiagnosticsScreen(
                            viewModel = chatViewModel,
                            onBack    = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.CONNECTOR_RUNTIME) {
                        ConnectorsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.DEEP_DIAGNOSTICS) {
                        DebugPanelScreen(
                            viewModel = chatViewModel,
                            onBack    = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
