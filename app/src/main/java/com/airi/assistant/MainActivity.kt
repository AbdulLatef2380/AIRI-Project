package com.airi.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.airi.assistant.ui.AiriApp
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.growth.ReferralManager
import com.airi.assistant.connector.oauth.OAuthStateRegistry
import com.airi.assistant.system.LanguageManager
import com.airi.assistant.ui.theme.AIRITheme
import com.airi.assistant.voice.HotwordService
import com.airi.assistant.R

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — we never block the app */ }

    /**
     * Receives the broadcast emitted by [HotwordService] when "Hey AIRI" is
     * recognized. Brings the activity to the front and signals the chat input
     * to start a fresh listen turn. Without this receiver the wake-word
     * service was firing into the void — Bug #4 in the user report ("the wake
     * word does nothing").
     */
    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Wake-word broadcast received → bringing chat to front")
            val launch = Intent(this@MainActivity, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                action = ACTION_WAKE_WORD_TRIGGERED
            }
            startActivity(launch)
        }
    }

    private var wakeReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReferralManager.captureReferralIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Phase 2, Task 9: Start SystemHealthCoordinator so thermal + battery signals are
        // wired to the execution budget before the first inference request is made.
        // Lazy init handles the ThermalProfiler.start() call internally; accessing the
        // property here triggers the lazy chain: thermalProfiler → systemHealthCoordinator.
        runCatching { ServiceLocator.systemHealthCoordinator }
            .onFailure { Log.w(TAG, "SystemHealthCoordinator startup failed: ${it.message}") }

        // Cold-start OAuth callback — handles CustomTab redirects that launch the app fresh
        intent?.let { dispatchOAuthCallback(it) }

        setContent {
            AIRITheme {
                val prefs = remember { getSharedPreferences("airi_flags", Context.MODE_PRIVATE) }
                val isFirstLaunch = remember {
                    val first = !prefs.getBoolean("accessibility_shown", false)
                    if (first) prefs.edit().putBoolean("accessibility_shown", true).apply()
                    first
                }
                var showAccessDialog by remember {
                    mutableStateOf(isFirstLaunch && !isAccessibilityServiceEnabled())
                }

                // App loads immediately — no blocking gate
                AiriApp()

                // One dismissable
                if (showAccessDialog) {
                    AlertDialog(
                        onDismissRequest = { showAccessDialog = false },
                        shape = RoundedCornerShape(20.dp),
                        title = {
                            Text(stringResource(R.string.enable_accessibility_service), fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.accessibility_dialog_message)
                                )
                                Text(
                                    stringResource(R.string.accessibility_dialog_steps),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showAccessDialog = false
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) {
                                Text(stringResource(R.string.open_settings))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAccessDialog = false }) {
                                Text(stringResource(R.string.later))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!wakeReceiverRegistered) {
            val filter = IntentFilter(HotwordService.ACTION_WAKE_WORD)
            // RECEIVER_NOT_EXPORTED on API 33+ — the service that emits this
            // broadcast lives in our own process, no external sender allowed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    this,
                    wakeWordReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(wakeWordReceiver, filter)
            }
            wakeReceiverRegistered = true
            Log.d(TAG, "Wake-word receiver registered")
        }
    }

    override fun onStop() {
        super.onStop()
        if (wakeReceiverRegistered) {
            try { unregisterReceiver(wakeWordReceiver) } catch (_: Throwable) {}
            wakeReceiverRegistered = false
            Log.d(TAG, "Wake-word receiver unregistered")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ReferralManager.captureReferralIntent(intent)
        if (intent.action == ACTION_WAKE_WORD_TRIGGERED) {
            WakeWordDispatcher.fireTriggered()
        }
        dispatchOAuthCallback(intent)
    }

    /**
     * Handle an OAuth deep-link callback from airi://oauth/callback.
     *
     * Called from both [onNewIntent] (warm start) and [onCreate] (cold start
     * via CustomTab redirect) so the validation path is shared.
     *
     * CSRF protection: the inbound `state` is validated against
     * [OAuthStateRegistry]. Unknown, expired, or replayed states are dropped
     * silently — the EventBus is never touched.
     *
     * Legacy empty-state callbacks (token-paste integrations) still emit
     * [AppEvent.OAuthCallbackReceived] with `provider=""` so IntegrationsViewModel
     * can handle them; subscribers must check `provider.isNotEmpty()` to
     * distinguish a CSRF-validated flow from a paste-token flow.
     */
    private fun dispatchOAuthCallback(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "airi" || data.host != "oauth") return
        if (data.pathSegments.firstOrNull() != "callback") return

        val code  = data.getQueryParameter("code") ?: return
        val state = data.getQueryParameter("state")

        if (state.isNullOrBlank()) {
            // Legacy paste-token path — emit without provider
            android.util.Log.d("AIRI_OAUTH", "OAuth callback: no state (legacy paste-token path)")
            com.airi.assistant.domain.event.EventBus.emit(
                com.airi.assistant.domain.event.AppEvent.OAuthCallbackReceived(
                    code = code, state = "", provider = ""
                )
            )
            return
        }

        // CSRF-validated path: consume the state from the registry
        val provider = OAuthStateRegistry.consume(state)
        if (provider == null) {
            android.util.Log.w("AIRI_OAUTH",
                "Rejected OAuth callback — state unknown/expired/replayed: ${state.take(8)}…")
            return
        }

        android.util.Log.i("AIRI_OAUTH",
            "OAuth callback validated: provider=$provider code=${code.take(8)}…")
        com.airi.assistant.domain.event.EventBus.emit(
            com.airi.assistant.domain.event.AppEvent.OAuthCallbackReceived(
                code = code, state = state, provider = provider
            )
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        private const val TAG = "AIRI_MAIN"
        const val ACTION_WAKE_WORD_TRIGGERED = "com.airi.assistant.action.WAKE_WORD_TRIGGERED"
    }
}

/**
 * Process-scoped, lock-free signal that the wake word fired. The chat screen
 * observes [counter] in a LaunchedEffect and starts an in-app listen turn.
 *
 * Kept in this file (not a separate service) so MainActivity is fully
 * self-contained for the wake-word path.
 */
object WakeWordDispatcher {
    private val _counter = mutableStateOf(0)
    val counter: State<Int> get() = _counter
    fun fireTriggered() { _counter.value = _counter.value + 1 }
}
