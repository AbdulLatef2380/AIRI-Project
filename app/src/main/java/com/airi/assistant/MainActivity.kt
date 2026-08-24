package com.airi.assistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.lifecycle.lifecycleScope
import com.airi.assistant.ui.AiriApp
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.growth.ReferralManager
import com.airi.assistant.connector.oauth.OAuthStateRegistry
import com.airi.assistant.system.LanguageManager
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.voice.HotwordService
import com.airi.assistant.connector.app.ZapierConnector
import com.airi.assistant.R
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReferralManager.captureReferralIntent(intent)
        if (intent?.action == HotwordService.ACTION_WAKE_WORD_TRIGGERED) {
            WakeWordDispatcher.fireTriggered()
        }

        // , Start SystemHealthCoordinator so thermal + battery signals are
        // wired to the execution budget before the first inference request is made.
        // Lazy init handles the ThermalProfiler.start() call internally; accessing the
        // property here triggers the lazy chain: thermalProfiler → systemHealthCoordinator.
        runCatching { ServiceLocator.systemHealthCoordinator }
            .onFailure { Log.w(TAG, "SystemHealthCoordinator startup failed: ${it.message}") }

        // Cold-start OAuth callback — handles CustomTab redirects that launch the app fresh
        intent?.let { dispatchOAuthCallback(it) }

        setContent {
            AiriTheme {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ReferralManager.captureReferralIntent(intent)
        if (intent.action == HotwordService.ACTION_WAKE_WORD_TRIGGERED) {
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
     * CSRF and PKCE protection: the inbound `state` is consumed against
     * [OAuthStateRegistry]. Unknown, expired, or replayed states are dropped
     * before any token exchange is attempted.
     *
     * Every browser callback must carry a state value created by the registry.
     * Token-paste integrations are not browser callbacks and must use their own
     * explicit settings flow rather than this deep-link entry point.
     */
    private fun dispatchOAuthCallback(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "airi" || data.host != "oauth") return
        if (data.pathSegments.firstOrNull() != "callback") return

        val code  = data.getQueryParameter("code") ?: return
        val state = data.getQueryParameter("state")

        if (state.isNullOrBlank()) {
            android.util.Log.w("AIRI_OAUTH", "Rejected OAuth callback without state")
            return
        }

        val requestContext = OAuthStateRegistry.consumeRequest(state)
        if (requestContext == null) {
            android.util.Log.w("AIRI_OAUTH", "Rejected OAuth callback with invalid state")
            return
        }

        when (requestContext.connectorId) {
            ZapierConnector.CONNECTOR_ID -> lifecycleScope.launch {
                val connector = ServiceLocator.connectorRegistry
                    .get(ZapierConnector.CONNECTOR_ID) as? ZapierConnector
                val exchanged = connector?.handleCallback(code, requestContext) ?: false
                if (!exchanged) {
                    android.util.Log.w("AIRI_OAUTH", "OAuth code exchange failed for Zapier")
                }
            }
            else -> android.util.Log.w("AIRI_OAUTH", "Rejected OAuth callback for unsupported connector")
        }
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
