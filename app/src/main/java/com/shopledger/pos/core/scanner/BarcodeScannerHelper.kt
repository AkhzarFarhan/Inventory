package com.shopledger.pos.core.scanner

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Lightweight zero-Camera-permission barcode & QR scanner helper built on Google Play Services ML Kit Code Scanner.
 */
object BarcodeScannerHelper {

    fun scanBarcode(
        context: Context,
        onScanned: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .enableAutoZoom()
                .build()
            val scanner = GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue?.trim().orEmpty()
                    if (rawValue.isNotEmpty()) {
                        onScanned(rawValue)
                    } else {
                        onError("Empty barcode scanned")
                    }
                }
                .addOnFailureListener { e ->
                    onError(e.localizedMessage ?: "Barcode scanner unavailable on this device")
                }
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Barcode scanner initialization failed")
        }
    }
}
