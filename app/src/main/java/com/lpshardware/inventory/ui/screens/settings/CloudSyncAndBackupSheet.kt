package com.lpshardware.inventory.ui.screens.settings

import android.accounts.AccountManager
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.lpshardware.inventory.core.auth.GoogleAuthManager
import com.lpshardware.inventory.core.cipher.CipherReferenceStrip
import com.lpshardware.inventory.core.share.ReceiptShareManager
import com.lpshardware.inventory.core.sync.SyncManager
import com.lpshardware.inventory.core.update.AppUpdateManager
import com.lpshardware.inventory.core.update.UpdateStatus
import com.lpshardware.inventory.ui.theme.CupertinoCard
import com.lpshardware.inventory.ui.theme.CupertinoColors
import com.lpshardware.inventory.ui.theme.CupertinoTextField
import com.lpshardware.inventory.ui.theme.SemanticPillBadge
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Clean, zero-complexity Account & Shop Settings Sheet:
 * 1. Single-Tap Google Sign-In (auto-configured via `google-services.json` -> `ubiverse-app`).
 * 2. Seller's WhatsApp Number (for 1-tap buyer contact link on Receipts & Estimates).
 * 3. Secret Symbol Key (when Seller Mode is unlocked).
 * 4. Offline JSON Backup & Restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncAndBackupSheet(
    pendingSyncCount: Int,
    isSellerUnlocked: Boolean,
    onExportBackupJson: suspend () -> String,
    onImportBackupJson: suspend (String) -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    val signedInUser by GoogleAuthManager.currentUser.collectAsState()
    val syncStatusMessage by SyncManager.lastSyncMessage.collectAsState()
    val updateStatus by AppUpdateManager.status.collectAsState()
    val (currentVersionName, currentVersionCode) = remember { AppUpdateManager.getCurrentVersionInfo(context) }

    var sellerNameInput by remember {
        mutableStateOf(SyncManager.getSellerName(context))
    }
    var sellerWhatsAppInput by remember {
        mutableStateOf(SyncManager.getSellerWhatsAppNumber(context))
    }
    var importJsonText by remember { mutableStateOf("") }
    var showImportBox by remember { mutableStateOf(false) }

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
                    "Signed in as ${user.email} — Syncing all items to this device!",
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
                            "Signed in as ${user.email} — Syncing all items to this device!",
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
        containerColor = CupertinoColors.SystemGroupedBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Account, Shop WhatsApp & Backup",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CupertinoColors.LabelPrimary
            )

            // 1. SINGLE-TAP GOOGLE SIGN-IN CARD
            CupertinoCard(
                containerColor = if (signedInUser != null) CupertinoColors.EmeraldTint else CupertinoColors.CardSurface,
                borderColor = if (signedInUser != null) CupertinoColors.EmeraldGreen.copy(alpha = 0.4f) else CupertinoColors.SystemBlue.copy(alpha = 0.35f)
            ) {
                val activeUser = signedInUser
                if (activeUser == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(CupertinoColors.SystemBlueTint),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = CupertinoColors.SystemBlue,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Single-Tap Google Sign-In",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CupertinoColors.LabelPrimary
                            )
                            Text(
                                text = "Sign in with Google to automatically sync all LPS Hardware items, variants & sales across your devices.",
                                fontSize = 12.sp,
                                color = CupertinoColors.LabelSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            try {
                                googleAccountPickerLauncher.launch(
                                    GoogleAuthManager.buildGoogleAccountPickerIntent()
                                )
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Could not open Google Account chooser: ${e.localizedMessage}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CupertinoColors.SystemBlue,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(vertical = 13.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sign in with Google",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeUser.displayName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CupertinoColors.LabelPrimary
                            )
                            Text(
                                text = activeUser.email,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.EmeraldGreen
                            )
                            Text(
                                text = "$syncStatusMessage • $pendingSyncCount pending",
                                fontSize = 11.sp,
                                color = CupertinoColors.LabelSecondary
                            )
                        }

                        SemanticPillBadge(
                            text = "Connected",
                            containerColor = CupertinoColors.CardSurface,
                            contentColor = CupertinoColors.EmeraldGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                SyncManager.onUserSignedInTriggerFullSync(
                                    context,
                                    activeUser.cloudAccountDocumentId
                                )
                                Toast.makeText(
                                    context,
                                    "Syncing all items with Cloud...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CupertinoColors.EmeraldGreen,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1.4f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync All Items Now", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                GoogleAuthManager.signOut(context)
                                Toast.makeText(context, "Signed out of Google Account", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CupertinoColors.DestructiveRed.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(0.8f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                tint = CupertinoColors.DestructiveRed,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Sign Out",
                                color = CupertinoColors.DestructiveRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // 2. SELLER / SHOP DETAILS & WHATSAPP CARD (Improvement #1 & #5)
            CupertinoCard {
                Text(
                    text = "Seller / Shop Name & WhatsApp",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Printed on every Sale Receipt & Estimate photo and included as a 1-tap WhatsApp link so buyers can reach out to you directly.",
                    fontSize = 12.sp,
                    color = CupertinoColors.LabelSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                CupertinoTextField(
                    value = sellerNameInput,
                    onValueChange = {
                        sellerNameInput = it
                        SyncManager.saveSellerName(context, it)
                    },
                    label = "Seller / Shop Name",
                    placeholder = "LPS Hardware / Owner Name",
                    capitalization = KeyboardCapitalization.Words
                )

                Spacer(modifier = Modifier.height(8.dp))

                CupertinoTextField(
                    value = sellerWhatsAppInput,
                    onValueChange = {
                        sellerWhatsAppInput = it
                        SyncManager.saveSellerWhatsAppNumber(context, it)
                    },
                    label = "Seller WhatsApp Mobile (+91)",
                    placeholder = "e.g. 9876543210",
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                )
            }

            // 3. IN-APP OTA UPDATES & RELEASES (Improvement #5)
            CupertinoCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Updates & Releases",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.LabelPrimary
                        )
                        Text(
                            text = "Installed: v$currentVersionName (Build $currentVersionCode)",
                            fontSize = 12.sp,
                            color = CupertinoColors.LabelSecondary
                        )
                    }

                    when (val s = updateStatus) {
                        is UpdateStatus.UpdateAvailable -> {
                            SemanticPillBadge(
                                text = "v${s.latestVersion} Available",
                                containerColor = CupertinoColors.SystemBlueTint,
                                contentColor = CupertinoColors.SystemBlue
                            )
                        }
                        is UpdateStatus.UpToDate -> {
                            SemanticPillBadge(
                                text = "Up to Date",
                                containerColor = CupertinoColors.EmeraldTint,
                                contentColor = CupertinoColors.EmeraldGreen
                            )
                        }
                        else -> Unit
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (val status = updateStatus) {
                    is UpdateStatus.Idle, is UpdateStatus.UpToDate -> {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val result = AppUpdateManager.checkForUpdates(context)
                                    if (result is UpdateStatus.UpToDate) {
                                        Toast.makeText(context, "LPS Hardware is up to date!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CupertinoColors.SystemBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (status is UpdateStatus.UpToDate) "Check Again for Updates" else "Check for Updates",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
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
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CupertinoColors.SystemBlue
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Checking for new releases on GitHub...",
                                fontSize = 12.sp,
                                color = CupertinoColors.LabelSecondary
                            )
                        }
                    }
                    is UpdateStatus.UpdateAvailable -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Release Notes: ${status.releaseNotes}",
                                fontSize = 12.sp,
                                color = CupertinoColors.LabelPrimary
                            )
                            if (status.apkSize > 0) {
                                val mb = String.format(Locale.ENGLISH, "%.1f MB", status.apkSize / (1024.0 * 1024.0))
                                Text(
                                    text = "Download Size: $mb",
                                    fontSize = 11.sp,
                                    color = CupertinoColors.LabelSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        AppUpdateManager.downloadUpdate(context, status.downloadUrl)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CupertinoColors.EmeraldGreen,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Download & Install v${status.latestVersion}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    is UpdateStatus.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val percent = (status.progress * 100).toInt()
                            val mbDownloaded = String.format(Locale.ENGLISH, "%.1f", status.bytesDownloaded / (1024.0 * 1024.0))
                            val mbTotal = String.format(Locale.ENGLISH, "%.1f", status.totalBytes / (1024.0 * 1024.0))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Downloading Update... $percent%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CupertinoColors.SystemBlue
                                )
                                Text(
                                    text = "$mbDownloaded / $mbTotal MB",
                                    fontSize = 11.sp,
                                    color = CupertinoColors.LabelSecondary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { status.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = CupertinoColors.SystemBlue,
                                trackColor = CupertinoColors.SystemBlueTint
                            )
                        }
                    }
                    is UpdateStatus.ReadyToInstall -> {
                        Button(
                            onClick = {
                                if (AppUpdateManager.canInstallPackages(context)) {
                                    AppUpdateManager.installApk(context, status.apkFile)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Please allow installing updates from this app in Settings",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    AppUpdateManager.openInstallPermissionSettings(context)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CupertinoColors.EmeraldGreen,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ready! Tap to Install Update", fontWeight = FontWeight.Bold)
                        }
                    }
                    is UpdateStatus.Error -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Update Error: ${status.message}",
                                fontSize = 11.sp,
                                color = CupertinoColors.DestructiveRed
                            )
                            OutlinedButton(
                                onClick = { AppUpdateManager.resetStatus() },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 4. Coded Purchase Price Symbol Key (Only visible when Seller Mode is unlocked)
            if (isSellerUnlocked) {
                CupertinoCard {
                    Text(
                        text = "LPS Hardware Secret Purchase Price Symbol Key",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.CipherIndigo
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    CipherReferenceStrip(modifier = Modifier.fillMaxWidth())
                }
            }

            // 4. Local JSON Backup Export / Restore
            CupertinoCard {
                Text(
                    text = "Offline JSON Backup & Restore",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Export a complete snapshot of all LPS Hardware items, variants, and sales to WhatsApp or Clipboard.",
                    fontSize = 12.sp,
                    color = CupertinoColors.LabelSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val json = onExportBackupJson()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("LPS_Hardware_Backup", json))
                                Toast.makeText(context, "Backup JSON copied to Clipboard!", Toast.LENGTH_SHORT).show()
                                ReceiptShareManager.shareTextToWhatsApp(context, json)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export Backup", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { showImportBox = !showImportBox },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Restore Backup", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                if (showImportBox) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CupertinoTextField(
                        value = importJsonText,
                        onValueChange = { importJsonText = it },
                        label = "Paste Backup JSON Here",
                        singleLine = false,
                        capitalization = KeyboardCapitalization.None
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val ok = onImportBackupJson(importJsonText)
                                Toast.makeText(
                                    context,
                                    if (ok) "Backup Restored Successfully!" else "Invalid Backup JSON",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (ok) {
                                    showImportBox = false
                                    importJsonText = ""
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import & Merge Records", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
