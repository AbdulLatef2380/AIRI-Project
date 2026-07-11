package com.airi.assistant.ui.screens

import com.airi.assistant.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent

/**
 * Task 1.6: WelcomeScreen — shown to signed-in users with no model and no API key.
 * Provides guided CTAs to set up a model or enter an API key before using the app.
 */
@Composable
fun WelcomeScreen(
    onSetupModel: () -> Unit = {},
    onEnterApiKey: () -> Unit = {},
    onContinueAnyway: () -> Unit = {},
    /** Legacy single-action variant for backward compatibility */
    onStart: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.welcome_app_name), color = CosmicAccent, fontSize = 18.sp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Welcome to AIRI",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = AiriTheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "To get started, set up a local model or enter a cloud API key.",
            fontSize = 15.sp,
            color = AiriTheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))

        // CTA 1: Set up a local model
        Button(
            onClick = onSetupModel,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
        ) {
            Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.welcome_setup_model), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))

        // CTA 2: Enter API key
        OutlinedButton(
            onClick = onEnterApiKey,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.5.dp, CosmicAccent)
        ) {
            Icon(Icons.Outlined.Key, contentDescription = null,
                tint = CosmicAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.welcome_enter_api_key), fontSize = 15.sp, color = CosmicAccent, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))

        TextButton(onClick = onContinueAnyway) {
            Text(stringResource(R.string.welcome_continue_anyway), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
