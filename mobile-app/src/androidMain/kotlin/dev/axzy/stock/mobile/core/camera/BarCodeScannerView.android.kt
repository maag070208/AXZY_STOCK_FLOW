package dev.axzy.stock.mobile.core.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanRect
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.mobile.core.ui.AppButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ESCÁNER ANDROID — CameraX + ML Kit (modelo *bundled*)
 *
 * LO ÚNICO QUE HACE ESTA PLATAFORMA ES ENTREGAR `(código, posición)`.
 * No valida la posición contra ninguna ventana: eso lo hace el dominio con la
 * MISMA [ScanWindow] que pinta la máscara. Es la corrección del bug M4 del legacy,
 * donde la máscara (300×100) y la validación (250×50) eran dos constantes en dos
 * archivos y nada las mantenía sincronizadas.
 *
 * POR QUÉ EL RECTÁNGULO VA **NORMALIZADO** (0..1) Y NO EN PÍXELES
 * ML Kit devuelve el `boundingBox` en píxeles de la imagen analizada, cuyo tamaño
 * no coincide con el del `PreviewView` ni con el de la pantalla. Si se comparara
 * en píxeles, la misma ventana lógica mediría cosas distintas en un teléfono y en
 * una tablet — que es exactamente cómo el legacy fallaba.
 * ═══════════════════════════════════════════════════════════════════════════
 */

private const val THROTTLE_MS = 2_500L

@Composable
actual fun BarCodeScannerView(
    modifier: Modifier,
    window: ScanWindow,
    isActive: Boolean,
    onScan: (ScanInput) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Permiso de cámara ───────────────────────────────────────────────────
    // Se pide en el momento de abrir el escáner y no al arrancar la app: pedir la
    // cámara a quien solo quería ver una lista es la forma más rápida de que la
    // deniegue para siempre.
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        asked = true
    }

    // La ventana se captura en un `AtomicReference` para que el analizador, que
    // corre en OTRO hilo, lea siempre la vigente y no una copia del primer frame.
    val windowRef = remember { AtomicReference(window) }
    SideEffect { windowRef.set(window) }

    val onScanRef = remember { AtomicReference<(ScanInput) -> Unit> { } }
    SideEffect { onScanRef.set(onScan) }

    val throttle = remember { ScanThrottle(THROTTLE_MS) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val scanner = remember {
        // FORMAT_QR_CODE y FORMAT_CODE_128: el catálogo del almacén usa códigos de
        // producto alfanuméricos, y las etiquetas del legacy son CODE_128. Limitar
        // formatos acelera la detección y reduce los falsos positivos de un código
        // de barras de un envase ajeno.
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128)
                .build(),
        )
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner, isActive, granted) {
        if (!isActive || !granted) return@DisposableEffect onDispose { }

        val future = ProcessCameraProvider.getInstance(context)
        var analysis: ImageAnalysis? = null

        val listener = Runnable {
            val provider = runCatching { future.get() }.getOrNull() ?: return@Runnable
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                // KEEP_ONLY_LATEST: si el dispositivo no da abasto, se descartan
                // fotogramas en lugar de acumular cola. Un escáner que va por
                // detrás del operador es peor que uno que lee menos.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                processFrame(
                    imageProxy = imageProxy,
                    scanner = scanner,
                    throttle = throttle,
                    nowMs = { System.currentTimeMillis() },
                    onCode = { raw, rect -> onScanRef.get()(ScanInput(raw, rect)) },
                )
            }
            analysis = imageAnalysis

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            }
        }
        future.addListener(listener, mainExecutor())

        onDispose {
            runCatching { analysis?.clearAnalyzer() }
            runCatching { future.get().unbindAll() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }

    Box(modifier = modifier) {
        if (granted) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF111827)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Se necesita la cámara para escanear códigos de producto y de ubicación.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Box(modifier = Modifier.padding(top = 16.dp)) {
                        // Se usa el botón estándar, no uno local: la regla del
                        // design system no admite excepciones «porque es un caso
                        // especial». Si el estándar no sirviera aquí, se cambiaría
                        // el estándar, no esta pantalla.
                        AppButton(
                            text = "Permitir cámara",
                            onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        )
                    }
                    if (asked) {
                        Text(
                            text = "Si lo denegaste, actívalo en Ajustes del sistema.",
                            color = Color(0xFF9CA3AF),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processFrame(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    throttle: ScanThrottle,
    nowMs: () -> Long,
    onCode: (String, ScanRect) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val rotation = imageProxy.imageInfo.rotationDegrees
    val imageWidth = imageProxy.width
    val imageHeight = imageProxy.height

    val input = InputImage.fromMediaImage(mediaImage, rotation)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() } ?: return@addOnSuccessListener
            val raw = barcode.rawValue ?: return@addOnSuccessListener
            if (!throttle.shouldEmit(raw, nowMs())) return@addOnSuccessListener

            val box = barcode.boundingBox ?: return@addOnSuccessListener
            onCode(raw, box.normalize(rotation, imageWidth, imageHeight))
        }
        // `addOnCompleteListener` y no `addOnSuccessListener` para cerrar: si se
        // cerrara solo en el camino feliz, un fallo de ML Kit dejaría el
        // ImageProxy abierto y la cámara se congelaría tras el primer error.
        .addOnCompleteListener { imageProxy.close() }
}

/**
 * Pasa el rectángulo de ML Kit (píxeles de la imagen rotada) a coordenadas
 * normalizadas (0..1) del fotograma tal y como lo ve el operador.
 *
 * LA ROTACIÓN NO ES UN DETALLE: con la cámara trasera en un teléfono vertical,
 * `rotationDegrees` es 90 y las dimensiones del rectángulo vienen intercambiadas
 * respecto a la pantalla. Sin este intercambio, la validación compararía un
 * rectángulo vertical contra una banda horizontal y **descartaría todo** — el
 * mismo síntoma que el bug M4, por otra causa.
 */
private fun android.graphics.Rect.normalize(
    rotationDegrees: Int,
    imageWidth: Int,
    imageHeight: Int,
): ScanRect {
    val frameWidth: Int
    val frameHeight: Int
    if (rotationDegrees == 90 || rotationDegrees == 270) {
        frameWidth = imageHeight
        frameHeight = imageWidth
    } else {
        frameWidth = imageWidth
        frameHeight = imageHeight
    }
    if (frameWidth <= 0 || frameHeight <= 0) return ScanRect(0.0, 0.0, 1.0, 1.0)

    val left: Int
    val top: Int
    val right: Int
    val bottom: Int
    when (rotationDegrees) {
        90 -> {
            left = this.top
            top = imageWidth - this.right
            right = this.bottom
            bottom = imageWidth - this.left
        }
        180 -> {
            left = imageWidth - this.right
            top = imageHeight - this.bottom
            right = imageWidth - this.left
            bottom = imageHeight - this.top
        }
        270 -> {
            left = imageHeight - this.bottom
            top = this.left
            right = imageHeight - this.top
            bottom = this.right
        }
        else -> {
            left = this.left
            top = this.top
            right = this.right
            bottom = this.bottom
        }
    }

    val l = (left.toDouble() / frameWidth).coerceIn(0.0, 1.0)
    val t = (top.toDouble() / frameHeight).coerceIn(0.0, 1.0)
    val r = (right.toDouble() / frameWidth).coerceIn(0.0, 1.0)
    val b = (bottom.toDouble() / frameHeight).coerceIn(0.0, 1.0)

    // El dominio exige ancho y alto > 0. Una caja degenerada (que ML Kit puede
    // devolver en un frame borroso) se convierte en una ventana mínima en lugar de
    // lanzar: una excepción en el analizador tumbaría la cámara.
    val width = (r - l).coerceAtLeast(0.001)
    val height = (b - t).coerceAtLeast(0.001)
    return ScanRect(
        left = l.coerceAtMost(1.0 - width),
        top = t.coerceAtMost(1.0 - height),
        width = width,
        height = height,
    )
}

private fun mainExecutor() = Handler(Looper.getMainLooper()).let { handler ->
    java.util.concurrent.Executor { command -> handler.post(command) }
}
