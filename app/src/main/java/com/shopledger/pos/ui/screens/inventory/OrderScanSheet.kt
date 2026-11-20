package com.shopledger.pos.ui.screens.inventory

import com.shopledger.pos.ui.text.variantLabel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ShoppingCartCheckout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.shopledger.pos.BuildConfig
import com.shopledger.pos.R
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.scanner.LlmModelState
import com.shopledger.pos.core.scanner.OnDeviceOrderScannerManager
import com.shopledger.pos.core.scanner.OrderScanResult
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.text.UiText
import com.shopledger.pos.ui.theme.ChipTone
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StatusChip
import kotlinx.coroutines.launch
import java.util.Locale

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Unbundled On-Device Receipt / Handwritten / WhatsApp Order List Scanner Sheet.
 * Combines Play Services ML Kit Document Scanner + OCR, an optional downloadable
 * on-device MediaPipe LLM (stored in filesDir, never bundled in APK), and
 * immediate cart population ready for checkout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScanSheet(
    inventory: List<InventoryItemWithVariants>,
    activeCartLabel: String,
    onAddScannedItemsAndOpenCart: (List<CartLineItem>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    val modelState by OnDeviceOrderScannerManager.modelState.collectAsState()
    var rawOrderText by remember { mutableStateOf("") }
    var modelUrlInput by remember { mutableStateOf(OnDeviceOrderScannerManager.DEFAULT_RECOMMENDED_MODEL_URL) }
    var showModelSettings by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastScanResult by remember { mutableStateOf<OrderScanResult?>(null) }

    LaunchedEffect(Unit) {
        OnDeviceOrderScannerManager.refreshModelState(context)
    }

    fun processTextIntoCart(text: String, autoOpenIfClean: Boolean) {
        if (text.isBlank()) {
            AppErrorManager.reportWarning(UiText.Res(R.string.err_empty_order_title), UiText.Res(R.string.msg_empty_order))
            return
        }
        coroutineScope.launch {
            isProcessing = true
            try {
                val result = OnDeviceOrderScannerManager.parseOrderListToCart(
                    rawText = text,
                    inventory = inventory,
                    context = context
                )
                lastScanResult = result

                if (autoOpenIfClean && result.matchedCartLines.isNotEmpty() && result.unmatchedLines.isEmpty()) {
                    val count = result.matchedCartLines.size
                    AppErrorManager.reportSuccess(
                        UiText.Res(R.string.msg_cart_ready_title),
                        UiText.Raw(
                            context.resources.getQuantityString(R.plurals.msg_cart_ready, count, activeCartLabel, count)
                        )
                    )
                    onAddScannedItemsAndOpenCart(result.matchedCartLines)
                } else if (result.matchedCartLines.isEmpty()) {
                    AppErrorManager.reportWarning(
                        UiText.Res(R.string.err_no_match_title),
                        UiText.Res(R.string.msg_no_match)
                    )
                }
            } catch (t: Throwable) {
                AppErrorManager.reportError(UiText.Res(R.string.err_order_parse_title), t)
            } finally {
                isProcessing = false
            }
        }
    }

    val cameraFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            coroutineScope.launch {
                isProcessing = true
                val ocrRes = OnDeviceOrderScannerManager.extractTextFromBitmap(bitmap)
                isProcessing = false
                ocrRes.onSuccess { text ->
                    rawOrderText = text
                    processTextIntoCart(text, autoOpenIfClean = true)
                }.onFailure { err ->
                    AppErrorManager.reportError(UiText.Res(R.string.err_ocr_title), UiText.Res(R.string.stock_in_scan_failed), err)
                }
            }
        }
    }

    val docScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pageUris = scanResult?.pages?.map { it.imageUri }.orEmpty()
            if (pageUris.isNotEmpty()) {
                coroutineScope.launch {
                    isProcessing = true
                    val combined = StringBuilder()
                    var firstError: Throwable? = null
                    for (uri in pageUris) {
                        val ocr = OnDeviceOrderScannerManager.extractTextFromUri(context, uri)
                        ocr.onSuccess { txt ->
                            if (txt.isNotBlank()) combined.appendLine(txt)
                        }.onFailure { if (firstError == null) firstError = it }
                    }
                    isProcessing = false
                    val mergedText = combined.toString().trim()
                    if (mergedText.isNotBlank()) {
                        rawOrderText = mergedText
                        processTextIntoCart(mergedText, autoOpenIfClean = true)
                    } else {
                        AppErrorManager.reportError(
                            UiText.Res(R.string.err_ocr_title),
                            UiText.Res(R.string.stock_in_scan_failed),
                            firstError
                        )
                    }
                }
            }
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                val ocrRes = OnDeviceOrderScannerManager.extractTextFromUri(context, uri)
                isProcessing = false
                ocrRes.onSuccess { text ->
                    rawOrderText = text
                    processTextIntoCart(text, autoOpenIfClean = true)
                }.onFailure { err ->
                    AppErrorManager.reportError(UiText.Res(R.string.err_ocr_title), UiText.Res(R.string.stock_in_scan_failed), err)
                }
            }
        }
    }

    val modelFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                OnDeviceOrderScannerManager.importLocalModelUri(context, uri)
                    .onSuccess { file ->
                        AppErrorManager.reportSuccess(UiText.Res(R.string.msg_model_ready_title), UiText.Res(R.string.msg_model_ready, listOf(file.name)))
                    }
                    .onFailure { err ->
                        AppErrorManager.reportError(UiText.Res(R.string.err_model_import_title), err)
                    }
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
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.scan_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.scan_target, activeCartLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.padding(horizontal = ShopLedgerTokens.spacingXs))
                StatusChip(
                    text = stringResource(if (modelState is LlmModelState.Ready) R.string.scan_ai_ready else R.string.scan_ai_none),
                    tone = if (modelState is LlmModelState.Ready) ChipTone.SUCCESS else ChipTone.NEUTRAL
                )
            }

            // 1. Camera / document scan and gallery
            SectionCard(title = stringResource(R.string.scan_step1)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.scan_camera),
                        icon = Icons.Default.CameraAlt,
                        onClick = {
                            if (activity != null) {
                                val opts = GmsDocumentScannerOptions.Builder()
                                    .setGalleryImportAllowed(true)
                                    .setPageLimit(3)
                                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                                    .build()
                                GmsDocumentScanning.getClient(opts)
                                    .getStartScanIntent(activity)
                                    .addOnSuccessListener { intentSender ->
                                        docScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                    }
                                    .addOnFailureListener {
                                        cameraFallbackLauncher.launch(null)
                                    }
                            } else {
                                cameraFallbackLauncher.launch(null)
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                    )

                    SecondaryActionButton(
                        text = stringResource(R.string.scan_gallery),
                        icon = Icons.Default.PhotoLibrary,
                        onClick = { galleryPickerLauncher.launch("image/*") },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f).heightIn(min = ShopLedgerTokens.primaryActionHeight),
                    )
                }

                if (isProcessing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.scan_reading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 2. Paste / review order list text -> instant cart fill
            SectionCard(title = stringResource(R.string.scan_step2)) {
                OutlinedTextField(
                    value = rawOrderText,
                    onValueChange = { rawOrderText = it },
                    label = { Text(stringResource(R.string.scan_field_lines)) },
                    placeholder = { Text(stringResource(R.string.scan_field_lines_hint)) },
                    singleLine = false,
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                PrimaryActionButton(
                    text = stringResource(R.string.scan_fill_cart, activeCartLabel),
                    icon = Icons.Default.ShoppingCartCheckout,
                    onClick = { processTextIntoCart(rawOrderText, autoOpenIfClean = true) },
                    enabled = rawOrderText.isNotBlank() && !isProcessing,
                )
            }

            // 3. Scan preview and unmatched lines review (shown if partial/manual review needed)
            lastScanResult?.let { result ->
                val hasMatches = result.matchedCartLines.isNotEmpty()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = if (hasMatches) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = if (hasMatches) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(ShopLedgerTokens.spacingL),
                        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.scan_matched,
                                    result.matchedCartLines.size,
                                    result.matchedCartLines.size
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (result.usedOnDeviceLlm) {
                                StatusChip(text = stringResource(R.string.scan_ai_used), tone = ChipTone.INFO)
                            }
                        }

                        if (hasMatches) {
                            result.matchedCartLines.forEach { line ->
                                Text(
                                    text = "• ${line.itemName} (${line.variantSummary.variantLabel()}) × ${
                                        IndianCurrencyFormatter.formatQuantity(line.quantity)
                                    } ${line.unit}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            PrimaryActionButton(
                                text = pluralStringResource(
                                    R.plurals.scan_add_open,
                                    result.matchedCartLines.size,
                                    result.matchedCartLines.size,
                                    activeCartLabel
                                ),
                                icon = Icons.Default.CheckCircle,
                                onClick = { onAddScannedItemsAndOpenCart(result.matchedCartLines) },
                            )
                        }

                        if (result.unmatchedLines.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.scan_unmatched),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            result.unmatchedLines.forEach { u ->
                                Text(
                                    text = "– $u",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }

            // 4. Downloadable on-device AI model (NOT bundled inside the APK)
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.scan_ai_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.scan_ai_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(
                        onClick = { showModelSettings = !showModelSettings },
                        modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                    ) {
                        Text(
                            stringResource(if (showModelSettings) R.string.scan_ai_hide else R.string.scan_ai_manage),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                when (val s = modelState) {
                    is LlmModelState.Downloading -> {
                        val mbDone = String.format(Locale.ENGLISH, "%.1f", s.bytesDownloaded / (1024.0 * 1024.0))
                        val mbTotal = if (s.totalBytes > 0L) {
                            String.format(Locale.ENGLISH, "%.1f", s.totalBytes / (1024.0 * 1024.0))
                        } else "?"
                        Text(
                            text = stringResource(R.string.scan_ai_downloading, mbDone, mbTotal),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            progress = { s.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                    }
                    is LlmModelState.Ready -> {
                        val sizeMb = String.format(Locale.ENGLISH, "%.1f MB", s.sizeBytes / (1024.0 * 1024.0))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.scan_ai_active, s.modelName, sizeMb),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        OnDeviceOrderScannerManager.deleteDownloadedModel(context)
                                        Toast.makeText(context, R.string.msg_model_removed, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        stringResource(R.string.scan_ai_remove),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    is LlmModelState.Error -> {
                        Text(
                            text = stringResource(R.string.msg_model_error) + if (BuildConfig.DEBUG) " (${s.message})" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is LlmModelState.NotDownloaded -> Unit
                }

                if (showModelSettings || modelState is LlmModelState.Error) {
                    OutlinedTextField(
                        value = modelUrlInput,
                        onValueChange = { modelUrlInput = it },
                        label = { Text(stringResource(R.string.scan_ai_url)) },
                        placeholder = { Text(stringResource(R.string.scan_ai_url_hint)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.scan_ai_download),
                            icon = Icons.Default.Download,
                            onClick = {
                                coroutineScope.launch {
                                    OnDeviceOrderScannerManager.downloadModel(context, modelUrlInput)
                                }
                            },
                            enabled = modelState !is LlmModelState.Downloading,
                            modifier = Modifier.weight(1f),
                        )

                        SecondaryActionButton(
                            text = stringResource(R.string.scan_ai_import),
                            icon = Icons.Default.FolderOpen,
                            onClick = { modelFilePickerLauncher.launch(arrayOf("*/*")) },
                            enabled = modelState !is LlmModelState.Downloading,
                            modifier = Modifier.weight(1f).heightIn(min = ShopLedgerTokens.primaryActionHeight),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(ShopLedgerTokens.spacingL))
        }
    }
}
