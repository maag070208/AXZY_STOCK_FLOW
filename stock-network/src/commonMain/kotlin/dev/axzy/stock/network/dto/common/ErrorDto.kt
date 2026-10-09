package dev.axzy.stock.network.dto.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL CONTRATO DE ERROR — es el mismo en TODOS los endpoints
 *
 * ```json
 * { "code": "POLICY_DENIED", "message": "...", "details": { "campo": "motivo" } }
 * ```
 *
 * **El cliente decide con `code`, nunca con `message`.** El mensaje se traduce
 * y se reescribe; un cliente que compara texto se rompe en silencio el día que
 * alguien corrige una tilde. Los códigos son estables y en
 * `SCREAMING_SNAKE_CASE`. La traducción a casos tipados vive en
 * `dev.axzy.stock.network.error.ApiError`.
 *
 * `details` es un objeto de forma **variable por endpoint** (`details.until` en
 * el bloqueo de cuenta, `details.stock` en el borrado de producto,
 * `details.available` en stock insuficiente, `details.pendingLines` al cerrar
 * una factura). Por eso se tipa como [JsonObject] y se lee con los accesores de
 * abajo, en lugar de inventar un DTO por cada error posible. Los accesores
 * devuelven `null` en vez de lanzar: un `details` de forma inesperada debe
 * degradar, no tumbar la pantalla en mitad del manejo del error.
 *
 * Contrato: docs/historico/CONTRATO_API.md §1.3 y §1.7.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Serializable
data class ErrorDto(
    val code: String,
    /**
     * Mensaje legible ya en el idioma del cliente. **No se compara nunca**: es
     * un detalle de presentación y cambia sin avisar.
     */
    val message: String = "",
    val details: JsonObject? = null,
) {
    /** Lee un detalle de texto (`details.until`). */
    fun detailString(name: String): String? =
        details?.get(name)?.jsonPrimitive?.contentOrNull

    /** Lee un detalle entero (`details.stock`, `details.pendingLines`). */
    fun detailLong(name: String): Long? =
        details?.get(name)?.jsonPrimitive?.longOrNull

    /** Lee un detalle decimal (`details.available`). */
    fun detailDouble(name: String): Double? =
        details?.get(name)?.jsonPrimitive?.doubleOrNull

    /** Lee un detalle booleano. */
    fun detailBoolean(name: String): Boolean? =
        details?.get(name)?.jsonPrimitive?.booleanOrNull
}
