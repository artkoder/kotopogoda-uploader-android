package com.kotopogoda.uploader.feature.pairing.ui

import android.annotation.SuppressLint
import android.net.Uri
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.kotopogoda.uploader.feature.pairing.normalizePairingToken

internal fun parsePairingToken(rawValue: String?): String? {
    val value = rawValue?.trim().orEmpty()
    if (value.isEmpty()) {
        return null
    }

    normalizePairingToken(value)?.let { return it }

    val prefixToken = value.substringAfter(':', missingDelimiterValue = "").takeIf {
        value.startsWith("PAIR:", ignoreCase = true)
    }?.trim()
    normalizePairingToken(prefixToken)?.let { return it }

    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null

    listOf("token", "code").forEach { parameter ->
        val queryToken = uri.getQueryParameter(parameter)
        normalizePairingToken(queryToken)?.let { return it }
    }

    val lastSegment = uri.lastPathSegment
    return normalizePairingToken(lastSegment)
}

@SuppressLint("MissingPermission")
@Composable
fun QrScanner(
    modifier: Modifier = Modifier,
    onTokenDetected: (String) -> Unit,
    onParsingError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember {
        LifecycleCameraController(context).apply {
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }

    val options = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }
    val scanner = remember { BarcodeScanning.getClient(options) }
    val lastRejectedRawValue = remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, scanner) {
        cameraController.setEnabledUseCases(LifecycleCameraController.IMAGE_ANALYSIS)
        cameraController.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(context),
        ) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setImageAnalysisAnalyzer
            }
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val rawValue = barcodes.firstOrNull()?.rawValue
                    if (!rawValue.isNullOrBlank()) {
                        val token = parsePairingToken(rawValue)
                        if (!token.isNullOrEmpty()) {
                            lastRejectedRawValue.value = null
                            onTokenDetected(token)
                        } else if (lastRejectedRawValue.value != rawValue) {
                            lastRejectedRawValue.value = rawValue
                            onParsingError(rawValue)
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        }
        cameraController.bindToLifecycle(lifecycleOwner)
        onDispose {
            cameraController.unbind()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.controller = cameraController
            }
        },
    )
}
