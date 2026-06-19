package com.airi.assistant.ui.input

import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SurfaceFloating

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusMenuSheet(onDismiss: () -> Unit, onAction: (PlusMenuAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Color(0xFF0F1628),
        contentColor     = Color.White,
        dragHandle = {
            Box(modifier = Modifier.padding(vertical = 10.dp).width(36.dp).height(4.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f)))
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.plus_menu_create_explore), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AiriTheme.onBackground, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done), color = CosmicAccent, fontSize = 13.sp) }
            }
            Divider(color = AiriTheme.onBackground.copy(alpha = 0.07f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            PlusMenuAction.sections.forEach { (title, actions) ->
                Text(title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = AiriTheme.onBackground.copy(alpha = 0.35f), letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = false) {
                    items(items = actions, key = { it.label }) { action ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                .background(AiriTheme.surfaceVariant)
                                .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                .clickable { onAction(action); onDismiss() }
                                .padding(vertical = 14.dp, horizontal = 8.dp).fillMaxWidth()) {
                            Text(action.emoji, fontSize = 26.sp)
                            Text(action.label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                color = AiriTheme.onBackground.copy(alpha = 0.85f), textAlign = TextAlign.Center,
                                maxLines = 2, lineHeight = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
