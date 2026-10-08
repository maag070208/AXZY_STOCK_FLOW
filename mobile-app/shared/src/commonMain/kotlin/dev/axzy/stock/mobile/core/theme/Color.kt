package dev.axzy.stock.mobile.core.theme

import androidx.compose.ui.graphics.Color

/**
 * Tokens de color.
 *
 * REGLA DEL DESIGN SYSTEM (ver AGENTS.md del ejemplo real): las pantallas usan
 * `MaterialTheme.colorScheme.*` y, para lo que **no** es un rol de Material
 * (`error`, `primary`…), un color semántico de aquí. Nunca un literal.
 *
 * El motivo de no hardcodear en pantalla no es estético: un color literal repetido
 * en 30 sitios convierte un cambio de marca en 30 ediciones, y garantiza que
 * alguno se quede atrás. Y los colores de ESTADO tienen que ser coherentes entre
 * pantallas o el operador interpreta mal una etiqueta.
 */
object AppColors {

    // ── Marca ────────────────────────────────────────────────────────────────
    // Azul de almacén: no es decorativo. El operador trabaja con guantes y bajo
    // luz de bodega; un contraste alto entre acción y fondo reduce pulsaciones
    // equivocadas, y el error de pulsación aquí mueve inventario.
    val BrandPrimary = Color(0xFF1D4ED8)
    val BrandPrimaryDark = Color(0xFF1E3A8A)
    val BrandPrimaryContainer = Color(0xFFDBEAFE)
    val BrandOnPrimaryContainer = Color(0xFF172554)
    val BrandAccent = Color(0xFF0891B2)
    val BrandOnAccent = Color(0xFFFFFFFF)
    val BrandSecondaryContainer = Color(0xFFCFFAFE)
    val BrandOnSecondaryContainer = Color(0xFF083344)

    val Background = Color(0xFFF4F6FA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFEFF2F7)
    val Outline = Color(0xFFD3DAE6)

    val TextPrimary = Color(0xFF111827)
    val TextMuted = Color(0xFF5B6472)
    val TextFaint = Color(0xFF98A2B3)
    val OnPrimary = Color(0xFFFFFFFF)

    // ── Semánticos de estado ─────────────────────────────────────────────────
    // No son marca: significan algo y se usan en toda la app con el mismo
    // significado (éxito, aviso, peligro, informativo, neutro).
    val Success = Color(0xFF0F9D58)
    val Warning = Color(0xFFB45309)
    val Danger = Color(0xFFD92D20)
    val Info = Color(0xFF2563EB)
    val Purple = Color(0xFF7C3AED)
    val Slate = Color(0xFF64748B)

    /**
     * Color de un estado del dominio, por su nombre de enum.
     *
     * POR QUÉ POR NOMBRE Y NO POR ENUM: el enum de estado de la línea de factura
     * vive en `stock-domain`, y `stock-domain` **no puede depender de Compose**
     * (la guardia de arquitectura falla si lo hace). Así que la traducción
     * estado → color tiene que ocurrir aquí, y ocurre en un solo sitio para que
     * una línea `DISCREPANCY` se pinte igual en la lista y en el detalle.
     */
    fun statusColor(status: String): Color = when (status.uppercase()) {
        // Correcto / terminado
        "COMPLETED", "APPROVED", "LOCATED", "PACKAGED", "PRINTED", "RECEIVED", "UP", "SUCCESS" ->
            Success
        // En proceso / informativo
        "IN_PROGRESS", "CAPTURED", "ASSIGNED", "SENT", "IN_TRANSIT", "SHELF" -> Info
        // Requiere acción de alguien
        "PENDING", "PENDING_TO_LOCATE", "PENDING_APPROVAL", "DISPATCHED", "WARNING" -> Warning
        // Problema / parado
        "DISCREPANCY", "CANCELLED", "FAILED", "DOWN", "DANGER" -> Danger
        else -> Slate
    }
}
