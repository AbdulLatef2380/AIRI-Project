package com.airi.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.auth.AuthService
import com.airi.assistant.domain.experiment.ExperimentManager
import com.airi.assistant.domain.growth.OnboardingManager
import com.airi.assistant.domain.growth.ReferralManager
import androidx.compose.foundation.background
import com.airi.assistant.ui.components.AiriBottomNavBar
import com.airi.assistant.ui.components.AiriNavTab
import com.airi.assistant.ui.screens.AIModelsSettingsScreen
import com.airi.assistant.ui.screens.AboutScreen
import com.airi.assistant.ui.screens.AgentControlScreen
import com.airi.assistant.ui.screens.AgentLogsScreen
import com.airi.assistant.ui.screens.AgentTasksScreen
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
import com.airi.assistant.ui.screens.ModelLibraryScreen
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
import com.airi.assistant.ui.screens.SkillManagerScreen
import com.airi.assistant.ui.screens.TemplatesScreen
import com.airi.assistant.ui.screens.AppInfoScreen
import com.airi.assistant.ui.screens.CreditsScreen
import com.airi.assistant.ui.screens.PermissionsScreen
import com.airi.assistant.ui.screens.UpdateScreen
import com.airi.assistant.ui.screens.VoicePersonalizationScreen
import com.airi.assistant.ui.plan.AgentPlanViewModel
import com.airi.assistant.ui.theme.AIRITheme
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.viewmodel.AgentViewModel
import com.airi.assistant.ui.viewmodel.ChatViewModel

object AiriRoute {
    const val ONBOARDING         = "screen_onboarding"
    // B-12: WELCOME route removed — was registered but had no callers
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
    const val TEMPLATES          = "screen_templates"   // B-13: was unreachable
    const val APP_INFO           = "screen_app_info"    // B-13: was unreachable
    const val PERFORMANCE        = "screen_performance"
    const val MODEL_PERFORMANCE  = "screen_model_performance"
    const val DEBUG_PANEL        = "screen_debug_panel"
    const val DEBUG_SCREEN       = "screen_debug_runtime"
    const val EXEC_DIAGNOSTICS   = "screen_exec_diagnostics"
    const val SANDBOX_WORKSPACE  = "screen_sandbox_workspace"
    const val WORKSPACE          = "screen_workspace"
    const val TERMINAL           = "screen_terminal"
    const val DEVELOPER_CENTER   = "screen_developer_center"
    const val VOICE_SETTINGS          = "screen_voice_settings"
    const val SETTINGS_GENERAL       = "screen_settings_general"
    const val SETTINGS_AI_MODELS     = "screen_settings_ai_models"
    const val SETTINGS_CUSTOMIZATION = "screen_settings_customization"
    const val SETTINGS_PRIVACY       = "screen_settings_privacy"
    const val SETTINGS_ABOUT         = "screen_settings_about"
    const val AGENT_TASKS            = "screen_agent_tasks"
    const val MODEL_LIBRARY          = "screen_model_library"
    const val CREDITS                = "screen_credits"
    const val PERMISSIONS_SCREEN     = "screen_permissions"
    const val UPDATE_SCREEN          = "screen_update"
    const val VOICE_PERSONALIZATION  = "screen_voice_personalization"

    // ── Phase 4 routes ────────────────────────────────────────────────────────
    const val ZAPIER_IFTTT           = "screen_zapier_ifttt"
    const val STRIPE_PAYMENT         = "screen_stripe_payment"
    const val BILLING_HISTORY        = "screen_billing_history"
    const val MARKETPLACE            = "screen_marketplace"
    const val COMMUNITY_SKILLS       = "screen_community_skills"

    fun skillBuilder(skillId: String = "new") = "$SKILL_BUILDER/$skillId"
}

// Routes where the bottom nav bar should appear
private val bottomNavRoutes = setOf(
    AiriRoute.CHAT,
    AiriRoute.SETTINGS,
    AiriRoute.SKILL_MANAGER,
    AiriRoute.AGENT_TASKS,
    AiriRoute.HISTORY
)

@Composable
fun AiriApp() {
    val navController                = rememberNavController()
    val authService: AuthService     = remember { ServiceLocator.authService }
    val chatViewModel: ChatViewModel = viewModel()
    val agentViewModel: AgentViewModel = viewModel()
    val planViewModel: AgentPlanViewModel = viewModel()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Chat is "active" (has messages) — bottom nav hides during active conversation
    var chatIsActive by remember { mutableStateOf(false) }

    val showBottomNav = currentRoute in bottomNavRoutes && !chatIsActive

    val startDest = when {
        authService.isSignedIn() -> AiriRoute.CHAT
        !OnboardingManager.isCompleted() -> AiriRoute.ONBOARDING
        else -> AiriRoute.LOGIN
    }

    // Map current route to selected nav tab
    val selectedTab = when (currentRoute) {
        AiriRoute.SKILL_MANAGER -> AiriNavTab.SKILLS
        AiriRoute.AGENT_TASKS   -> AiriNavTab.SCHEDULE
        AiriRoute.SETTINGS      -> AiriNavTab.SETTINGS
        AiriRoute.HISTORY       -> AiriNavTab.CHAT
        else                    -> AiriNavTab.NEW   // CHAT and others
    }

    AIRITheme {
        Scaffold(
            containerColor = CosmicBlack,
            bottomBar = {
                AiriBottomNavBar(
                    selectedTab = selectedTab,
                    visible     = showBottomNav,
                    onTabSelected = { tab ->
                        when (tab) {
                            AiriNavTab.SKILLS   -> navController.navigate(AiriRoute.SKILL_MANAGER) { launchSingleTop = true; restoreState = true }
                            AiriNavTab.SCHEDULE -> navController.navigate(AiriRoute.AGENT_TASKS)   { launchSingleTop = true; restoreState = true }
                            AiriNavTab.SETTINGS -> navController.navigate(AiriRoute.SETTINGS)      { launchSingleTop = true; restoreState = true }
                            AiriNavTab.CHAT     -> navController.navigate(AiriRoute.HISTORY)       { launchSingleTop = true; restoreState = true }
                            AiriNavTab.NEW      -> {
                                chatViewModel.clearMessages()
                                chatIsActive = false
                                navController.navigate(AiriRoute.CHAT) { launchSingleTop = true }
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CosmicBlack)
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                NavHost(navController = navController, startDestination = startDest) {

                    composable(AiriRoute.ONBOARDING) {
                        OnboardingScreen(
                            onComplete = {
                                navController.navigate(AiriRoute.LOGIN) {
                                    popUpTo(AiriRoute.ONBOARDING) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onSkip = {
                                OnboardingManager.skip()  // P0-8: persist skip so onboarding never repeats (also logs analytics)
                                navController.navigate(AiriRoute.LOGIN) {
                                    popUpTo(AiriRoute.ONBOARDING) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    // B-12: WELCOME composable removed — was unreachable

                    composable(AiriRoute.LOGIN) {
                        LoginScreen(
                            onSignIn = { email, password, onResult ->
                                authService.signIn(email, password) { error ->
                                    if (error == null) {
                                        AnalyticsService.login("email")
                                        AnalyticsService.funnelStep("open_to_login")
                                        ReferralManager.completePendingReferral(authService.currentUser()?.uid)
                                        ReferralManager.grantFirstLaunchBonus()  // B-10
                                        ExperimentManager.init(
                                            ServiceLocator.context ?: return@signIn,
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
                                        ReferralManager.grantFirstLaunchBonus()  // B-10
                                        ExperimentManager.init(
                                            ServiceLocator.context ?: return@createAccount,
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
                                ReferralManager.grantFirstLaunchBonus()  // B-10
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
                            onChatActiveChanged = { active -> chatIsActive = active },
                            onNavigate = { route ->
                                navController.navigate(route) { launchSingleTop = true }
                            },
                            onLogout = {
                                authService.signOut()
                                chatViewModel.clearMessages()
                                chatIsActive = false
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
                                navController.navigate(AiriRoute.CHAT) { launchSingleTop = true }
                            }
                        )
                    }

                    composable(AiriRoute.VOICE_SETTINGS) {
                        com.airi.assistant.ui.screens.VoiceSettingsScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToPersonalization = {
                                navController.navigate(AiriRoute.VOICE_PERSONALIZATION) { launchSingleTop = true }
                            }
                        )
                    }

                    composable(AiriRoute.VOICE_PERSONALIZATION) {
                        VoicePersonalizationScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.CREDITS) {
                        CreditsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.PERMISSIONS_SCREEN) {
                        PermissionsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.UPDATE_SCREEN) {
                        UpdateScreen(onBack = { navController.popBackStack() })
                    }

                    // ── Phase 4 screens ───────────────────────────────────────
                    composable(AiriRoute.ZAPIER_IFTTT) {
                        val zapier = com.airi.assistant.core.ServiceLocator.zapierConnector
                        val ifttt  = com.airi.assistant.core.ServiceLocator.iftttConnector
                        com.airi.assistant.ui.screens.ZapierIftttScreen(
                            zapierConnector = zapier,
                            iftttConnector  = ifttt,
                            onBack          = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.STRIPE_PAYMENT) {
                        com.airi.assistant.ui.screens.StripePaymentScreen(
                            stripeManager       = com.airi.assistant.core.ServiceLocator.stripeManager,
                            subscriptionManager = com.airi.assistant.core.ServiceLocator.subscriptionManager,
                            onBack              = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.BILLING_HISTORY) {
                        com.airi.assistant.ui.screens.BillingHistoryScreen(
                            billingHistoryStore = com.airi.assistant.core.ServiceLocator.billingHistoryStore,
                            onBack              = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.MARKETPLACE) {
                        com.airi.assistant.ui.screens.MarketplaceScreen(
                            repository = com.airi.assistant.core.ServiceLocator.marketplaceRepository,
                            onBack     = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.COMMUNITY_SKILLS) {
                        com.airi.assistant.ui.screens.CommunitySkillsScreen(
                            hub    = com.airi.assistant.core.ServiceLocator.communitySkillHub,
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
                                chatIsActive = false
                                navController.navigate(AiriRoute.LOGIN) {
                                    popUpTo(AiriRoute.CHAT) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(AiriRoute.SETTINGS_GENERAL) {
                        GeneralSettingsScreen(onBack = { navController.popBackStack() })
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
                                chatIsActive = false
                                navController.navigate(AiriRoute.LOGIN) {
                                    popUpTo(AiriRoute.CHAT) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(AiriRoute.SETTINGS_ABOUT) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.MEMORY) {
                        MemoryScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.INTEGRATIONS) {
                        IntegrationsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.CONNECTORS) {
                        ConnectorsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.PROFILE) {
                        ProfileScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.AGENT_CONTROL) {
                        AgentControlScreen(
                            viewModel = agentViewModel,
                            onBack    = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.AGENT_TASKS) {
                        AgentTasksScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToAgentControl = {
                                navController.navigate(AiriRoute.AGENT_CONTROL) { launchSingleTop = true }
                            }
                        )
                    }

                    composable(AiriRoute.MODEL_LIBRARY) {
                        ModelLibraryScreen(
                            viewModel = chatViewModel,
                            onBack    = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.AGENT_LOGS) {
                        AgentLogsScreen(
                            viewModel       = agentViewModel,
                            onBack          = { navController.popBackStack() },
                            onTraceSelected = {
                                navController.navigate(AiriRoute.AGENT_TRACE_DETAIL) { launchSingleTop = true }
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
                        ObservabilityScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.PAYWALL) {
                        PaywallScreen(
                            onBack            = { navController.popBackStack() },
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
                            onBack    = { navController.popBackStack() },
                            onCreate  = { navController.navigate(AiriRoute.skillBuilder()) },
                            onEdit    = { skillId -> navController.navigate(AiriRoute.skillBuilder(skillId)) }
                        )
                    }

                    composable(
                        route = "${AiriRoute.SKILL_BUILDER}/{skillId}",
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
                        DebugPanelScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
                    }

                    // B-13: TemplatesScreen and AppInfoScreen — now reachable
                    composable(AiriRoute.TEMPLATES) {
                        TemplatesScreen(
                            viewModel = chatViewModel,
                            onBack    = { navController.popBackStack() }
                        )
                    }

                    composable(AiriRoute.APP_INFO) {
                        AppInfoScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.DEBUG_SCREEN) {
                        DebugScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.EXEC_DIAGNOSTICS) {
                        ExecDiagnosticsScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
                    }
                    composable(AiriRoute.SANDBOX_WORKSPACE) {
                        com.airi.assistant.ui.screens.SandboxWorkspaceScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(AiriRoute.WORKSPACE) {
                        com.airi.assistant.ui.screens.WorkspaceScreen(
                            onBack       = { navController.popBackStack() },
                            onOpenChat   = {
                                navController.navigate(AiriRoute.CHAT) { launchSingleTop = true }
                            }
                        )
                    }
                    composable(AiriRoute.TERMINAL) {
                        com.airi.assistant.ui.screens.TerminalScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(AiriRoute.DEVELOPER_CENTER) {
                        com.airi.assistant.ui.screens.DeveloperCenterScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}