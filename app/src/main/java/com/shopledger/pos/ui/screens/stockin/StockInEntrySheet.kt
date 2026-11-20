package com.shopledger.pos.ui.screens.stockin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.shopledger.pos.R
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.scanner.OnDeviceOrderScannerManager
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Entry point for adding stock: scan a bill or list with the camera, pick a photo, or add an item by hand.
 * Scanning reads the photo on this device and hands the text to [onScanned]; the sheet stays open while it works
 * (so the camera/gallery result is not lost) and the host closes it from [onScanned].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockInEntrySheet(
    onAddManually: () -> Unit,
    onDismiss: () -> Unit,
    onScanned: (text: String, imageUri: Uri?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var processing by remember { mutableStateOf(false) }
    val failedTitle = stringResource(R.string.stock_in_scan_failed)

    /** OCR the given photos, tidy the text with the optional LLM, and hand it over. */
    fun readPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            processing = true
            try {
                val combined = StringBuilder()
                var firstError: Throwable? = null
                for (uri in uris) {
                    OnDeviceOrderScannerManager.extractTextFromUri(context, uri)
                        .onSuccess { if (it.isNotBlank()) combined.appendLine(it) }
                        .onFailure { if (firstError == null) firstError = it }
                }
                val raw = combined.toString().trim()
                if (raw.isBlank() && firstError != null) {
                    AppErrorManager.reportError(failedTitle, firstError, failedTitle)
                } else {
                    val text = if (raw.isBlank()) raw else OnDeviceOrderScannerManager.extractStockLines(context, raw) ?: raw
                    onScanned(text, uris.first())
                }
            } finally {
                processing = false
            }
        }
    }

    val cameraFallbackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            scope.launch {
                processing = true
                try {
                    OnDeviceOrderScannerManager.extractTextFromBitmap(bitmap)
                        .onSuccess { raw ->
                            val text = if (raw.isBlank()) raw else OnDeviceOrderScannerManager.extractStockLines(context, raw) ?: raw
                            onScanned(text, null)
                        }
                        .onFailure { AppErrorManager.reportError(failedTitle, it, failedTitle) }
                } finally {
                    processing = false
                }
            }
        }
    }

    val docScannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            readPhotos(scan?.pages?.map { it.imageUri }.orEmpty())
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) readPhotos(listOf(uri))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
        ) {
            Text(stringResource(R.string.stock_in_title), style = MaterialTheme.typography.titleLarge)
            if (processing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.stock_in_reading), style = MaterialTheme.typography.bodyLarge)
                }
            }
            PrimaryActionButton(
                text = stringResource(R.string.stock_in_scan),
                onClick = {
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
                },
                icon = Icons.Filled.CameraAlt,
                enabled = !processing,
            )
            SecondaryActionButton(
                text = stringResource(R.string.stock_in_pick_photo),
                onClick = { galleryLauncher.launch("image/*") },
                icon = Icons.Filled.PhotoLibrary,
                enabled = !processing,
            )
            SecondaryActionButton(
                text = stringResource(R.string.stock_in_add_manually),
                onClick = onAddManually,
                icon = Icons.Filled.Edit,
                enabled = !processing,
            )
        }
    }
}
