package dev.axzy.stock.mobile.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Radios estándar de la app. **Un solo punto de verdad.**
 *
 * La regla del design system es que ninguna pantalla escribe
 * `RoundedCornerShape(12.dp)`: usa `AppShape.row`. El motivo es que un radio
 * suelto no se puede cambiar de golpe, y una app con 6 radios distintos se ve
 * descuidada aunque cada pantalla por separado esté bien.
 */
object AppShape {
    /** Tarjetas y tiles. */
    val card = RoundedCornerShape(16.dp)

    /** Filas pulsables: listas, menús, resultados de búsqueda. */
    val row = RoundedCornerShape(12.dp)

    /** Contenedor cuadrado de un icono dentro de una tarjeta. */
    val icon = RoundedCornerShape(12.dp)

    /** Píldoras: chips de estado, filtros. Un 50% para que sea siempre cápsula. */
    val pill = RoundedCornerShape(50)

    /** Campos de texto y botones. */
    val field = RoundedCornerShape(10.dp)
}
