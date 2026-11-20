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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R
import com.shopledger.pos.ui.text.asString
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
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.error,
                    MaterialTheme.colorScheme.onErrorContainer,
                    Icons.Default.ErrorOutline
                )
                MessageSeverity.WARNING -> Quadruple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    Icons.Default.WarningAmber
                )
                MessageSeverity.SUCCESS -> Quadruple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    Icons.Default.CheckCircle
                )
                MessageSeverity.INFO -> Quadruple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.onSecondaryContainer,
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
                            text = msg.title.asString(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = inkColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = msg.detail.asString() + msg.technical?.let { "\n$it" }.orEmpty(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { AppErrorManager.dismiss(msg.id) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = inkColor,
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
