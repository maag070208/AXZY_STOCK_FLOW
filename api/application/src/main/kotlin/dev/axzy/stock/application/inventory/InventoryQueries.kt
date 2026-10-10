package dev.axzy.stock.application.inventory

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.ProductCode
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Las consultas de existencias y del kardex (M02).
 *
 * Son de solo lectura: no abren transacción ni escriben bitácora. La bitácora
 * registra **cambios**, no miradas (docs/seguridad/auditoria.md).
 */
class ListStock(private val queries: StockQueries, private val access: AccessDecider) {

    fun execute(actor: Actor, filter: StockFilter, request: PageRequest, origin: RequestOrigin): Outcome<Page<StockLine>> =
        access.authorizeOr(actor, "inventory.view", origin) { queries.page(filter, request).asRight() }
}

class ListKardex(private val queries: KardexQueries, private val access: AccessDecider) {

    fun execute(actor: Actor, filter: KardexFilter, request: PageRequest, origin: RequestOrigin): Outcome<Page<KardexLine>> =
        access.authorizeOr(actor, "kardex.view", origin) { queries.page(filter, request).asRight() }
}

/** Traduce y valida los filtros de una consulta, que es donde el contrato admite `400`. */
object InventoryFilters {

    fun productCode(raw: String?): Outcome<ProductCode?> {
        if (raw.isNullOrBlank()) return null.asRight()
        return ProductCode.validate(raw).fold(
            onSuccess = { it.asRight() },
            onFailure = {
                failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PRODUCT_CODE",
                    "El código de producto '$raw' no cumple el formato.",
                    mapOf("productCode" to raw),
                )
            },
        )
    }

    fun locationRef(raw: String?): Outcome<LocationRef?> {
        if (raw.isNullOrBlank()) return null.asRight()
        val ref = LocationRef.parse(raw)
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_LOCATION_CODE",
                "El código de ubicación '$raw' no tiene forma de ubicación (por ejemplo 04-B-C o RECEPCION).",
                mapOf("locationCode" to raw),
            )
        return ref.asRight()
    }

    /**
     * Una fecha operativa `AAAA-MM-DD`.
     *
     * Se valida aquí y no se deja al `CAST` de PostgreSQL: un dato del cliente que
     * llegue a la base sale como `500`, y un `500` invita a reintentar para siempre
     * (docs/api/errores.md §1.3-ter).
     */
    fun date(raw: String?, field: String): Outcome<LocalDate?> {
        if (raw.isNullOrBlank()) return null.asRight()
        return try {
            LocalDate.parse(raw.trim()).asRight()
        } catch (_: DateTimeParseException) {
            failure(
                FailureKind.INVALID_REQUEST, "INVALID_FILTER_DATE",
                "La fecha '$raw' no es ISO (AAAA-MM-DD).",
                mapOf(field to raw),
            )
        }
    }

    fun moveType(raw: String?): Outcome<MovementType?> {
        if (raw.isNullOrBlank()) return null.asRight()
        val value = raw.trim().uppercase()
        val type = MovementType.entries.firstOrNull { it.name == value }
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_FILTER",
                "Tipo de movimiento desconocido. Se admiten: ${MovementType.entries.joinToString(", ") { it.name }}.",
                mapOf("moveType" to raw),
            )
        return type.asRight()
    }

    /** Un rango invertido no devuelve nada y parece «no hay movimientos»: se rechaza. */
    fun range(from: LocalDate?, to: LocalDate?): Outcome<Unit> =
        if (from != null && to != null && from > to) {
            failure(
                FailureKind.INVALID_REQUEST, "INVALID_FILTER_RANGE",
                "El rango está invertido: desde $from hasta $to.",
                mapOf("from" to from.toString(), "to" to to.toString()),
            )
        } else {
            Unit.asRight()
        }
}
