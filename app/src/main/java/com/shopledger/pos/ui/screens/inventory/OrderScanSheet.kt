package com.shopledger.pos.ui.screens.inventory

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.scanner.LlmModelState
import com.shopledger.pos.core.scanner.OnDeviceOrderScannerManager
import com.shopledger.pos.core.scanner.OrderScanResult
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.theme.CupertinoCard
import com.shopledger.pos.ui.theme.CupertinoColors
import com.shopledger.pos.ui.theme.CupertinoTextField
import com.shopledger.pos.ui.theme.SemanticPillBadge
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
            AppErrorManager.reportWarning("Empty Order List", "No readable order text detected.")
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
                    AppErrorManager.reportSuccess(
                        title = "Cart Ready",
                        detail = "$activeCartLabel filled with ${result.matchedCartLines.size} scanned items."
                    )
                    onAddScannedItemsAndOpenCart(result.matchedCartLines)
                } else if (result.matchedCartLines.isEmpty()) {
                    AppErrorManager.reportWarning(
                        title = "No Direct Catalog Match",
                        detail = "Review or adjust item names below to match your inventory."
                    )
                }
            } catch (t: Throwable) {
                AppErrorManager.reportError("Order Parsing Failed", t)
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
                    AppErrorManager.reportError("OCR Scan Failed", err)
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
                            title = "Document OCR Failed",
                            throwable = firstError,
                            customDetail = firstError?.localizedMessage ?: "Could not read text from scanned document."
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
                    AppErrorManager.reportError("Gallery Image OCR Failed", err)
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
                        AppErrorManager.reportSuccess("On-Device LLM Ready", "Loaded ${file.name}")
                    }
                    .onFailure { err ->
                        AppErrorManager.reportError("LLM Model Import Failed", err)
                    }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CupertinoColors.SystemGroupedBackground
    ) {
        FloatingAppMessageHost()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Scan Order List / Receipt",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CupertinoColors.LabelPrimary
                    )
                    Text(
                        text = "Target: $activeCartLabel • Cart fills automatically",
                        fontSize = 12.sp,
                        color = CupertinoColors.LabelSecondary
                    )
                }

                SemanticPillBadge(
                    text = if (modelState is LlmModelState.Ready) "On-Device LLM Ready" else "Unbundled AI",
                    containerColor = if (modelState is LlmModelState.Ready) CupertinoColors.EmeraldTint else CupertinoColors.SystemBlueTint,
                    contentColor = if (modelState is LlmModelState.Ready) CupertinoColors.EmeraldGreen else CupertinoColors.SystemBlue
                )
            }

            // 1. Camera / Document Scan & Gallery Actions
            CupertinoCard {
                Text(
                    text = "1. Scan Photo or Receipt",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
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
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CupertinoColors.SystemBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1.1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Camera Scan", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = { galleryPickerLauncher.launch("image/*") },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(0.9f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Gallery", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                if (isProcessing) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Reading order list & matching inventory...",
                            fontSize = 12.sp,
                            color = CupertinoColors.SystemBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 2. Paste / Review Order List Text -> Instant Cart Fill
            CupertinoCard {
                Text(
                    text = "2. Order List Text (Scanned or Copied from WhatsApp)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                CupertinoTextField(
                    value = rawOrderText,
                    onValueChange = { rawOrderText = it },
                    label = "Order Lines",
                    placeholder = "1. White Cement 1kg x 5\n2. 4 inch SS Kabza - 12 pcs\n3. Fevicol SH 2",
                    singleLine = false,
                    capitalization = KeyboardCapitalization.Sentences
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { processTextIntoCart(rawOrderText, autoOpenIfClean = true) },
                    enabled = rawOrderText.isNotBlank() && !isProcessing,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoColors.EmeraldGreen,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ShoppingCartCheckout, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Text & Fill $activeCartLabel", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // 3. Scan Preview & Unmatched Lines Review (shown if partial/manual review needed)
            lastScanResult?.let { result ->
                CupertinoCard(
                    containerColor = if (result.matchedCartLines.isNotEmpty()) CupertinoColors.EmeraldTint else CupertinoColors.AmberTint
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Matched ${result.matchedCartLines.size} Item(s)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.LabelPrimary
                        )
                        if (result.usedOnDeviceLlm) {
                            SemanticPillBadge(
                                text = "On-Device LLM",
                                containerColor = CupertinoColors.SystemBlueTint,
                                contentColor = CupertinoColors.SystemBlue
                            )
                        }
                    }

                    if (result.matchedCartLines.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        result.matchedCartLines.forEach { line ->
                            Text(
                                text = "• ${line.itemName} (${line.variantSummary}) × ${
                                    IndianCurrencyFormatter.formatQuantity(line.quantity)
                                } ${line.unit}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.LabelPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onAddScannedItemsAndOpenCart(result.matchedCartLines) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CupertinoColors.SystemBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Add ${result.matchedCartLines.size} Items & Open $activeCartLabel",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (result.unmatchedLines.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Unmatched Lines (edit above to retry):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.AmberWarning
                        )
                        result.unmatchedLines.forEach { u ->
                            Text(
                                text = "– $u",
                                fontSize = 11.sp,
                                color = CupertinoColors.LabelSecondary
                            )
                        }
                    }
                }
            }

            // 4. Downloadable On-Device LLM Model Card (NOT bundled inside APK)
            CupertinoCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = CupertinoColors.CipherIndigo,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Downloadable On-Device LLM",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.LabelPrimary
                            )
                            Text(
                                text = "Optional offline AI model (not bundled in APK) for complex messy lists",
                                fontSize = 11.sp,
                                color = CupertinoColors.LabelSecondary
                            )
                        }
                    }
                    TextButton(onClick = { showModelSettings = !showModelSettings }) {
                        Text(if (showModelSettings) "Hide" else "Manage", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                when (val s = modelState) {
                    is LlmModelState.Downloading -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        val mbDone = String.format(Locale.ENGLISH, "%.1f", s.bytesDownloaded / (1024.0 * 1024.0))
                        val mbTotal = if (s.totalBytes > 0L) {
                            String.format(Locale.ENGLISH, "%.1f", s.totalBytes / (1024.0 * 1024.0))
                        } else "?"
                        Text(
                            text = "Downloading On-Device Model: $mbDone / $mbTotal MB",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.SystemBlue
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { s.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                    is LlmModelState.Ready -> {
                        val sizeMb = String.format(Locale.ENGLISH, "%.1f MB", s.sizeBytes / (1024.0 * 1024.0))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CupertinoColors.EmeraldTint)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active: ${s.modelName} ($sizeMb)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.EmeraldGreen,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    OnDeviceOrderScannerManager.deleteDownloadedModel(context)
                                    Toast.makeText(context, "On-device LLM removed", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Model",
                                    tint = CupertinoColors.DestructiveRed,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text("Remove", fontSize = 11.sp, color = CupertinoColors.DestructiveRed)
                            }
                        }
                    }
                    is LlmModelState.Error -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Model Error: ${s.message}",
                            fontSize = 11.sp,
                            color = CupertinoColors.DestructiveRed
                        )
                    }
                    is LlmModelState.NotDownloaded -> Unit
                }

                if (showModelSettings || modelState is LlmModelState.Error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CupertinoTextField(
                        value = modelUrlInput,
                        onValueChange = { modelUrlInput = it },
                        label = "MediaPipe LLM Task/Bin Download URL",
                        placeholder = "https://.../model.task",
                        capitalization = KeyboardCapitalization.None
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    OnDeviceOrderScannerManager.downloadModel(context, modelUrlInput)
                                }
                            },
                            enabled = modelState !is LlmModelState.Downloading,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download LLM", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { modelFilePickerLauncher.launch(arrayOf("*/*")) },
                            enabled = modelState !is LlmModelState.Downloading,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import File", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
