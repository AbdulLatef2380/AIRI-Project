package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.domain.growth.OnboardingManager
import com.airi.assistant.domain.permission.AccessibilityServiceState
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * OnboardingScreen — production 5-page first-run flow.
 *
 * Pages:
 *   0 — Welcome & capabilities overview
 *   1 — Accessibility permission (required for agent actions)
 *   2 — Microphone permission (required for voice mode)
 *   3 — Privacy & execution mode explanation
 *   4 — Ready / Get Started
 *
 * Each permission page checks the actual grant state and shows a
 * contextual action button (Grant → Settings → Continue depending on state).
 * Users can skip individual permissions — AIRI degrades gracefully without them.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(0) }

    val micPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // : Notifications permission — required for agent task alerts on API 33+
    val notificationsPermissionState = if (android.os.Build.VERSION.SDK_INT >= 33) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else null

    // : Calendar permissions — required for CalendarTool and ProductivityAgent
    val calendarPermissionsState = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
    )

    val pages = remember {
        listOf(
            OnboardingPage(Icons.Outlined.Psychology, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
            OnboardingPage(Icons.Outlined.Accessibility, R.string.onboarding_accessibility_title, R.string.onboarding_accessibility_body, "accessibility"),
            OnboardingPage(Icons.Outlined.Mic, R.string.onboarding_microphone_title, R.string.onboarding_microphone_body, "microphone"),
            OnboardingPage(Icons.Outlined.Notifications, R.string.onboarding_notifications_title, R.string.onboarding_notifications_body, "notifications"),
            OnboardingPage(Icons.Outlined.CalendarMonth, R.string.onboarding_calendar_title, R.string.onboarding_calendar_body, "calendar"),
            OnboardingPage(Icons.Outlined.Lock, R.string.onboarding_privacy_title, R.string.onboarding_privacy_body, "privacy"),
            OnboardingPage(Icons.Outlined.CheckCircle, R.string.onboarding_ready_title, R.string.onboarding_ready_body)
        )
    }

    LaunchedEffect(Unit) { OnboardingManager.start() }

    val bg = Brush.verticalGradient(listOf(Color(0xFF050816), Color(0xFF101633), Color(0xFF050816)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(24.dp)
    ) {
        // Skip button — top right
        TextButton(
            onClick = { OnboardingManager.skip(); onSkip() },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Text(stringResource(R.string.skip), color = AiriTheme.onSurfaceVariant, fontSize = 14.sp)
        }

        // Page content
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                (slideOutHorizontally { -it } + fadeOut())
            },
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            label = "onboarding_page"
        ) { targetPage ->
            val p = pages[targetPage]
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Icon circle
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(CosmicAccent.copy(alpha = 0.12f))
                        .border(1.5.dp, CosmicAccent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(p.icon, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(44.dp))
                }

                Text(
                    text       = stringResource(p.titleRes),
                    color      = AiriTheme.onBackground,
                    fontSize   = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 34.sp,
                    textAlign  = TextAlign.Center
                )

                Text(
                    text      = stringResource(p.subtitleRes),
                    color     = AiriTheme.onSurfaceVariant,
                    fontSize  = 15.sp,
                    lineHeight = 23.sp,
                    textAlign  = TextAlign.Center
                )

                // Permission-specific action card
                when (p.permissionKey) {
                    "accessibility" -> AccessibilityPermissionCard(context)
                    "microphone"    -> MicrophonePermissionCard(micPermissionState, context)
                    "notifications" -> NotificationsPermissionCard(notificationsPermissionState)
                    "calendar"      -> CalendarPermissionCard(calendarPermissionsState)
                    "privacy"       -> PrivacyExplanationCard()
                    else            -> SocialProofStrip()
                }
            }
        }

        // Bottom navigation: dots + Continue/Get Started
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Page dots
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .width(if (index == page) 28.dp else 8.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                if (index == page) CosmicAccent else AiriTheme.onSurface.copy(alpha = 0.18f)
                            )
                    )
                }
            }

            Button(
                onClick = {
                    if (page < pages.lastIndex) {
                        page += 1
                    } else {
                        OnboardingManager.complete()
                        onComplete()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape  = AIRIShapes.lg,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CosmicAccent,
                    contentColor   = AiriTheme.background
                )
            ) {
                Text(
                    text       = stringResource(if (page < pages.lastIndex) R.string.onboarding_continue else R.string.onboarding_get_started),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = 16.sp
                )
            }
        }
    }
}
@Composable
private fun AccessibilityPermissionCard(context: android.content.Context) {
    val isEnabled = remember {
        mutableStateOf(AccessibilityServiceState.isEnabled(context))
    }

    // Refresh on recompose (user may return from Settings)
    LaunchedEffect(Unit) {
        isEnabled.value = AccessibilityServiceState.isEnabled(context)
    }

    PermissionCard(
        title      = stringResource(if (isEnabled.value) R.string.onboarding_accessibility_enabled else R.string.onboarding_accessibility_not_enabled),
        body       = stringResource(if (isEnabled.value) R.string.onboarding_accessibility_enabled_body else R.string.onboarding_accessibility_not_enabled_body),
        buttonText = if (isEnabled.value) null else stringResource(R.string.onboarding_open_settings),
        isGranted  = isEnabled.value,
        onClick    = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun MicrophonePermissionCard(
    micState: com.google.accompanist.permissions.PermissionState,
    context:  android.content.Context
) {
    val granted = micState.status.isGranted
    PermissionCard(
        title      = stringResource(if (granted) R.string.onboarding_microphone_enabled else R.string.onboarding_microphone_not_enabled),
        body       = stringResource(if (granted) R.string.onboarding_microphone_enabled_body else R.string.onboarding_microphone_not_enabled_body),
        buttonText = if (granted) null else stringResource(R.string.onboarding_grant_access),
        isGranted  = granted,
        onClick    = { micState.launchPermissionRequest() }
    )
}

@Composable
private fun PrivacyExplanationCard() {
    Column(
        modifier = Modifier
            .clip(AIRIShapes.md)
            .background(AiriTheme.onSurface.copy(alpha = 0.05f))
            .border(1.dp, AiriTheme.outline, AIRIShapes.md)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PrivacyRow(stringResource(R.string.onboarding_privacy_local_title), stringResource(R.string.onboarding_privacy_local_body))
        PrivacyRow(stringResource(R.string.onboarding_privacy_cloud_title), stringResource(R.string.onboarding_privacy_cloud_body))
        PrivacyRow(stringResource(R.string.onboarding_privacy_telemetry_title), stringResource(R.string.onboarding_privacy_telemetry_body))
        PrivacyRow(stringResource(R.string.onboarding_privacy_deletion_title), stringResource(R.string.onboarding_privacy_deletion_body))
    }
}

@Composable
private fun PrivacyRow(label: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.CheckCircle, null, tint = CosmicAccent, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Column {
            Text(label,  color = AiriTheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun PermissionCard(
    title:      String,
    body:       String,
    buttonText: String?,
    isGranted:  Boolean,
    onClick:    () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(AIRIShapes.md)
            .background(AiriTheme.onSurface.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (isGranted) CosmicAccent.copy(alpha = 0.35f) else AiriTheme.outline,
                AIRIShapes.md
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = if (isGranted) CosmicAccent else AiriTheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
        if (buttonText != null) {
            TextButton(
                onClick = onClick,
                colors  = ButtonDefaults.textButtonColors(contentColor = CosmicAccent)
            ) {
                Text(buttonText, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// : Notifications permission card — POST_NOTIFICATIONS required on API 33+
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun NotificationsPermissionCard(
    permissionState: com.google.accompanist.permissions.PermissionState?
) {
    // On API < 33, notifications are granted at install time — show as enabled.
    val granted = permissionState?.status?.isGranted ?: true
    PermissionCard(
        title      = stringResource(if (granted) R.string.onboarding_notifications_enabled else R.string.onboarding_notifications_not_enabled),
        body       = stringResource(if (granted) R.string.onboarding_notifications_enabled_body else R.string.onboarding_notifications_not_enabled_body),
        buttonText = if (granted) null else stringResource(R.string.onboarding_grant_access),
        isGranted  = granted,
        onClick    = { permissionState?.launchPermissionRequest() }
    )
}

// : Calendar permission card — READ_CALENDAR + WRITE_CALENDAR for CalendarTool
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CalendarPermissionCard(
    permissionsState: com.google.accompanist.permissions.MultiplePermissionsState
) {
    val granted = permissionsState.allPermissionsGranted
    PermissionCard(
        title      = stringResource(if (granted) R.string.onboarding_calendar_enabled else R.string.onboarding_calendar_not_enabled),
        body       = stringResource(if (granted) R.string.onboarding_calendar_enabled_body else R.string.onboarding_calendar_not_enabled_body),
        buttonText = if (granted) null else stringResource(R.string.onboarding_grant_access),
        isGranted  = granted,
        onClick    = { permissionsState.launchMultiplePermissionRequest() }
    )
}

@Composable
private fun SocialProofStrip() {
    Row(
        modifier = Modifier
            .clip(AIRIShapes.md)
            .background(AiriTheme.surfaceVariant)
            .border(1.dp, AiriTheme.onSurface.copy(alpha = 0.09f), AIRIShapes.md)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = CosmicAccent, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.onboarding_tagline), color = AiriTheme.onBackground.copy(alpha = 0.76f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private data class OnboardingPage(
    val icon:          ImageVector,
    val titleRes:      Int,
    val subtitleRes:   Int,
    val permissionKey: String? = null
)
