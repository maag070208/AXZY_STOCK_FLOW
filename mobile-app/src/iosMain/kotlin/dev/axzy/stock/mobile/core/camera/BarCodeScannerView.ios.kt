package dev.axzy.stock.mobile.core.camera

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanRect
import dev.axzy.stock.domain.scanner.ScanWindow
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObject
import platform.AVFoundation.AVMetadataObjectTypeCode128Code
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ESCÁNER iOS — AVFoundation (captura) + metadatos (lectura)
 *
 * LO ÚNICO QUE HACE ESTA PLATAFORMA ES ENTREGAR `(código, posición)`, igual que
 * la de Android. La ventana de validación es la MISMA instancia de [ScanWindow]
 * que pinta la máscara en commonMain: **en este archivo no hay ningún tamaño de
 * ventana**, y eso es la corrección del bug M4 del legacy (máscara de 300×100 y
 * validación de 250×50 en dos archivos distintos).
 *
 * POR QUÉ NO SE USA VISION AQUÍ
 * `AVCaptureMetadataOutput` ya devuelve el código y su rectángulo, y además
 * `previewLayer.transformedMetadataObjectForMetadataObject` lo entrega en
 * coordenadas de PANTALLA, que es justo lo que hace falta para comparar con la
 * máscara. Vision sería un segundo camino de detección con su propio sistema de
 * coordenadas (origen abajo a la izquierda) y, por tanto, una segunda oportunidad
 * de desalinear la ventana. Se usa el camino que ya viene transformado.
 * ═══════════════════════════════════════════════════════════════════════════
 */

private const val THROTTLE_MS = 2_500L

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun BarCodeScannerView(
    modifier: Modifier,
    window: ScanWindow,
    isActive: Boolean,
    onScan: (ScanInput) -> Unit,
) {
    // La ventana se guarda en un objeto mutable para que el delegado de
    // Objective-C —que vive FUERA del árbol de Compose y no se recompone— siempre
    // lea la vigente y no la capturada en el primer fotograma.
    val windowRef = remember { ValueHolder<ScanWindow>(window) }
    SideEffect { windowRef.value = window }

    val onScanRef = remember { ValueHolder<(ScanInput) -> Unit> { } }
    SideEffect { onScanRef.value = onScan }

    val throttle = remember { ScanThrottle(THROTTLE_MS) }
    val controller = remember { ScannerController(throttle, windowRef, onScanRef) }

    // El ciclo de vida de la sesión se ata a `isActive`: dejar la cámara encendida
    // detrás del modal de confirmación hace que el escáner lea un segundo código
    // mientras el operador confirma el primero, y eso es un movimiento de
    // inventario que nadie ordenó.
    DisposableEffect(isActive) {
        if (isActive) controller.start()
        onDispose { controller.stop() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val view = controller.view
        if (view != null) {
            UIKitView(
                factory = { view },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Sin cámara (simulador, permiso denegado en Ajustes) se dice qué pasa
            // en lugar de dejar un rectángulo negro mudo: el operador necesita
            // saber si el problema es suyo o de la app.
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
                        text = "No se pudo abrir la cámara. Revisa el permiso de cámara " +
                            "en Ajustes del sistema.",
                        color = Color(0xFF9CA3AF),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Contenedor mutable: el puente entre Compose (recompone) y ObjC (no). */
private class ValueHolder<T>(var value: T)

/**
 * Sesión de captura y traducción de cada código a [ScanInput].
 *
 * La sesión se configura UNA VEZ (en el primer `start`) y se reutiliza en los
 * siguientes: `AVCaptureSession` tarda ~200 ms en arrancar y reconfigurarla en cada
 * apertura del escáner se nota como un retardo al abrir la pantalla.
 */
@OptIn(ExperimentalForeignApi::class)
private class ScannerController(
    private val throttle: ScanThrottle,
    private val windowRef: ValueHolder<ScanWindow>,
    private val onScanRef: ValueHolder<(ScanInput) -> Unit>,
) {
    private val session = AVCaptureSession()
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session)
    private val delegate = MetadataDelegate()

    var view: UIView? = null
        private set

    private var running = false

    fun start() {
        if (view == null && !configure()) return
        if (running) return
        dispatch_async(dispatch_get_main_queue()) {
            // `startRunning` bloquea el hilo: nunca en el principal.
            session.startRunning()
        }
        running = true
    }

    fun stop() {
        if (!running) return
        dispatch_async(dispatch_get_main_queue()) { session.stopRunning() }
        running = false
    }

    private fun configure(): Boolean {
        // Cámara trasera explícita: el operador apunta al estante, no a su cara.
        // Sin pedirla, en un dispositivo con dos cámaras puede elegirse la frontal.
        val device = AVCaptureDevice.deviceWithMediaType(AVMediaTypeVideo, AVCaptureDevicePositionBack)
            ?: AVCaptureDevice.deviceWithMediaType(AVMediaTypeVideo)
            ?: return false

        val input = runCatching { AVCaptureDeviceInput.deviceInputWithDevice(device, null) }.getOrNull()
            ?: return false

        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetHigh
        if (session.canAddInput(input)) session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (session.canAddOutput(output)) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
            // QR y CODE_128: son las dos simbologías del almacén. Añadir decenas
            // cuesta velocidad de decodificación sin aportar nada.
            output.metadataObjectTypes = listOf(
                AVMetadataObjectTypeQRCode,
                AVMetadataObjectTypeCode128Code,
            )
        }
        session.commitConfiguration()

        val host = UIView(frame = CGRectMake(0.0, 0.0, 360.0, 640.0))
        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
        previewLayer.frame = host.bounds
        host.layer.addSublayer(previewLayer)
        view = host
        return true
    }

    /**
     * Delegado de metadatos.
     *
     * `transformedMetadataObjectForMetadataObject` devuelve el rectángulo en
     * coordenadas de PANTALLA (mismo espacio que la máscara), así que normalizar es
     * una división. Es la razón de no usar Vision aquí: su `bounds` viene con el
     * origen abajo a la izquierda y obligaría a una segunda conversión —y a una
     * segunda oportunidad de desalinear la ventana.
     */
    private inner class MetadataDelegate : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

        override fun captureOutput(
            output: AVCaptureOutput,
            didOutputMetadataObjects: List<*>,
            fromConnection: AVCaptureConnection?,
        ) {
            val first = didOutputMetadataObjects.firstOrNull() as? AVMetadataObject ?: return

            val enPantalla = previewLayer.transformedMetadataObjectForMetadataObject(first)
                as? AVMetadataMachineReadableCodeObject ?: return

            val raw = enPantalla.stringValue ?: return
            if (!throttle.shouldEmit(raw, nowMs())) return

            val frame = previewLayer.bounds
            val bounds = enPantalla.bounds
            val frameW = frame.useContents { size.width }
            val frameH = frame.useContents { size.height }
            if (frameW <= 0.0 || frameH <= 0.0) return

            val rect = bounds.useContents {
                val left = (origin.x / frameW).coerceIn(0.0, 1.0)
                val top = (origin.y / frameH).coerceIn(0.0, 1.0)
                val width = (size.width / frameW).coerceIn(0.001, 1.0 - left)
                val height = (size.height / frameH).coerceIn(0.001, 1.0 - top)
                ScanRect(left = left, top = top, width = width, height = height)
            }

            onScanRef.value(ScanInput(raw, rect))
        }
    }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
}
