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
        /** Called with the failure (null when the scan simply returned nothing). Never show its text to the user. */
        onError: (Throwable?) -> Unit
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
                        onError(null)
                    }
                }
                .addOnFailureListener { e ->
                    onError(e)
                }
        } catch (e: Exception) {
            onError(e)
        }
    }
}
