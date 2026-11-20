package com.shopledger.pos.ui.screens.settings

import android.accounts.AccountManager
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.BuildConfig
import com.shopledger.pos.data.command.CommandException
import com.shopledger.pos.ui.text.labelRes
import com.shopledger.pos.ui.text.resolve
import com.shopledger.pos.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.backup.BackupShare
import com.shopledger.pos.core.cipher.CipherMode
import com.shopledger.pos.core.cipher.CipherReferenceStrip
import com.shopledger.pos.core.cipher.CodedPurchasePriceBadge
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.core.update.AppUpdateManager
import com.shopledger.pos.core.update.UpdateStatus
import com.shopledger.pos.ui.theme.ChipTone
import com.shopledger.pos.ui.theme.ConfirmDialog
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SegmentedChoice
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StatusChip
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import android.net.Uri
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clean, zero-complexity Account & Shop Settings Sheet:
 * 1. Single-Tap Google Sign-In (auto-configured via `google-services.json` -> `ubiverse-app`).
 * 2. Seller's WhatsApp Number (for 1-tap buyer contact link on Receipts & Estimates).
 * 3. Secret Symbol Key (when Seller Mode is unlocked).
 * 4. Backup & Restore: nightly encrypted snapshots, a shareable backup file, and a checked restore
 *    (the backup is validated and staged, then the app restarts to swap it in with automatic rollback).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncAndBackupSheet(
    pendingSyncCount: Int,
    isSellerUnlocked: Boolean,
    onExportBackupFile: suspend () -> File?,
    /** Stages the chosen backup file for restore. Returns null when it was staged, otherwise an error message to show. */
    onRestoreBackupFile: suspend (Uri) -> String?,
    /** Modification time of the newest automatic (nightly) snapshot, or null when there is none. */
    latestSnapshotTime: () -> Long?,
    /** Stages the newest automatic snapshot for restore. Returns null when staged, otherwise an error message. */
    onRestoreLatestSnapshot: suspend () -> String?,
    onImportBackupJson: suspend (String) -> Boolean,
    /** The code in the local `devices` row (what receipt numbers really use). */
    loadDeviceCode: suspend () -> String,
    /** Saves the counter code. Returns the stored (cleaned) code, or throws with a user-presentable message. */
    onSaveDeviceCode: suspend (String) -> String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    val signedInUser by GoogleAuthManager.currentUser.collectAsState()
    val syncStatusMessage by SyncManager.lastSyncMessage.collectAsState()
    val updateStatus by AppUpdateManager.status.collectAsState()
    val (currentVersionName, currentVersionCode) = remember { AppUpdateManager.getCurrentVersionInfo(context) }

    val activeVertical by SyncManager.shopVerticalFlow.collectAsState()
    val activeCipherMode by SyncManager.cipherModeFlow.collectAsState()
    val activeWord10 by SyncManager.cipherWord10Flow.collectAsState()
    val activeCustomDigitMap by SyncManager.customDigitMapFlow.collectAsState()
    val ownerPinHash by SyncManager.ownerPinHashFlow.collectAsState()

    var sellerNameInput by remember {
        mutableStateOf(SyncManager.getSellerName(context))
    }
    var sellerWhatsAppInput by remember {
        mutableStateOf(SyncManager.getSellerWhatsAppNumber(context))
    }
    var upiIdInput by remember { mutableStateOf(SyncManager.getUpiId(context)) }
    var gstinInput by remember { mutableStateOf(SyncManager.getGstin(context)) }
    var deviceCodeInput by remember { mutableStateOf(SyncManager.getDeviceShortCode(context)) }
    var storedDeviceCode by remember { mutableStateOf(deviceCodeInput) }
    var deviceCodeWasFocused by remember { mutableStateOf(false) }
    var deviceCodeSave by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        runCatching { loadDeviceCode() }.onSuccess { deviceCodeInput = it; storedDeviceCode = it }
    }
    // Saved once when editing ends (Done or focus lost); an earlier in-flight save is cancelled first so saves can't finish out of order.
    val commitDeviceCode: () -> Unit = {
        val code = deviceCodeInput
        if (code.isEmpty()) {
            deviceCodeInput = storedDeviceCode
            Toast.makeText(context, R.string.shop_counter_code_empty, Toast.LENGTH_SHORT).show()
        } else if (code != storedDeviceCode) {
            deviceCodeSave?.cancel()
            deviceCodeSave = coroutineScope.launch {
                runCatching { onSaveDeviceCode(code) }
                    .onSuccess { saved ->
                        storedDeviceCode = saved
                        deviceCodeInput = saved
                        SyncManager.saveDeviceShortCode(context, saved)
                    }
                    .onFailure {
                        if (it is CancellationException) throw it
                        deviceCodeInput = storedDeviceCode
                        Toast.makeText(context, (it as? CommandException)?.uiText?.resolve(context) ?: context.getString(R.string.shop_counter_code_failed), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }
    var word10Input by remember(activeWord10) { mutableStateOf(activeWord10) }
    var customDigitInputs by remember(activeCustomDigitMap) { mutableStateOf(activeCustomDigitMap) }
    var newPinInput by remember { mutableStateOf("") }
    var importJsonText by remember { mutableStateOf("") }
    var showImportBox by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showSnapshotRestoreConfirm by remember { mutableStateOf(false) }
    var checkingBackup by rememberSaveable { mutableStateOf(false) }
    val lastSnapshotMillis = remember { latestSnapshotTime() }
    val lastSnapshotLabel = remember(lastSnapshotMillis) {
        lastSnapshotMillis?.let { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(it)) }
    }

    /**
     * Check and stage a backup behind a blocking dialog. The restart itself happens inside [stage] (non-cancellable),
     * so a null result means the app is already restarting and the dialog simply stays until the process ends.
     */
    fun stageThenRestart(stage: suspend () -> String?) {
        coroutineScope.launch {
            checkingBackup = true
            val error = try {
                stage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.getString(R.string.msg_backup_unreadable)
            }
            if (error != null) {
                checkingBackup = false
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            stageThenRestart { onRestoreBackupFile(uri) }
        }
    }
    if (checkingBackup) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.settings_checking_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.settings_checking_body), style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {}
        )
    }
    if (showRestoreConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_restore_all_title),
            message = stringResource(R.string.settings_restore_all_body),
            confirmText = stringResource(R.string.settings_choose_backup_file),
            destructive = true,
            onConfirm = {
                showRestoreConfirm = false
                restorePicker.launch(arrayOf("*/*"))
            },
            onDismiss = { showRestoreConfirm = false },
        )
    }
    if (showSnapshotRestoreConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_restore_night_title),
            message = stringResource(
                R.string.settings_restore_night_body,
                lastSnapshotLabel ?: stringResource(R.string.settings_last_night)
            ),
            confirmText = stringResource(R.string.settings_restore_action),
            destructive = true,
            onConfirm = {
                showSnapshotRestoreConfirm = false
                stageThenRestart { onRestoreLatestSnapshot() }
            },
            onDismiss = { showSnapshotRestoreConfirm = false },
        )
    }

    // Single-Tap Native Google Account Picker Launcher (Zero SHA-1 / Code 10 issues)
    val googleAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickedEmail = result.data
            ?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            ?.trim()
            .orEmpty()

        if (result.resultCode == Activity.RESULT_OK && pickedEmail.isNotBlank()) {
            coroutineScope.launch {
                val user = GoogleAuthManager.handleGoogleEmailSignIn(context, pickedEmail)
                Toast.makeText(
                    context,
                    context.getString(R.string.msg_signed_in, user.email),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            // Also check if a GoogleSignInAccount was returned
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    coroutineScope.launch {
                        val user = GoogleAuthManager.handleGoogleAccountSignIn(context, account)
                        Toast.makeText(
                            context,
                            context.getString(R.string.msg_signed_in, user.email),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (_: Exception) {
                // User dismissed the account picker dialog
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        FloatingAppMessageHost()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            // 1. SINGLE-TAP GOOGLE SIGN-IN CARD
            SectionCard {
                val activeUser = signedInUser
                if (activeUser == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_google_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_google_body),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    PrimaryActionButton(
                        text = stringResource(R.string.settings_google_button),
                        icon = Icons.Default.AccountCircle,
                        onClick = {
                            try {
                                googleAccountPickerLauncher.launch(
                                    GoogleAuthManager.buildGoogleAccountPickerIntent()
                                )
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.msg_google_chooser_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeUser.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = activeUser.email,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = pluralStringResource(R.plurals.settings_sync_status, pendingSyncCount, syncStatusMessage, pendingSyncCount),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusChip(text = stringResource(R.string.settings_connected), tone = ChipTone.SUCCESS)
                    }

                    PrimaryActionButton(
                        text = stringResource(R.string.settings_sync_now),
                        icon = Icons.Default.CloudSync,
                        onClick = {
                            SyncManager.onUserSignedInTriggerFullSync(
                                context,
                                activeUser.cloudAccountDocumentId
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.msg_syncing),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.settings_sign_out),
                        icon = Icons.AutoMirrored.Filled.Logout,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            GoogleAuthManager.signOut(context)
                            Toast.makeText(context, R.string.msg_signed_out, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // 2. SELLER / SHOP DETAILS & WHATSAPP CARD
            SectionCard(title = stringResource(R.string.settings_shop_title)) {
                Text(
                    text = stringResource(R.string.settings_shop_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SettingsField(
                    value = sellerNameInput,
                    onValueChange = {
                        sellerNameInput = it
                        SyncManager.saveSellerName(context, it)
                    },
                    label = stringResource(R.string.settings_seller_name),
                    placeholder = stringResource(R.string.settings_seller_name_hint),
                    capitalization = KeyboardCapitalization.Words
                )

                SettingsField(
                    value = sellerWhatsAppInput,
                    onValueChange = {
                        sellerWhatsAppInput = it
                        SyncManager.saveSellerWhatsAppNumber(context, it)
                    },
                    label = stringResource(R.string.settings_whatsapp),
                    placeholder = stringResource(R.string.settings_whatsapp_hint),
                    keyboardType = KeyboardType.Phone
                )

                SettingsField(
                    value = upiIdInput,
                    onValueChange = {
                        upiIdInput = it
                        SyncManager.saveUpiId(context, it)
                    },
                    label = stringResource(R.string.settings_upi),
                    placeholder = stringResource(R.string.settings_upi_hint),
                    capitalization = KeyboardCapitalization.None
                )
                SettingsField(
                    value = deviceCodeInput,
                    onValueChange = { typed ->
                        deviceCodeInput = typed.uppercase(Locale.ROOT).filter { ch -> ch in 'A'..'Z' || ch in '0'..'9' }.take(4)
                    },
                    label = stringResource(R.string.shop_counter_code),
                    placeholder = stringResource(R.string.settings_counter_hint),
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                    onImeAction = { commitDeviceCode() },
                    modifier = Modifier.onFocusChanged { f ->
                        if (deviceCodeWasFocused && !f.isFocused) commitDeviceCode()
                        deviceCodeWasFocused = f.isFocused
                    }
                )

                SettingsField(
                    value = gstinInput,
                    onValueChange = {
                        gstinInput = it
                        SyncManager.saveGstin(context, it)
                    },
                    label = stringResource(R.string.settings_gstin),
                    placeholder = stringResource(R.string.settings_gstin_hint),
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                )
            }

            // 2B. BUSINESS VERTICAL & COST PRIVACY MODE SETUP
            SectionCard(title = stringResource(R.string.settings_trade_title)) {
                Text(
                    text = stringResource(R.string.settings_trade_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShopVertical.entries.forEach { v ->
                        FilterChip(
                            selected = v == activeVertical,
                            onClick = {
                                SyncManager.saveShopVertical(context, v)
                                Toast.makeText(context, context.getString(R.string.msg_trade_pack_switched, context.getString(v.labelRes())), Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(stringResource(v.labelRes()), style = MaterialTheme.typography.labelLarge) },
                            modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.settings_cost_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isSellerUnlocked) {
                    SegmentedChoice(
                        options = listOf(
                            stringResource(R.string.settings_cost_mode_code),
                            stringResource(R.string.settings_cost_mode_pin)
                        ),
                        selectedIndex = if (activeCipherMode == CipherMode.HIDDEN_PIN) 1 else 0,
                        onSelect = { idx ->
                            val nextMode = if (idx == 1) CipherMode.HIDDEN_PIN else CipherMode.CUSTOM_CODE
                            SyncManager.saveCustomDigitMap(context, nextMode, customDigitInputs)
                        }
                    )

                    if (activeCipherMode == CipherMode.CUSTOM_CODE) {
                        SettingsField(
                            value = word10Input,
                            onValueChange = { raw ->
                                val clean = raw.uppercase(Locale.ROOT)
                                    .filter { !it.isWhitespace() && it != '[' && it != ']' && it != '=' }
                                    .take(10)
                                word10Input = clean
                                if (CostCipherEngine.isValidCipherWord10(clean)) {
                                    val mapped = CostCipherEngine.word10ToDigitMap(clean)
                                    customDigitInputs = mapped
                                    SyncManager.saveCustomDigitMap(context, CipherMode.CUSTOM_CODE, mapped)
                                }
                            },
                            label = stringResource(R.string.settings_word10_label),
                            placeholder = CostCipherEngine.DEFAULT_WORD_10,
                            capitalization = KeyboardCapitalization.Characters
                        )
                        if (!CostCipherEngine.isValidCipherWord10(word10Input)) {
                            Text(
                                text = stringResource(R.string.settings_word10_error),
                                style = MaterialTheme.typography.bodyLarge,
                                color = ShopLedgerTheme.extra.warning
                            )
                        }

                        Text(
                            text = stringResource(R.string.settings_per_digit),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val digitRow1 = remember { listOf('1', '2', '3', '4', '5') }
                        val digitRow2 = remember { listOf('6', '7', '8', '9', '0') }
                        listOf(digitRow1, digitRow2).forEach { rowDigits ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowDigits.forEach { d ->
                                    val digitDescription = stringResource(R.string.settings_digit_label, d.toString())
                                    SettingsField(
                                        value = customDigitInputs[d].orEmpty(),
                                        onValueChange = { typed ->
                                            val cleanToken = typed
                                                .replace("[", "")
                                                .replace("]", "")
                                                .replace("=", "")
                                                .trim()
                                                .take(4)
                                                .uppercase(Locale.ROOT)
                                            val updated = customDigitInputs.toMutableMap().apply {
                                                put(d, cleanToken)
                                            }
                                            customDigitInputs = updated
                                            if (cleanToken.isNotEmpty()) {
                                                SyncManager.saveCustomDigitMap(context, CipherMode.CUSTOM_CODE, updated)
                                            }
                                        },
                                        label = d.toString(),
                                        placeholder = CostCipherEngine.defaultDigitMap()[d].orEmpty(),
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics { contentDescription = digitDescription }
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_preview),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            CodedPurchasePriceBadge(
                                purchasePricePaise = 125000L,
                                isSellerUnlocked = false,
                                cipherMode = CipherMode.CUSTOM_CODE
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.cost_code_locked_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (ownerPinHash.isBlank() || isSellerUnlocked) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsField(
                            value = newPinInput,
                            onValueChange = { newPinInput = it.filter { ch -> ch.isDigit() }.take(6) },
                            label = stringResource(if (ownerPinHash.isBlank()) R.string.owner_pin_set else R.string.owner_pin_change),
                            placeholder = stringResource(R.string.owner_pin_hint),
                            keyboardType = KeyboardType.NumberPassword,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                SyncManager.saveOwnerPin(context, newPinInput)
                                val msg = context.getString(if (newPinInput.isBlank()) R.string.owner_pin_cleared else R.string.owner_pin_saved)
                                newPinInput = ""
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.heightIn(min = ShopLedgerTokens.primaryActionHeight)
                        ) {
                            Text(
                                stringResource(
                                    if (newPinInput.isBlank() && ownerPinHash.isNotBlank()) R.string.owner_pin_button_clear else R.string.owner_pin_button_set
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.owner_pin_locked_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. IN-APP OTA UPDATES & RELEASES
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_updates_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.more_version, currentVersionName, currentVersionCode.toString()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    when (val s = updateStatus) {
                        is UpdateStatus.UpdateAvailable -> {
                            StatusChip(
                                text = stringResource(R.string.update_available, s.latestVersion),
                                tone = ChipTone.INFO
                            )
                        }
                        is UpdateStatus.UpToDate -> {
                            StatusChip(
                                text = stringResource(R.string.update_up_to_date),
                                tone = ChipTone.SUCCESS
                            )
                        }
                        else -> Unit
                    }
                }

                when (val status = updateStatus) {
                    is UpdateStatus.Idle, is UpdateStatus.UpToDate -> {
                        PrimaryActionButton(
                            text = stringResource(if (status is UpdateStatus.UpToDate) R.string.settings_check_again else R.string.update_check),
                            icon = Icons.Default.SystemUpdate,
                            onClick = {
                                coroutineScope.launch {
                                    val result = AppUpdateManager.checkForUpdates(context)
                                    if (result is UpdateStatus.UpToDate) {
                                        Toast.makeText(context, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                    is UpdateStatus.Checking -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.update_checking),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is UpdateStatus.UpdateAvailable -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.settings_release_notes, status.releaseNotes),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (status.apkSize > 0) {
                                val mb = String.format(Locale.ENGLISH, "%.1f", status.apkSize / (1024.0 * 1024.0))
                                Text(
                                    text = stringResource(R.string.settings_download_size, mb),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            PrimaryActionButton(
                                text = stringResource(R.string.update_download),
                                icon = Icons.Default.Download,
                                onClick = {
                                    coroutineScope.launch {
                                        AppUpdateManager.downloadUpdate(context, status.downloadUrl)
                                    }
                                }
                            )
                        }
                    }
                    is UpdateStatus.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val percent = (status.progress * 100).toInt()
                            val mbDownloaded = String.format(Locale.ENGLISH, "%.1f", status.bytesDownloaded / (1024.0 * 1024.0))
                            val mbTotal = String.format(Locale.ENGLISH, "%.1f", status.totalBytes / (1024.0 * 1024.0))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.update_downloading, percent),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.settings_download_progress, mbDownloaded, mbTotal),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LinearProgressIndicator(
                                progress = { status.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                    is UpdateStatus.ReadyToInstall -> {
                        PrimaryActionButton(
                            text = stringResource(R.string.update_ready),
                            icon = Icons.Default.Check,
                            onClick = {
                                if (AppUpdateManager.canInstallPackages(context)) {
                                    AppUpdateManager.installApk(context, status.apkFile)
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.update_permission),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    AppUpdateManager.openInstallPermissionSettings(context)
                                }
                            }
                        )
                    }
                    is UpdateStatus.Error -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.msg_update_error) + if (BuildConfig.DEBUG) " (${status.message})" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            SecondaryActionButton(
                                text = stringResource(R.string.action_retry),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    AppUpdateManager.resetStatus()
                                    coroutineScope.launch { AppUpdateManager.checkForUpdates(context) }
                                }
                            )
                        }
                    }
                }
            }

            // 4. Coded Purchase Price Symbol Key (Only visible when Seller Mode is unlocked)
            if (isSellerUnlocked) {
                SectionCard(title = stringResource(R.string.settings_cipher_key_title)) {
                    CipherReferenceStrip(modifier = Modifier.fillMaxWidth())
                }
            }

            // 5. Backup & Restore
            SectionCard(title = stringResource(R.string.more_backup)) {
                if (!isSellerUnlocked) {
                    Text(
                        text = stringResource(R.string.more_backup_locked),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_backup_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lastSnapshotLabel != null) {
                        Text(
                            text = stringResource(R.string.backup_last, lastSnapshotLabel),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        EmptyState(
                            title = stringResource(R.string.backup_none_title),
                            message = stringResource(R.string.backup_none_message),
                        )
                    }
                    PrimaryActionButton(
                        text = stringResource(R.string.backup_export_button),
                        onClick = {
                            coroutineScope.launch {
                                val file = onExportBackupFile()
                                if (file != null) BackupShare.share(context, file, context.getString(R.string.share_backup_chooser))
                            }
                        }
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.settings_restore_backup),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showRestoreConfirm = true }
                    )
                    if (lastSnapshotLabel != null) {
                        SecondaryActionButton(
                            text = stringResource(R.string.settings_restore_night_button, lastSnapshotLabel),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showSnapshotRestoreConfirm = true }
                        )
                    }
                    TextButton(
                        onClick = { showImportBox = !showImportBox },
                        modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)
                    ) {
                        Text(stringResource(R.string.settings_import_toggle), style = MaterialTheme.typography.labelLarge)
                    }

                    if (showImportBox) {
                        SettingsField(
                            value = importJsonText,
                            onValueChange = { importJsonText = it },
                            label = stringResource(R.string.settings_import_label),
                            singleLine = false,
                            capitalization = KeyboardCapitalization.None
                        )
                        PrimaryActionButton(
                            text = stringResource(R.string.settings_import_button),
                            onClick = {
                                coroutineScope.launch {
                                    val ok = onImportBackupJson(importJsonText)
                                    Toast.makeText(
                                        context,
                                        context.getString(if (ok) R.string.msg_backup_merged else R.string.msg_backup_json_invalid),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (ok) {
                                        showImportBox = false
                                        importJsonText = ""
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Outlined text field for the settings sheet: 56 dp tall, label always visible, Material 3 colours. */
@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        } else null,
        singleLine = singleLine,
        shape = MaterialTheme.shapes.medium,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onNext = { onImeAction() },
            onSearch = { onImeAction() }
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    )
}
