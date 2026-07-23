package com.airi.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import com.airi.assistant.ui.screens.SkillCreationWizardScreen
import com.airi.assistant.ui.screens.SkillManagerScreen
import com.airi.assistant.ui.screens.TemplatesScreen
import com.airi.assistant.ui.screens.AppInfoScreen
import com.airi.assistant.ui.screens.CreditsScreen
import com.airi.assistant.ui.screens.PermissionsScreen
import com.airi.assistant.ui.screens.ArtifactPreviewScreen
import com.airi.assistant.ui.screens.UpdateScreen
import com.airi.assistant.ui.screens.VoicePersonalizationScreen
import com.airi.assistant.ui.screens.WelcomeScreen
import com.airi.assistant.ui.screens.WorkspaceScreen
import androidx.compose.ui.platform.LocalContext
import com.airi.assistant.ui.screens.PlanningDashboardScreen
import com.airi.assistant.ui.screens.GitRepositoryScreen
import com.airi.assistant.ui.screens.SecurityScannerScreen
import com.airi.assistant.ui.screens.SecretManagerScreen
import com.airi.assistant.ui.plan.AgentPlanViewModel
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.AgentViewModel
import com.airi.assistant.ui.viewmodel.ChatViewModel

object AiriRoute {
    const val ONBOARDING         = "screen_onboarding"
    // : WELCOME route removed — was registered but had no callers
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
    const val TEMPLATES          = "screen_templates"   // : was unreachable
    const val APP_INFO           = "screen_app_info"    // : was unreachable
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
    const val SKILL_CREATION_WIZARD  = "screen_skill_creation_wizard"

    // ── Phase 2, Task 7: Artifact preview route ───────────────────────────────
    const val ARTIFACT_PREVIEW       = "screen_artifact_preview"

    /**
     * Build the artifact preview route with encoded type and content.
     * Content is truncated to [MAX_ROUTE_CONTENT_BYTES] to avoid NavController limits.
     */
    fun artifactPreview(type: String, content: String): String {
        val encoded = java.net.URLEncoder.encode(
            content.take(MAX_ROUTE_CONTENT_BYTES), "UTF-8"
        )
        return "$ARTIFACT_PREVIEW/$type/$encoded"
    }

    private const val MAX_ROUTE_CONTENT_BYTES = 8_192

    fun skillBuilder(skillId: String = "new") = "$SKILL_BUILDER/$skillId"

    // ── Phase 2 new routes (Tasks 1.6, 5.1, 5.2, 5.3, 6.2, 8.1, 9.1) ────────
    const val WELCOME              = "screen_welcome"
    const val PLANNING_DASHBOARD   = "screen_planning_dashboard"
    const val PROTOTYPE_BUILDER    = "screen_prototype_builder"
    const val WIREFRAME_BUILDER    = "screen_wireframe_builder"
    const val GIT_REPOSITORY       = "screen_git_repository"
    const val SECURITY_SCANNER     = "screen_security_scanner"
    const val SECRET_MANAGER       = "screen_secret_manager"
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
    val context                      = LocalContext.current
    val authService: AuthService     = remember { ServiceLocator.authService }
    val chatViewModel: ChatViewModel = viewModel()
    val agentViewModel: AgentViewModel = viewModel()
    val planViewModel: AgentPlanViewModel = viewModel()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Chat is "active" (has messages) — bottom nav hides during active conversation
    var chatIsActive by remember { mutableStateOf(false) }

    val showBottomNav = currentRoute in bottomNavRoutes && !chatIsActive

    // Check if the user has any usable API key configured
    // Note: local model presence is checked via SecureApiKeyStore since
    // modelController lives inside ChatViewModel, not ServiceLocator.
    fun hasAnyModel(): Boolean = runCatching {
        val keyStore = ServiceLocator.secureApiKeyStore
        com.airi.assistant.execution.CloudProvider.values().any { keyStore.hasKey(it) }
    }.getOrDefault(false)

    fun hasAnyApiKey(): Boolean = runCatching {
        val keyStore = com.airi.assistant.execution.security.SecureApiKeyStore(context)
        com.airi.assistant.execution.CloudProvider.values().any { keyStore.getKey(it) != null }
    }.getOrDefault(false)

    val startDest = when {
        !OnboardingManager.isCompleted()      -> AiriRoute.ONBOARDING
        !authService.isSignedIn()             -> AiriRoute.LOGIN
        // Route signed-in users with no model and no API key to Welcome setup screen
        !hasAnyModel() && !hasAnyApiKey()     -> AiriRoute.WELCOME
        else                                  -> AiriRoute.CHAT
    }

    // Map current route to selected nav tab
    val selectedTab = when (currentRoute) {
        AiriRoute.SKILL_MANAGER -> AiriNavTab.SKILLS
        AiriRoute.AGENT_TASKS   -> AiriNavTab.SCHEDULE
        AiriRoute.SETTINGS      -> AiriNavTab.SETTINGS
        AiriRoute.HISTORY       -> AiriNavTab.CHAT
        else                    -> AiriNavTab.NEW   // CHAT and others
    }

    AiriTheme {
        Scaffold(
            containerColor = AiriTheme.background,
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
                    .background(AiriTheme.background)
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

                    // WELCOME screen — first-time setup for users with no model/key
                    composable(AiriRoute.WELCOME) {
                        WelcomeScreen(
                            onSetupModel = {
                                navController.navigate(AiriRoute.MODELS) {
                                    launchSingleTop = true
                                }
                            },
                            onEnterApiKey = {
                                navController.navigate(AiriRoute.SETTINGS_GENERAL) {
                                    launchSingleTop = true
                                }
                            },
                            onContinueAnyway = {
                                navController.navigate(AiriRoute.CHAT) {
                                    popUpTo(AiriRoute.WELCOME) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(AiriRoute.LOGIN) {
                        LoginScreen(
                            authService = authService,
                            onSignIn = { email, password, onResult ->
                                authService.signIn(email, password) { error ->
                                    if (error == null) {
                                        AnalyticsService.login("email")
                                        AnalyticsService.funnelStep("open_to_login")
                                        ReferralManager.completePendingReferral(authService.currentUser()?.uid)
                                        ReferralManager.grantFirstLaunchBonus()  // 
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
                                        ReferralManager.grantFirstLaunchBonus()  // 
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
                                ReferralManager.grantFirstLaunchBonus()  // 
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
                            repository         = com.airi.assistant.core.ServiceLocator.marketplaceRepository,
                            onBack             = { navController.popBackStack() },
                            onNavigateToWizard = { navController.navigate(AiriRoute.SKILL_CREATION_WIZARD) }
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
                        AboutScreen(
                            onBack     = { navController.popBackStack() },
                            // : wire "Technical Details" → APP_INFO route
                            onNavigate = { route -> navController.navigate(route) }
                        )
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
                            viewModel  = agentViewModel,
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route -> navController.navigate(route) }
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

                    composable(AiriRoute.SKILL_CREATION_WIZARD) {
                        SkillCreationWizardScreen(onBack = { navController.popBackStack() })
                    }

                    composable(AiriRoute.DEBUG_PANEL) {
                        DebugPanelScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
                    }

                    // : TemplatesScreen and AppInfoScreen — now reachable
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
                            },
                            // : Wire artifact preview navigation
                            onNavigate   = { route -> navController.navigate(route) }
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

                    // ── Phase 2, Task 7: Isolated artifact preview (sandboxed WebView) ──────────
                    composable(
                        route     = "${AiriRoute.ARTIFACT_PREVIEW}/{type}/{content}",
                        arguments = listOf(
                            androidx.navigation.navArgument("type")    { type = NavType.StringType },
                            androidx.navigation.navArgument("content") { type = NavType.StringType }
                        )
                    ) { backStack ->
                        val type    = backStack.arguments?.getString("type")    ?: "CODE"
                        val encoded = backStack.arguments?.getString("content") ?: ""
                        val content = runCatching {
                            java.net.URLDecoder.decode(encoded, "UTF-8")
                        }.getOrDefault(encoded)
                        ArtifactPreviewScreen(
                            artifactType    = type,
                            artifactContent = content,
                            onBack          = { navController.popBackStack() }
                        )
                    }

                    // ── Task 5.1: Planning Dashboard ──────────────────────────
                    composable(AiriRoute.PLANNING_DASHBOARD) {
                        PlanningDashboardScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // ── Task 5.2: Prototype Builder ───────────────────────────
                    composable(AiriRoute.PROTOTYPE_BUILDER) {
                        WorkspaceScreen(
                            sessionType = "prototype",
                            onBack      = { navController.popBackStack() },
                            onNavigate  = { route -> navController.navigate(route) }
                        )
                    }

                    // ── Task 5.3: Wireframe Builder ───────────────────────────
                    composable(AiriRoute.WIREFRAME_BUILDER) {
                        WorkspaceScreen(
                            sessionType = "wireframe",
                            onBack      = { navController.popBackStack() },
                            onNavigate  = { route -> navController.navigate(route) }
                        )
                    }

                    // ── Task 6.2: Git Repository Browser ─────────────────────
                    composable(AiriRoute.GIT_REPOSITORY) {
                        GitRepositoryScreen(
                            onBack     = { navController.popBackStack() },
                            onNavigate = { route -> navController.navigate(route) }
                        )
                    }

                    // ── Task 8.1: Security Scanner ────────────────────────────
                    composable(AiriRoute.SECURITY_SCANNER) {
                        SecurityScannerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // ── Task 9.1: Secret Manager ──────────────────────────────
                    composable(AiriRoute.SECRET_MANAGER) {
                        SecretManagerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}