package com.shopledger.pos.ui.screens.more

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.shopledger.pos.R
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.core.update.AppUpdateManager
import com.shopledger.pos.core.update.UpdateStatus
import com.shopledger.pos.ui.i18n.AppLanguage
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * More tab: shop details, language, security, cost code, backup, cloud sync and app info.
 * Heavy editors (Google sign-in, trade pack, cost code key editor, backup and restore) stay in the
 * existing account sheet, opened by [onOpenAccountSheet].
 */
@Composable
fun MoreScreen(
    isSellerUnlocked: Boolean,
    onToggleSeller: () -> Unit,
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    pendingSyncCount: Int,
    loadDeviceCode: suspend () -> String,
    onSaveDeviceCode: suspend (String) -> String,
    onOpenAccountSheet: () -> Unit,
    onRunSetupAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ShopLedgerTokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
    ) {
        item { Text(stringResource(R.string.more_title), style = MaterialTheme.typography.headlineSmall) }
        item { ShopCard(loadDeviceCode, onSaveDeviceCode) }
        item {
            SectionCard(title = stringResource(R.string.setup_run_again)) {
                Text(
                    stringResource(R.string.setup_run_again_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryActionButton(text = stringResource(R.string.setup_run_again), onClick = onRunSetupAgain)
            }
        }
        item { LanguageCard(language, onLanguageSelected) }
        item { SecurityCard(isSellerUnlocked, onToggleSeller) }
        item {
            LockedActionCard(
                title = stringResource(R.string.more_cost_code),
                hint = stringResource(R.string.more_cost_code_hint),
                lockedHint = stringResource(R.string.more_cost_code_locked),
                buttonText = stringResource(R.string.more_cost_code_open),
                unlocked = isSellerUnlocked,
                onClick = onOpenAccountSheet,
            )
        }
        item {
            LockedActionCard(
                title = stringResource(R.string.more_backup),
                hint = stringResource(R.string.more_backup_hint),
                lockedHint = stringResource(R.string.more_backup_locked),
                buttonText = stringResource(R.string.more_backup_open),
                unlocked = isSellerUnlocked,
                onClick = onOpenAccountSheet,
            )
        }
        item { CloudCard(pendingSyncCount, onOpenAccountSheet) }
        item { AboutCard() }
    }
}

@Composable
private fun ShopCard(loadDeviceCode: suspend () -> String, onSaveDeviceCode: suspend (String) -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(SyncManager.getSellerName(context)) }
    var whatsApp by remember { mutableStateOf(SyncManager.getSellerWhatsAppNumber(context)) }
    var upi by remember { mutableStateOf(SyncManager.getUpiId(context)) }
    var gstin by remember { mutableStateOf(SyncManager.getGstin(context)) }
    var counterCode by remember { mutableStateOf(SyncManager.getDeviceShortCode(context)) }
    var storedCounterCode by remember { mutableStateOf(counterCode) }
    var counterWasFocused by remember { mutableStateOf(false) }
    var counterSave by remember { mutableStateOf<Job?>(null) }
    val emptyMessage = stringResource(R.string.shop_counter_code_empty)
    val failedMessage = stringResource(R.string.shop_counter_code_failed)

    LaunchedEffect(Unit) {
        runCatching { loadDeviceCode() }.onSuccess { counterCode = it; storedCounterCode = it }
    }
    // Saved once when editing ends; an earlier in-flight save is cancelled first so saves cannot finish out of order.
    val commitCounterCode: () -> Unit = {
        val code = counterCode
        if (code.isEmpty()) {
            counterCode = storedCounterCode
            Toast.makeText(context, emptyMessage, Toast.LENGTH_SHORT).show()
        } else if (code != storedCounterCode) {
            counterSave?.cancel()
            counterSave = scope.launch {
                runCatching { onSaveDeviceCode(code) }
                    .onSuccess { saved ->
                        storedCounterCode = saved
                        counterCode = saved
                        SyncManager.saveDeviceShortCode(context, saved)
                    }
                    .onFailure {
                        if (it is CancellationException) throw it
                        counterCode = storedCounterCode
                        Toast.makeText(context, it.message ?: failedMessage, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    SectionCard(title = stringResource(R.string.more_shop)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; SyncManager.saveSellerName(context, it) },
            label = { Text(stringResource(R.string.shop_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = whatsApp,
            onValueChange = { whatsApp = it; SyncManager.saveSellerWhatsAppNumber(context, it) },
            label = { Text(stringResource(R.string.shop_whatsapp)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = upi,
            onValueChange = { upi = it; SyncManager.saveUpiId(context, it) },
            label = { Text(stringResource(R.string.shop_upi)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = gstin,
            onValueChange = { gstin = it; SyncManager.saveGstin(context, it) },
            label = { Text(stringResource(R.string.shop_gstin)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = counterCode,
            onValueChange = { typed ->
                counterCode = typed.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }.take(4)
            },
            label = { Text(stringResource(R.string.shop_counter_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commitCounterCode() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (counterWasFocused && !f.isFocused) commitCounterCode()
                counterWasFocused = f.isFocused
            },
        )
    }
}

@Composable
private fun LanguageCard(current: AppLanguage, onSelected: (AppLanguage) -> Unit) {
    SectionCard(title = stringResource(R.string.settings_language)) {
        Column(Modifier.selectableGroup()) {
            listOf(
                AppLanguage.ENGLISH to stringResource(R.string.language_english),
                AppLanguage.HINDI to stringResource(R.string.language_hindi),
            ).forEach { (lang, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ShopLedgerTokens.minTouch)
                        .selectable(selected = lang == current, role = Role.RadioButton, onClick = { onSelected(lang) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = lang == current, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = ShopLedgerTokens.spacingM))
                }
            }
        }
    }
}

@Composable
private fun SecurityCard(isSellerUnlocked: Boolean, onToggleSeller: () -> Unit) {
    val context = LocalContext.current
    val pinHash by SyncManager.ownerPinHashFlow.collectAsState()
    var newPin by remember { mutableStateOf("") }
    val saved = stringResource(R.string.owner_pin_saved)
    val cleared = stringResource(R.string.owner_pin_cleared)

    SectionCard(title = stringResource(R.string.more_security)) {
        Row(Modifier.fillMaxWidth().heightIn(min = ShopLedgerTokens.minTouch), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.seller_mode), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.seller_mode_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = isSellerUnlocked, onCheckedChange = { onToggleSeller() })
        }
        val pinLocked = pinHash.isNotBlank() && !isSellerUnlocked
        if (pinLocked) {
            Text(
                stringResource(R.string.owner_pin_locked_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = newPin,
                onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(6) },
                label = {
                    Text(stringResource(if (pinHash.isBlank()) R.string.owner_pin_set else R.string.owner_pin_change))
                },
                placeholder = { Text(stringResource(R.string.owner_pin_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            val clearing = newPin.isBlank() && pinHash.isNotBlank()
            if (newPin.isNotBlank() || pinHash.isNotBlank()) {
                SecondaryActionButton(
                    text = stringResource(if (clearing) R.string.owner_pin_button_clear else R.string.owner_pin_button_set),
                    onClick = {
                        SyncManager.saveOwnerPin(context, newPin)
                        Toast.makeText(context, if (newPin.isBlank()) cleared else saved, Toast.LENGTH_SHORT).show()
                        newPin = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun LockedActionCard(
    title: String,
    hint: String,
    lockedHint: String,
    buttonText: String,
    unlocked: Boolean,
    onClick: () -> Unit,
) {
    SectionCard(title = title) {
        if (unlocked) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryActionButton(text = buttonText, onClick = onClick)
        } else {
            Text(lockedHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CloudCard(pendingSyncCount: Int, onOpenAccountSheet: () -> Unit) {
    val user by GoogleAuthManager.currentUser.collectAsState()
    val message by SyncManager.lastSyncMessage.collectAsState()
    SectionCard(title = stringResource(R.string.more_cloud)) {
        val activeUser = user
        Text(
            if (activeUser != null) {
                stringResource(R.string.cloud_signed_in, activeUser.email.ifBlank { activeUser.displayName })
            } else {
                stringResource(R.string.cloud_signed_out)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pendingSyncCount > 0) {
            Text(
                stringResource(R.string.cloud_pending, pendingSyncCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SecondaryActionButton(text = stringResource(R.string.more_account_open), onClick = onOpenAccountSheet)
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by AppUpdateManager.status.collectAsState()
    val (versionName, versionCode) = remember { AppUpdateManager.getCurrentVersionInfo(context) }
    val upToDate = stringResource(R.string.update_up_to_date)
    val permission = stringResource(R.string.update_permission)

    SectionCard(title = stringResource(R.string.more_about)) {
        Text(stringResource(R.string.more_version, versionName, versionCode.toString()), style = MaterialTheme.typography.bodyLarge)
        when (val s = status) {
            is UpdateStatus.Idle, is UpdateStatus.UpToDate -> {
                if (s is UpdateStatus.UpToDate) {
                    Text(upToDate, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SecondaryActionButton(
                    text = stringResource(R.string.update_check),
                    onClick = {
                        scope.launch {
                            val result = AppUpdateManager.checkForUpdates(context)
                            if (result is UpdateStatus.UpToDate) Toast.makeText(context, upToDate, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            is UpdateStatus.Checking -> Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
            is UpdateStatus.UpdateAvailable -> {
                Text(stringResource(R.string.update_available, s.latestVersion), style = MaterialTheme.typography.bodyLarge)
                PrimaryActionButton(
                    text = stringResource(R.string.update_download),
                    onClick = { scope.launch { AppUpdateManager.downloadUpdate(context, s.downloadUrl) } },
                )
            }
            is UpdateStatus.Downloading -> {
                Text(stringResource(R.string.update_downloading, (s.progress * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { s.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateStatus.ReadyToInstall -> PrimaryActionButton(
                text = stringResource(R.string.update_ready),
                onClick = {
                    if (AppUpdateManager.canInstallPackages(context)) {
                        AppUpdateManager.installApk(context, s.apkFile)
                    } else {
                        Toast.makeText(context, permission, Toast.LENGTH_LONG).show()
                        AppUpdateManager.openInstallPermissionSettings(context)
                    }
                },
            )
            is UpdateStatus.Error -> {
                Text(stringResource(R.string.update_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                SecondaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = {
                        AppUpdateManager.resetStatus()
                        scope.launch {
                            val result = AppUpdateManager.checkForUpdates(context)
                            if (result is UpdateStatus.UpToDate) Toast.makeText(context, upToDate, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}
