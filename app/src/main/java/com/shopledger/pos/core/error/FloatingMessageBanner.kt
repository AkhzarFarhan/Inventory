package com.shopledger.pos.core.error

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopledger.pos.ui.theme.CupertinoColors
import kotlinx.coroutines.delay

/**
 * Floating in-app notification & exception overlay.
 * Surfaces all errors, warnings, info notices, and saved-draft banners without losing context.
 */
@Composable
fun FloatingAppMessageHost(
    modifier: Modifier = Modifier
) {
    val activeMessage by AppErrorManager.activeMessage.collectAsState()

    LaunchedEffect(activeMessage?.id) {
        val current = activeMessage ?: return@LaunchedEffect
        val autoHideMs = when (current.severity) {
            MessageSeverity.ERROR, MessageSeverity.WARNING -> 6500L
            MessageSeverity.INFO, MessageSeverity.SUCCESS -> 4500L
        }
        delay(autoHideMs)
        AppErrorManager.dismiss(current.id)
    }

    AnimatedVisibility(
        visible = activeMessage != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        activeMessage?.let { msg ->
            val (bgColor, borderColor, inkColor, icon) = when (msg.severity) {
                MessageSeverity.ERROR -> Quadruple(
                    CupertinoColors.DestructiveTint,
                    CupertinoColors.DestructiveRed,
                    CupertinoColors.DestructiveRed,
                    Icons.Default.ErrorOutline
                )
                MessageSeverity.WARNING -> Quadruple(
                    CupertinoColors.AmberTint,
                    CupertinoColors.AmberWarning,
                    CupertinoColors.AmberWarning,
                    Icons.Default.WarningAmber
                )
                MessageSeverity.SUCCESS -> Quadruple(
                    CupertinoColors.EmeraldTint,
                    CupertinoColors.EmeraldGreen,
                    CupertinoColors.EmeraldGreen,
                    Icons.Default.CheckCircle
                )
                MessageSeverity.INFO -> Quadruple(
                    CupertinoColors.SystemBlueTint,
                    CupertinoColors.SystemBlue,
                    CupertinoColors.SystemBlue,
                    Icons.Default.Info
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = bgColor,
                border = BorderStroke(1.dp, borderColor.copy(alpha = 0.7f)),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = msg.severity.name,
                        tint = inkColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = msg.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = inkColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = msg.detail,
                            fontSize = 12.sp,
                            color = CupertinoColors.LabelPrimary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { AppErrorManager.dismiss(msg.id) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss Message",
                            tint = Color(0xFF444448),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
