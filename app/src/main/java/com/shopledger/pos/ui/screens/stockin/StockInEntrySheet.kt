package com.shopledger.pos.ui.screens.stockin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.shopledger.pos.R
import com.shopledger.pos.ui.theme.ConfirmDialog
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTokens

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Entry point for adding stock: scan a bill or list with the camera, pick a photo, or add an item by hand.
 *
 * Keep this composable in the tree at all times (the app shell does) and drive the sheet with [visible]: the camera
 * and gallery launchers live here, so their results still arrive after the screen is rotated or the tab changed.
 * Reading the photo happens in the view model (see [StockInViewModel.readPhotos]); this sheet only hands the photos
 * to [onPhotos] / [onBitmap] and shows [reading]. Scanning needs seller mode; while it is locked the scan buttons call
 * [onRequestUnlock] instead. When [hasUnsavedReview] is true, starting a new scan first asks before the old review
 * is thrown away ([onDiscardReview]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockInEntrySheet(
    visible: Boolean,
    isSellerUnlocked: Boolean,
    onRequestUnlock: () -> Unit,
    onAddManually: () -> Unit,
    onDismiss: () -> Unit,
    reading: Boolean = false,
    hasUnsavedReview: Boolean = false,
    onDiscardReview: () -> Unit = {},
    onPhotos: (List<Uri>) -> Unit = {},
    onBitmap: (Bitmap) -> Unit = {},
) {
    val currentOnPhotos by rememberUpdatedState(onPhotos)
    val currentOnBitmap by rememberUpdatedState(onBitmap)
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    val cameraFallbackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) currentOnBitmap(bitmap)
    }

    val docScannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            currentOnPhotos(scan?.pages?.map { it.imageUri }.orEmpty())
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) currentOnPhotos(listOf(uri))
    }

    /** Run [start] now, or after the user agrees to throw the unsaved review away. */
    fun guarded(start: () -> Unit) {
        if (hasUnsavedReview) pendingStart = start else start()
    }

    if (!visible) return
    pendingStart?.let { start ->
        ConfirmDialog(
            title = stringResource(R.string.stock_in_replace_title),
            message = stringResource(R.string.stock_in_replace_message),
            confirmText = stringResource(R.string.review_discard_confirm),
            destructive = true,
            onConfirm = {
                pendingStart = null
                onDiscardReview()
                start()
            },
            onDismiss = { pendingStart = null },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
        ) {
            Text(stringResource(R.string.stock_in_title), style = MaterialTheme.typography.titleLarge)
            if (reading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.stock_in_reading), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!isSellerUnlocked) {
                Text(
                    stringResource(R.string.stock_in_unlock_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PrimaryActionButton(
                text = stringResource(R.string.stock_in_scan),
                onClick = {
                    if (!isSellerUnlocked) {
                        onRequestUnlock()
                    } else guarded {
                      if (activity != null) {
                        val options = GmsDocumentScannerOptions.Builder()
                            .setGalleryImportAllowed(true)
                            .setPageLimit(3)
                            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                            .build()
                        GmsDocumentScanning.getClient(options)
                            .getStartScanIntent(activity)
                            .addOnSuccessListener { docScannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                            .addOnFailureListener { cameraFallbackLauncher.launch(null) }
                      } else {
                        cameraFallbackLauncher.launch(null)
                      }
                    }
                },
                icon = Icons.Filled.CameraAlt,
                enabled = !reading,
            )
            SecondaryActionButton(
                text = stringResource(R.string.stock_in_pick_photo),
                onClick = { if (isSellerUnlocked) guarded { galleryLauncher.launch("image/*") } else onRequestUnlock() },
                icon = Icons.Filled.PhotoLibrary,
                enabled = !reading,
            )
            SecondaryActionButton(
                text = stringResource(R.string.stock_in_add_manually),
                onClick = { if (isSellerUnlocked) onAddManually() else onRequestUnlock() },
                icon = Icons.Filled.Edit,
                enabled = !reading,
            )
        }
    }
}
