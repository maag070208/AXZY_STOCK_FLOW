package dev.axzy.stock.mobile.core.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tokens de los campos de texto.
 *
 * Existen separados de [AppTextField] por el mismo motivo que los colores: el
 * campo se usa en ~20 pantallas y su altura mínima es una decisión de accesibilidad
 * (un campo de menos de 48dp no se acierta con guantes). Si el número vive en 20
 * sitios, en alguno se equivoca.
 *
 * 48dp no es arbitrario: es el objetivo táctil mínimo recomendado, y el operador
 * de almacén trabaja de pie y con prisa.
 */
object InputTokens {
    /** Alto de un campo de una línea. */
    val Height = 48.dp

    /** Alto mínimo de un campo multilínea: nunca menos que el de una línea. */
    val MinHeight = 48.dp

    val ContentPaddingHorizontal = 12.dp
    val ContentPaddingVertical = 10.dp

    val LabelSize = 12.sp
}
