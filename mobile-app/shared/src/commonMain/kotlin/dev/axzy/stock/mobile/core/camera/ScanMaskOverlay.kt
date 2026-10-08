package dev.axzy.stock.mobile.core.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.axzy.stock.domain.scanner.ScanRect
import dev.axzy.stock.domain.scanner.ScanWindow

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA MÁSCARA DEL ESCÁNER — UNA SOLA FUENTE CON LA VALIDACIÓN
 *
 * Esta función dibuja [ScanWindow.window] del dominio. La validación del escaneo
 * ([dev.axzy.stock.domain.scanner.contains]) comprueba [ScanWindow.effective] del
 * MISMO objeto. No hay ningún número de tamaño escrito en este archivo.
 *
 * POR QUÉ ESTÁ EN COMMONMAIN Y NO EN CADA PLATAFORMA: es el bug M4 del legacy. Si
 * la máscara se dibujara en `androidMain` y en `iosMain` por separado, volvería a
 * haber dos definiciones de la misma ventana y volverían a desalinearse — y esta
 * vez sin que ningún test lo note, porque cada plataforma por separado sería
 * coherente consigo misma.
 *
 * El trazo discontinuo marca la **tolerancia**: el operador ve hasta dónde puede
 * desviarse. El legacy tenía tolerancia cero y no la dibujaba, así que el gesto se
 * sentía arbitrario.
 *
 * @param positionIsValid si el último código detectado cayó dentro de la ventana.
 *   Se pinta el marco en verde o en ámbar para que el operador reciba la misma
 *   respuesta que va a recibir el validador. `null` = aún no hay lectura.
 * @param maskColor el velo sobre lo que queda fuera de la ventana.
 */
@Composable
fun ScanMaskOverlay(
    window: ScanWindow,
    modifier: Modifier = Modifier,
    positionIsValid: Boolean? = null,
    maskColor: Color = Color(0x99000000),
) {
    val accent = when (positionIsValid) {
        true -> Color(0xFF22C55E)
        false -> Color(0xFFF59E0B)
        null -> Color(0xFFFFFFFF)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // `constraints` da píxeles; el dominio habla en normalizado (0..1). La
        // conversión ocurre AQUÍ y no en el dominio, porque el dominio no sabe de
        // pantallas — y no debe saberlo, o no se podría compartir con el backend.
        val fullWidth = constraints.maxWidth.toFloat()
        val fullHeight = constraints.maxHeight.toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (fullWidth <= 0f || fullHeight <= 0f) return@Canvas

            val banda = window.window.toPixels(fullWidth, fullHeight)
            val tolerada = window.effective.toPixels(fullWidth, fullHeight)

            // El velo se pinta como CUATRO rectángulos que rodean la ventana, no
            // con `BlendMode.Clear`: el modo de fusión necesita que el lienzo
            // tenga una capa propia, y en Compose Multiplatform esa capa se
            // comporta distinto en Android y en iOS. Cuatro rectángulos son
            // exactos y no dependen del backend de dibujo.
            val bandaDerecha = banda.left + banda.width
            val bandaAbajo = banda.top + banda.height

            // Arriba
            drawRect(maskColor, Offset.Zero, Size(fullWidth, banda.top))
            // Abajo
            drawRect(maskColor, Offset(0f, bandaAbajo), Size(fullWidth, fullHeight - bandaAbajo))
            // Izquierda (solo la franja vertical de la banda)
            drawRect(maskColor, Offset(0f, banda.top), Size(banda.left, banda.height))
            // Derecha
            drawRect(
                maskColor,
                Offset(bandaDerecha, banda.top),
                Size(fullWidth - bandaDerecha, banda.height),
            )

            // Marco de la ventana efectiva (visual + tolerancia), discontinuo: es
            // el límite REAL de aceptación.
            drawRect(
                color = accent.copy(alpha = 0.55f),
                topLeft = Offset(tolerada.left, tolerada.top),
                size = Size(tolerada.width, tolerada.height),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f),
                ),
            )

            // Marco de la ventana visual, continuo y grueso: es "la raya" que el
            // operador debe centrar.
            drawRect(
                color = accent,
                topLeft = Offset(banda.left, banda.top),
                size = Size(banda.width, banda.height),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Convierte el rectángulo normalizado del dominio a píxeles del layout.
 *
 * Es la ÚNICA conversión del proyecto. Una segunda sería la puerta por la que
 * vuelve el desalineamiento del legacy.
 */
private fun ScanRect.toPixels(frameWidth: Float, frameHeight: Float): Rect = Rect(
    left = (left * frameWidth).toFloat(),
    top = (top * frameHeight).toFloat(),
    width = (width * frameWidth).toFloat(),
    height = (height * frameHeight).toFloat(),
)

/** Rectángulo en píxeles, local para no arrastrar tipos de Compose al dominio. */
private data class Rect(val left: Float, val top: Float, val width: Float, val height: Float)
