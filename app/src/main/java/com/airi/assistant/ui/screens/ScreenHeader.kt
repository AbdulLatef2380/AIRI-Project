package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.CosmicAccent

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
            Row(content = trailing)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = CosmicAccent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Color.LightGray)
    }
}
