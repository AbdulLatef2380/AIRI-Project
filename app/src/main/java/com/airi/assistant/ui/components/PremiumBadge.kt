package com.airi.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.monetization.PaywallTriggerEngine

private val accentColor = Color(0xFF7C3AED)
private val goldColor   = Color(0xFFFFB300)

/**
 * Compact "PREMIUM" badge — attach next to any locked feature label.
 * If [onUnlockClick] is provided, tapping it triggers the paywall.
 */
@Composable
fun PremiumBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    onUnlockClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(goldColor.copy(alpha = 0.12f))
            .border(1.dp, goldColor.copy(alpha = 0.3f), shape)
            .then(
                if (onUnlockClick != null) Modifier.clickable {
                    AnalyticsService.premiumFeatureAttempted("badge_tap")
                    PaywallTriggerEngine.onPremiumFeatureAttempt()
                    onUnlockClick()
                } else Modifier
            )
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 3.dp else 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (onUnlockClick != null) Icons.Outlined.Lock else Icons.Outlined.Star,
                contentDescription = "Premium",
                tint     = goldColor,
                modifier = Modifier.size(if (compact) 10.dp else 14.dp)
            )
            Text(
                text          = "PREMIUM",
                fontSize      = if (compact) 9.sp else 11.sp,
                fontWeight    = FontWeight.ExtraBold,
                color         = goldColor,
                letterSpacing = 0.8.sp
            )
        }
    }
}

/**
 * Full-width locked feature row — shows the feature name, description,
 * a PREMIUM badge, and optionally an unlock CTA.
 */
@Composable
fun LockedFeatureRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onUnlockClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, goldColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clickable {
                AnalyticsService.premiumFeatureAttempted(title)
                if (PaywallTriggerEngine.onPremiumFeatureAttempt() != PaywallTriggerEngine.UpsellLevel.NONE) onUnlockClick()
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text       = title,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White.copy(alpha = 0.7f)
                )
                PremiumBadge()
            }
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = description,
                    fontSize = 12.sp,
                    color    = Color.White.copy(alpha = 0.35f)
                )
            }
        }
        Icon(
            Icons.Outlined.Lock,
            contentDescription = "Locked",
            tint     = goldColor.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
    }
}
