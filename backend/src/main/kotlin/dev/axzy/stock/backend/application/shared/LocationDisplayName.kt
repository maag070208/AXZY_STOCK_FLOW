package dev.axzy.stock.backend.application.shared

/**
 * El nombre visible de una ubicación.
 *
 * **Lo compone el servidor, no el cliente.** La base guarda `hall`, `code` y `position` por
 * separado y el operador ve una sola etiqueta: si mañana cambia el formato, no hay que tocar
 * el backend, la web y el móvil a la vez.
 *
 * Las ubicaciones especiales tienen nombre propio y no una concatenación: sus tres partes son
 * iguales, así que `RECEPCION-RECEPCION-RECEPCION` no diría nada. **El nombre visible no es
 * una concatenación, es una decisión**, y por eso vive en una función y no en cada consulta.
 */
fun locationDisplayName(hall: String, code: String, position: String, kind: String): String =
    when (kind) {
        "RECEIVING" -> "RECEPCION"
        "UNASSIGNED" -> "SIN-UBICAR"
        else -> "$hall-$code-$position"
    }
