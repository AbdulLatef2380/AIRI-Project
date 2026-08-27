package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.BuildConfig
import com.airi.assistant.R
import com.airi.assistant.domain.release.UpdateAvailabilityPolicy
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent

/**
 * Shows only release information that is present in the installed build.
 *
 * AIRI currently has no authenticated update catalog or installer hand-off.
 * The screen intentionally does not simulate either capability.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.updates_title),
                        color = AiriTheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = AiriTheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item { InstalledBuildCard() }
            item {
                if (!UpdateAvailabilityPolicy.automaticChecksAvailable) {
                    UpdateAvailabilityUnavailableCard()
                }
            }
            item { Spacer(Modifier.width(1.dp)) }
        }
    }
}

@Composable
private fun InstalledBuildCard() {
    Surface(
        shape = AIRIShapes.lg,
        color = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(CosmicAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Update,
                    contentDescription = null,
                    tint = CosmicAccent,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = stringResource(R.string.updates_installed_version, BuildConfig.VERSION_NAME),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = AiriTheme.onBackground
            )
            Text(
                text = stringResource(R.string.updates_installed_build, BuildConfig.VERSION_CODE),
                fontSize = 13.sp,
                color = AiriTheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpdateAvailabilityUnavailableCard() {
    Surface(
        shape = AIRIShapes.lg,
        color = AiriTheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AiriTheme.outline, AIRIShapes.lg)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = AiriTheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = stringResource(R.string.updates_check_unavailable_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = AiriTheme.onBackground
            )
            Text(
                text = stringResource(R.string.updates_check_unavailable_body),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = AiriTheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.updates_release_information_body),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = AiriTheme.onSurfaceVariant
            )
        }
    }
}
