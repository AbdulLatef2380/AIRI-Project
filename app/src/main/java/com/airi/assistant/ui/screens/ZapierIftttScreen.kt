package com.airi.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.app.IftttConnector
import com.airi.assistant.connector.app.ZapierConnector
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * ZapierIftttScreen — combined connector setup screen for Zapier and IFTTT.
 *
 * Zapier tab:
 *  - Displays OAuth connect / disconnect flow
 *  - Shows list of available trigger types
 *  - Allows manual webhook trigger (test)
 *
 * IFTTT tab:
 *  - Webhook key entry (stored encrypted)
 *  - Event name + payload test trigger
 *  - Applet trigger history
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapierIftttScreen(
    zapierConnector: ZapierConnector,
    iftttConnector:  IftttConnector,
    onBack:          () -> Unit = {}
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Zapier", "IFTTT")

    // Zapier state
    val zapierState by zapierConnector.state().collectAsState()

    // IFTTT state
    val iftttState by iftttConnector.state().collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.zapier_ifttt_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = AiriTheme.background,
                contentColor     = CosmicAccent,
                indicator        = { tabPositions ->
                    Box(
                        Modifier
                            .tabIndicatorOffset(tabPositions[selectedTab])
                            .height(2.dp)
                            .background(CosmicAccent)
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick  = { selectedTab = index },
                        text     = {
                            Text(
                                title,
                                color = if (selectedTab == index) CosmicAccent else AiriTheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> ZapierTab(zapierConnector, zapierState, context, scope)
                1 -> IftttTab(iftttConnector, iftttState, scope)
            }
        }
    }
}

@Composable
private fun ZapierTab(
    connector: ZapierConnector,
    state:     ConnectorState,
    context:   android.content.Context,
    scope:     kotlinx.coroutines.CoroutineScope
) {
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting  by remember { mutableStateOf(false) }
    var testHookUrl   by remember { mutableStateOf("") }
    var testPayload   by remember { mutableStateOf("") }
    var testResult    by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            ConnectorStatusCard(
                name        = "Zapier",
                description = "Connect AIRI to 6000+ apps via Zapier automations",
                iconEmoji   = "⚙",
                accentColor = Color(0xFFFF4A00),
                state       = state
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = AIRIShapes.md
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.zapier_auth_section), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    Text(
                        stringResource(R.string.zapier_auth_description),
                        fontSize = 13.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 18.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.connected) {
                            Button(
                                onClick = {
                                    if (!connector.isOAuthConfigured()) {
                                        statusMessage = context.getString(R.string.zapier_oauth_not_configured)
                                        return@Button
                                    }
                                    isConnecting = true
                                    val authUrl = connector.buildAuthUrl()
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                    context.startActivity(intent)
                                    isConnecting = false
                                    statusMessage = context.getString(R.string.zapier_authorization_started)
                                },
                                enabled = !isConnecting,
                                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4A00))
                            ) {
                                if (isConnecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.OpenInBrowser, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.zapier_connect_button))
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        connector.disconnect()
                                        statusMessage = context.getString(R.string.zapier_disconnected)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.disconnect))
                            }
                        }
                    }
                    statusMessage?.let {
                        Text(it, fontSize = 12.sp, color = if (state.connected) SemanticSuccess else AiriTheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = AIRIShapes.md
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.zapier_triggers_section), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    val triggers = listOf(
                        "message_sent"     to "Fires when AIRI sends a message",
                        "agent_completed"  to "Fires when an agent task finishes",
                        "skill_executed"   to "Fires when a skill runs",
                        "credit_limit_hit" to "Fires when daily credits run out",
                        "memory_stored"    to "Fires when AIRI stores a memory"
                    )
                    triggers.forEach { (name, desc) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(8.dp).background(CosmicAccent, CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                                Text(desc, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = AIRIShapes.md
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.test_webhook), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    OutlinedTextField(
                        value         = testHookUrl,
                        onValueChange = { testHookUrl = it },
                        label         = { Text(stringResource(R.string.zapier_hook_url_label)) },
                        placeholder = { Text(stringResource(R.string.zapier_webhook_placeholder)) },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        colors        = inputColors()
                    )
                    OutlinedTextField(
                        value         = testPayload,
                        onValueChange = { testPayload = it },
                        label         = { Text(stringResource(R.string.zapier_payload_label)) },
                        modifier      = Modifier.fillMaxWidth().height(80.dp),
                        colors        = inputColors()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                testResult = "Sending…"
                                val result = connector.execute(
                                    com.airi.assistant.connector.ConnectorInput(
                                        action = "send_webhook",
                                        text   = testPayload.ifBlank { "{\"source\":\"AIRI test\"}" },
                                        params = mapOf("hook_url" to testHookUrl)
                                    )
                                )
                                testResult = when (result) {
                                    is com.airi.assistant.connector.ConnectorOutput.Success -> result.text
                                    is com.airi.assistant.connector.ConnectorOutput.Failure -> "Error: ${result.message}"
                                    else -> "Sent"
                                }
                            }
                        },
                        enabled = testHookUrl.isNotBlank(),
                        colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
                    ) {
                        Icon(Icons.Default.Send, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.send_test))
                    }
                    testResult?.let {
                        Text(it, fontSize = 12.sp, color = if (it.startsWith("Error")) SemanticError else SemanticSuccess)
                    }
                }
            }
        }
    }
}

@Composable
private fun IftttTab(
    connector: IftttConnector,
    state:     ConnectorState,
    scope:     kotlinx.coroutines.CoroutineScope
) {
    var webhookKey     by remember { mutableStateOf(connector.getWebhookKey() ?: "") }
    var keyVisible     by remember { mutableStateOf(false) }
    var eventName      by remember { mutableStateOf("") }
    var value1         by remember { mutableStateOf("") }
    var triggerResult  by remember { mutableStateOf<String?>(null) }
    var isTesting      by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            ConnectorStatusCard(
                name        = "IFTTT",
                description = "Trigger IFTTT applets and Maker Webhooks from AIRI",
                iconEmoji   = "🔗",
                accentColor = Color(0xFF00C3E3),
                state       = state
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = AIRIShapes.md
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.zapier_webhook_key_section), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    Text(
                        "Get your Maker Webhook key from ifttt.com/maker_webhooks/settings",
                        fontSize = 13.sp, color = AiriTheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value         = webhookKey,
                        onValueChange = { webhookKey = it },
                        label         = { Text(stringResource(R.string.ifttt_maker_key_label)) },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon  = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (keyVisible) "Hide" else "Show"
                                )
                            }
                        },
                        colors = inputColors()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                connector.setKey(webhookKey.trim())
                                connector.connect()
                            }
                        },
                        enabled = webhookKey.isNotBlank(),
                        colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C3E3))
                    ) {
                        Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ifttt_save_key))
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = AIRIShapes.md
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.test_applet_trigger), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    OutlinedTextField(
                        value         = eventName,
                        onValueChange = { eventName = it },
                        label         = { Text(stringResource(R.string.ifttt_event_name_label)) },
                        placeholder   = { Text(stringResource(R.string.ifttt_event_placeholder)) },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        colors        = inputColors()
                    )
                    OutlinedTextField(
                        value         = value1,
                        onValueChange = { value1 = it },
                        label         = { Text(stringResource(R.string.ifttt_value1_label)) },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        colors        = inputColors()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                isTesting = true
                                triggerResult = "Triggering…"
                                val result = connector.execute(
                                    com.airi.assistant.connector.ConnectorInput(
                                        action = "trigger_event",
                                        text   = value1.ifBlank { "Test from AIRI" },
                                        params = mapOf("event" to eventName, "value1" to value1.ifBlank { "AIRI test" })
                                    )
                                )
                                triggerResult = when (result) {
                                    is com.airi.assistant.connector.ConnectorOutput.Success -> result.text
                                    is com.airi.assistant.connector.ConnectorOutput.Failure -> "Error: ${result.message}"
                                    else -> "Sent"
                                }
                                isTesting = false
                            }
                        },
                        enabled = eventName.isNotBlank() && state.connected && !isTesting,
                        colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C3E3))
                    ) {
                        if (isTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AiriTheme.onSurface)
                        else Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ifttt_trigger_applet))
                    }
                    triggerResult?.let {
                        Text(it, fontSize = 12.sp, color = if (it.startsWith("Error")) SemanticError else SemanticSuccess)
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = AIRIShapes.md
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ifttt_how_section), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    val useCases = listOf(
                        "📧" to "Send email alerts when an agent task completes",
                        "•" to "Control smart lights based on AIRI reminders",
                        "•" to "Log AIRI messages to Google Sheets",
                        "•" to "Send iOS/Android push notifications from AIRI",
                        "🔔" to "Post to Slack when credits run low"
                    )
                    useCases.forEach { (emoji, desc) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(emoji, fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(desc, fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectorStatusCard(
    name:        String,
    description: String,
    iconEmoji:   String,
    accentColor: Color,
    state:       ConnectorState
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape  = AIRIShapes.md,
        border = if (state.connected) androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)) else null
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(48.dp).background(accentColor.copy(alpha = 0.15f), AIRIShapes.md),
                contentAlignment = Alignment.Center
            ) { Text(iconEmoji, fontSize = 24.sp) }
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                Text(description, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(if (state.connected) SemanticSuccess else AiriTheme.outline, CircleShape))
                    Text(
                        if (state.connected) state.statusLine else "Not connected",
                        fontSize = 12.sp,
                        color    = if (state.connected) SemanticSuccess else AiriTheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = CosmicAccent,
    unfocusedBorderColor = AiriTheme.outline,
    focusedLabelColor    = CosmicAccent,
    cursorColor          = CosmicAccent
)

