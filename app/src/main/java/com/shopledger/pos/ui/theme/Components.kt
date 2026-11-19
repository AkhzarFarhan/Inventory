package com.shopledger.pos.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter

enum class StockStatus { OK, LOW, OUT, OVERSOLD }

enum class ChipTone { NEUTRAL, SUCCESS, WARNING, DANGER, INFO }

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = ShopLedgerTokens.primaryActionHeight),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(ShopLedgerTokens.spacingS))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = ShopLedgerTokens.minTouch),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(ShopLedgerTokens.spacingS))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ItemRow(
    title: String,
    subtitle: String,
    trailingPrimary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingSecondary: String? = null,
    status: StockStatus = StockStatus.OK,
) {
    val secondaryColor = when (status) {
        StockStatus.OK -> MaterialTheme.colorScheme.onSurfaceVariant
        StockStatus.LOW -> ShopLedgerTheme.extra.warning
        StockStatus.OUT, StockStatus.OVERSOLD -> ShopLedgerTheme.extra.danger
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(trailingPrimary, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (trailingSecondary != null) {
                Text(trailingSecondary, style = MaterialTheme.typography.bodyMedium, color = secondaryColor)
            }
        }
    }
}

@Composable
fun QtyStepper(
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onValueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val minusDesc = stringResource(R.string.cd_decrease_quantity)
    val plusDesc = stringResource(R.string.cd_increase_quantity)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onMinus,
            modifier = Modifier.size(ShopLedgerTokens.minTouch),
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
        ) { Icon(Icons.Filled.Remove, contentDescription = minusDesc) }
        TextButton(
            onClick = onValueClick,
            modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch).widthIn(min = 56.dp),
        ) { Text(valueText, style = MaterialTheme.typography.titleMedium) }
        IconButton(
            onClick = onPlus,
            modifier = Modifier.size(ShopLedgerTokens.minTouch),
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
        ) { Icon(Icons.Filled.Add, contentDescription = plusDesc) }
    }
}

private fun editable(paise: Long?): String =
    paise?.let { IndianCurrencyFormatter.paiseToEditableString(it) } ?: ""

private fun parse(text: String): Long? =
    if (text.isBlank()) null else IndianCurrencyFormatter.parseRupeesToPaise(text)

@Composable
fun MoneyField(
    valuePaise: Long?,
    onValueChange: (Long?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(editable(valuePaise)) }
    // Re-sync when the value is changed from outside (e.g. a form reset) and no longer matches the text.
    // A text such as "0" or "5." parses to a value the formatter renders differently, so only compare parsed values.
    val parsed = parse(text)
    if (parsed != valuePaise && !(parsed == 0L && valuePaise == null)) {
        text = editable(valuePaise)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() || it == '.' }
            if (filtered.count { it == '.' } <= 1 && filtered.substringAfter('.', "").length <= 2) {
                text = filtered
                onValueChange(parse(filtered))
            }
        },
        label = { Text(label) },
        prefix = { Text("₹") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
fun StatusChip(text: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val extra = ShopLedgerTheme.extra
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ChipTone.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
        ChipTone.SUCCESS -> extra.success to extra.onSuccess
        ChipTone.WARNING -> extra.warning to extra.onWarning
        ChipTone.DANGER -> extra.danger to extra.onDanger
        ChipTone.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingXs),
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(ShopLedgerTokens.spacingXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(ShopLedgerTokens.spacingS))
            PrimaryActionButton(text = actionText, onClick = onAction)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(
                    confirmText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(ShopLedgerTokens.spacingL),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
