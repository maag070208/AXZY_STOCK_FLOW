package dev.axzy.stock.domain.error

import kotlinx.serialization.Serializable

/**
 * Errores del dominio.
 *
 * Se modelan como un tipo sellado en lugar de excepciones por tres razones:
 *
 *  1. **Son parte del contrato**, no un accidente: "la cantidad excede la
 *     factura" es un resultado de negocio esperado, no un fallo del programa.
 *  2. **Obligan a manejarlos**: un `when` exhaustivo sobre [DomainError] no
 *     compila si aparece un caso nuevo sin tratar.
 *  3. **Se pueden mapear a códigos HTTP** sin perder información, y cada uno
 *     tiene un código estable que el móvil y la web usan para decidir qué
 *     mensaje mostrar. Un "acceso denegado" genérico no es accionable; "no
 *     tienes permiso sobre esta bodega" sí.
 *
 * Referencia: docs/historico/PLAN_ALMACEN_INTEGRAL.md §4 (códigos de error) y
 * docs/historico/PLAN_REFACTOR_MEJORAS.md R-20 (el legacy devolvía HTTP 200 en errores de
 * negocio y 500 con cuerpo `{}`).
 */
@Serializable
sealed interface DomainError {

    /** Código estable para el cliente. No cambia entre versiones. */
    val code: String

    /** Mensaje legible. Es un detalle de presentación; el cliente usa [code]. */
    val message: String

    // ── Transiciones de estado ──────────────────────────────────────────────

    /**
     * La transición pedida no es válida desde el estado actual.
     * El legacy permitía saltos ilegales porque los estados eran strings libres.
     */
    data class InvalidTransition(
        val entity: String,
        val from: String,
        val action: String,
        override val message: String = "Cannot '$action' from status '$from'",
    ) : DomainError {
        override val code = "INVALID_TRANSITION"
    }

    // ── Cantidades ──────────────────────────────────────────────────────────

    /** Se intentó ubicar o surtir más de lo que la línea admite. */
    data class ExceedsLineQuantity(
        val requested: Long,
        val available: Long,
        override val message: String =
            "The requested quantity exceeds what is available on the line",
    ) : DomainError {
        override val code = "EXCEEDS_LINE_QUANTITY"
    }

    /**
     * La cantidad y las unidades por caja no cuadran (decisión R-80).
     * Una factura de 100 piezas con cajas de 30 es siempre un dato mal capturado.
     */
    data class BoxesDoNotDivideEvenly(
        val quantity: Long,
        val unitsPerBox: Long,
        val remainder: Long,
        override val message: String =
            "The quantity is not divisible by units per box: " +
                "a remainder of $remainder would be left",
    ) : DomainError {
        override val code = "BOXES_DO_NOT_DIVIDE_EVENLY"
    }

    /**
     * Las cajas declaradas no caben en la cantidad declarada.
     * Ejemplo: 80 piezas con 2 cajas de 50 (serían 100).
     */
    data class InconsistentBoxCount(
        val quantity: Long,
        val boxes: Long,
        val unitsPerBox: Long,
        override val message: String =
            "$boxes box(es) of $unitsPerBox do not fit in $quantity units",
    ) : DomainError {
        override val code = "INCONSISTENT_BOX_COUNT"
    }

    /** Cantidad negativa o cero donde se exige positiva. */
    data class InvalidQuantity(
        override val message: String,
    ) : DomainError {
        override val code = "INVALID_QUANTITY"
    }

    // ── Discrepancias y aprobación ──────────────────────────────────────────

    /**
     * La línea tiene una discrepancia sin aprobar.
     *
     * Es el error que hace efectiva la regla B1: **no se puede ubicar
     * mercancía cuya diferencia con la factura no haya sido autorizada**.
     * En el legacy este control no existía.
     */
    data class DiscrepancyNotApproved(
        val difference: Long,
        override val message: String =
            "The line has a difference of $difference unapproved units. " +
                "A supervisor must authorize it before the goods are located.",
    ) : DomainError {
        override val code = "DISCREPANCY_NOT_APPROVED"
    }

    /**
     * Quien capturó intenta aprobar su propia discrepancia (decisión R-74).
     *
     * Es el control interno más importante del flujo de recepción: evita que
     * alguien tape su propio error de conteo.
     */
    data class SegregationOfDuties(
        override val message: String =
            "You cannot approve a discrepancy you counted yourself. " +
                "Requires another person's authorization.",
    ) : DomainError {
        override val code = "SEGREGATION_OF_DUTIES"
    }

    /** Se intentó aprobar algo que no está en estado de aprobación. */
    data class NotPendingApproval(
        val currentStatus: String,
        override val message: String =
            "The line is not awaiting approval (current status: $currentStatus)",
    ) : DomainError {
        override val code = "NOT_PENDING_APPROVAL"
    }

    /** Se exige un motivo y no se proporcionó (invariante I6). */
    data class ReasonRequired(
        val context: String,
        override val message: String = "A reason is required for $context",
    ) : DomainError {
        override val code = "REASON_REQUIRED"
    }

    // ── Inventario ──────────────────────────────────────────────────────────

    /** La operación dejaría stock negativo (invariante I2). */
    data class InsufficientStock(
        val available: Long,
        val requested: Long,
        val location: String,
        override val message: String =
            "Insufficient stock at $location: there are $available and $requested are requested",
    ) : DomainError {
        override val code = "INSUFFICIENT_STOCK"
    }

    /**
     * El origen y el destino de un movimiento son iguales.
     * En el legacy esto no se validaba y generaba movimientos sin efecto.
     */
    data class SameSourceAndDestination(
        val location: String,
        override val message: String = "The source and destination cannot be the same location",
    ) : DomainError {
        override val code = "SAME_SOURCE_AND_DESTINATION"
    }

    // ── Documentos ──────────────────────────────────────────────────────────

    /** El documento tiene líneas pendientes y no se puede cerrar. */
    data class HasPendingLines(
        val pendingCount: Int,
        override val message: String =
            "The document has $pendingCount unresolved line(s) and cannot be closed",
    ) : DomainError {
        override val code = "HAS_PENDING_LINES"
    }

    /**
     * Se intentó borrar un documento con efecto en inventario.
     * No se borra: se **anula** con un movimiento compensatorio (R-57 y R-68).
     */
    data class CannotDeleteWithInventoryEffect(
        override val message: String =
            "This document affects inventory and cannot be deleted: " +
                "must be voided with a compensating movement.",
    ) : DomainError {
        override val code = "CANNOT_DELETE_WITH_INVENTORY_EFFECT"
    }

    /** Conflicto de unicidad (número de factura u orden ya existe). */
    data class Duplicate(
        val entity: String,
        val value: String,
        override val message: String = "$entity with value '$value' already exists",
    ) : DomainError {
        override val code = "DUPLICATE"
    }

    /** El recurso no existe. */
    data class NotFound(
        val entity: String,
        val id: String,
        override val message: String = "No $entity found with id '$id'",
    ) : DomainError {
        override val code = "NOT_FOUND"
    }

    // ── Acceso (detalle en docs/historico/PLAN_ROLES_PERMISOS_POLITICAS.md) ────────────────

    /** RBAC no alcanza: el usuario no tiene el permiso. */
    data class PermissionDenied(
        val permission: String,
        override val message: String = "You do not have permission for this action",
    ) : DomainError {
        override val code = "PERMISSION_DENIED"
    }

    /** Tiene el permiso, pero no para **ese** recurso concreto. */
    data class ScopeInsufficient(
        val permission: String,
        val scope: String,
        override val message: String =
            "You do not have permission over this resource (scope: $scope)",
    ) : DomainError {
        override val code = "SCOPE_INSUFFICIENT"
    }

    /** Una política ABAC denegó la acción. */
    data class PolicyDenied(
        val policyName: String,
        override val message: String = "Denied by policy '$policyName'",
    ) : DomainError {
        override val code = "POLICY_DENIED"
    }

    /**
     * Hay políticas aplicables y ninguna `ALLOW` coincidió.
     * Es lo que hace que ABAC suba el listón en lugar de bajarlo.
     */
    data class PolicyRequired(
        override val message: String = "This action requires specific authorization",
    ) : DomainError {
        override val code = "POLICY_REQUIRED"
    }

    // ── Traslados entre bodegas ─────────────────────────────────────────────

    /**
     * Un traslado con la misma bodega de origen y de destino.
     *
     * La base lo impone con dos `CHECK` (en `transfers` y en `stock_in_transit`),
     * y el dominio lo comprueba **antes** de tocar la base para poder dar un
     * `422` que explique el campo en lugar de un error de restricción.
     *
     * **Por qué importa de verdad:** un traslado de una bodega a sí misma se
     * despacha, la mercancía queda «en tránsito» hacia el mismo sitio, y el total
     * del inventario **no cambia** — así que ningún reporte lo detecta. La
     * mercancía desaparece de las ubicaciones y no llega a ninguna parte, sin que
     * nada se ponga en rojo.
     */
    data class SameWarehouse(
        override val message: String =
            "The transfer must go to a warehouse other than the origin.",
    ) : DomainError {
        override val code = "SAME_WAREHOUSE"
    }

    /**
     * Un traslado sin líneas.
     *
     * Despachar un traslado vacío crea una deuda visible en `in-transit` que no
     * corresponde a ninguna mercancía: aparece en el informe de tránsito y nunca
     * se puede cerrar de forma útil.
     */
    data class EmptyTransfer(
        override val message: String = "The transfer has no lines.",
    ) : DomainError {
        override val code = "EMPTY_TRANSFER"
    }

    // ── Surtido de órdenes ──────────────────────────────────────────────────

    /**
     * La orden ya estaba confirmada (contrato §6, `ORDER_ALREADY_CONFIRMED`).
     *
     * **Confirmar dos veces descontaría el stock dos veces**: la primera deja las
     * líneas sin cantidad pendiente y la segunda volvería a restar. El total del
     * producto bajaría el doble y el descuadre parecería una venta.
     */
    data class OrderAlreadyConfirmed(
        val orderNumber: String,
        override val message: String =
            "La orden $orderNumber ya estaba confirmada. Confirmarla otra vez " +
                "would deduct the stock twice.",
    ) : DomainError {
        override val code = "ORDER_ALREADY_CONFIRMED"
    }

    /**
     * Se intentó exportar una orden que todavía no se confirmó.
     *
     * Exportar al ERP algo que aún no se ha surtido mandaría al sistema externo
     * una salida que no existe.
     */
    data class OrderNotConverted(
        val orderNumber: String,
        override val message: String =
            "Order $orderNumber is not confirmed: there is nothing to export.",
    ) : DomainError {
        override val code = "ORDER_NOT_CONVERTED"
    }

    /**
     * Se pidió empacar más de lo que queda pendiente en la línea.
     *
     * La base lo impone con `order_lines_supplied_not_exceeds`
     * (`supplied_quantity <= quantity`), pero el dominio lo comprueba antes para
     * poder decir **cuánto queda**, que es lo que el operador necesita saber.
     */
    data class ExceedsPendingQuantity(
        val requested: Long,
        val pending: Long,
        override val message: String =
            "Attempted to fulfill $requested units and only $pending remain pending " +
                "on the line.",
    ) : DomainError {
        override val code = "EXCEEDS_PENDING_QUANTITY"
    }

    // ── Etiquetas ───────────────────────────────────────────────────────────

    /**
     * Falta un dato que la plantilla necesita para imprimir.
     *
     * **No se resuelve dejando el marcador ni sustituyéndolo por vacío.** Lo
     * primero imprime `{{productCode}}` en la etiqueta del estante; lo segundo
     * imprime una etiqueta que **parece correcta y no lo está**, con un hueco que
     * nadie distingue de una plantilla mal diseñada.
     */
    data class MissingLabelField(
        val fields: List<String>,
        override val message: String =
            "Missing data for the label: ${fields.joinToString(", ")}. " +
                "The template needs them, and incomplete labels are not printed.",
    ) : DomainError {
        override val code = "MISSING_LABEL_FIELD"
    }

    /**
     * Una etiqueta ya impresa no se imprime otra vez por un reintento.
     *
     * El contrato lo exige con `409` en `PATCH /v1/print-jobs/{id}`: si la
     * impresora reintenta una llamada que ya funcionó, el trabajo no se marca dos
     * veces ni se gasta una segunda etiqueta.
     */
    data class LabelAlreadyPrinted(
        val jobId: Long,
        override val message: String =
            "Print job $jobId is already marked as printed; " +
                "a retry must not print twice.",
    ) : DomainError {
        override val code = "LABEL_ALREADY_PRINTED"
    }

    // ── Catálogo: productos, ubicaciones y bodegas (M01) ────────────────────
    //
    // Los códigos son los de la lista cerrada de docs/api/errores.md §1.3-bis:
    // ninguno se inventa aquí. Un código nuevo se añade al catálogo de errores
    // ANTES de usarse, y por eso este bloque se lee junto a ese documento.

    /** El código de producto no cumple el alfabeto `0-9A-Z` ni la longitud. */
    data class InvalidProductCode(
        val raw: String,
        val detail: String,
        override val message: String = "Invalid product code ('$raw'): $detail",
    ) : DomainError {
        override val code = "INVALID_PRODUCT_CODE"
    }

    /** La unidad no está en el catálogo cerrado (`units_of_measure`). */
    data class UnknownUnitMeasure(
        val unit: String,
        override val message: String =
            "Unknown unit of measure '$unit'. It must exist in the unit catalog.",
    ) : DomainError {
        override val code = "UNKNOWN_UNIT_MEASURE"
    }

    /** El número de niveles de una ubicación no es positivo. */
    data class InvalidLevels(
        val levels: Int,
        override val message: String = "The number of levels must be at least 1, got $levels",
    ) : DomainError {
        override val code = "INVALID_LEVELS"
    }

    /** El tipo de ubicación no es `SHELF` ni `RECEIVING`. */
    data class InvalidLocationKind(
        val raw: String,
        override val message: String = "Invalid location kind '$raw': it must be SHELF or RECEIVING",
    ) : DomainError {
        override val code = "INVALID_LOCATION_KIND"
    }

    /** Una de las tres partes de la ubicación no cumple el formato canónico. */
    data class InvalidLocationPart(
        val part: String,
        val raw: String,
        val detail: String,
        override val message: String = "Invalid location part '$part' ('$raw'): $detail",
    ) : DomainError {
        override val code = "INVALID_LOCATION_PART"
    }

    /** La bodega referenciada no existe. */
    data class UnknownWarehouse(
        val id: Long,
        override val message: String = "Warehouse $id does not exist",
    ) : DomainError {
        override val code = "UNKNOWN_WAREHOUSE"
    }

    /**
     * El `external_id` del archivo no es numérico.
     *
     * **Se rechaza la fila en lugar de guardarla nula**: el `external_id` es la
     * referencia cruzada con el ERP, y perderla en silencio deja productos que
     * después no se pueden conciliar.
     */
    data class InvalidExternalId(
        val raw: String,
        override val message: String =
            "The external identifier '$raw' is not numeric; the reference to the ERP would be lost.",
    ) : DomainError {
        override val code = "IMPORT_INVALID_EXTERNAL_ID"
    }

    /** Se intentó cambiar el código de un producto: está impreso en las etiquetas. */
    data class ProductCodeImmutable(
        val productCode: String,
        override val message: String =
            "The product code '$productCode' cannot be changed: it is printed on labels and shelves.",
    ) : DomainError {
        override val code = "PRODUCT_CODE_IMMUTABLE"
    }

    /** Se intentó mover una ubicación de bodega: su stock cambiaría de bodega sin movimiento. */
    data class WarehouseImmutable(
        val locationId: Long,
        override val message: String =
            "Location $locationId cannot be moved to another warehouse: its stock would change warehouse without a movement.",
    ) : DomainError {
        override val code = "WAREHOUSE_IMMUTABLE"
    }

    /** Ya existe un producto con ese código. */
    data class DuplicateProductCode(
        val productCode: String,
        override val message: String = "A product with the code '$productCode' already exists",
    ) : DomainError {
        override val code = "DUPLICATE_PRODUCT_CODE"
    }

    /** Ya existe un producto con ese identificador del ERP. */
    data class DuplicateExternalId(
        val externalId: String,
        override val message: String = "A product with the external identifier '$externalId' already exists",
    ) : DomainError {
        override val code = "DUPLICATE_EXTERNAL_ID"
    }

    /** Ya existe esa ubicación en la bodega. */
    data class DuplicateLocation(
        val hall: String,
        val locationCode: String,
        val position: String,
        override val message: String =
            "A location $hall-$locationCode-$position already exists in that warehouse",
    ) : DomainError {
        override val code = "DUPLICATE_LOCATION"
    }

    /** Ya existe una bodega con ese código. */
    data class DuplicateWarehouseCode(
        val warehouseCode: String,
        override val message: String = "A warehouse with the code '$warehouseCode' already exists",
    ) : DomainError {
        override val code = "DUPLICATE_WAREHOUSE_CODE"
    }

    /** El producto ya estaba desactivado: volver a desactivarlo borraría el motivo del primero. */
    data class ProductAlreadyInactive(
        val productCode: String,
        override val message: String = "El producto '$productCode' ya estaba desactivado",
    ) : DomainError {
        override val code = "PRODUCT_ALREADY_INACTIVE"
    }

    /** La ubicación ya estaba desactivada. */
    data class LocationAlreadyInactive(
        val locationCode: String,
        override val message: String = "Location '$locationCode' was already deactivated",
    ) : DomainError {
        override val code = "LOCATION_ALREADY_INACTIVE"
    }

    /**
     * No se puede desactivar la ubicación de recepción.
     *
     * Toda mercancía recibida pasa por ella (ADR-0009): desactivarla dejaría
     * recepciones sin sitio donde aterrizar.
     */
    data object ReceivingLocationProtected : DomainError {
        override val code = "RECEIVING_LOCATION_PROTECTED"
        override val message =
            "The receiving location cannot be deactivated: all received goods pass through it."
    }

    /** La ubicación todavía tiene existencias. */
    data class LocationHasStock(
        val quantity: String,
        override val message: String =
            "The location holds $quantity units. Move or adjust them before deactivating it.",
    ) : DomainError {
        override val code = "LOCATION_HAS_STOCK"
    }

    /** El producto todavía tiene existencias. */
    data class ProductHasStock(
        val stock: String,
        override val message: String =
            "The product has $stock units. Adjust or transfer the stock before deactivating it.",
    ) : DomainError {
        override val code = "PRODUCT_HAS_STOCK"
    }

    /** El producto no existe o está inactivo. */
    data class ProductNotFound(
        val reference: String,
        override val message: String = "No active product found for '$reference'",
    ) : DomainError {
        override val code = "PRODUCT_NOT_FOUND"
    }

    /** La ubicación no existe o está inactiva. */
    data class LocationNotFound(
        val reference: String,
        override val message: String = "No active location found for '$reference'",
    ) : DomainError {
        override val code = "LOCATION_NOT_FOUND"
    }

    /**
     * El archivo de importación tiene filas inválidas: **no se escribe ninguna**
     * (K1). El listado de filas viaja con el error, porque sin el número de fila
     * el usuario no puede corregir el archivo.
     */
    data class ImportInvalidRows(
        val rows: List<RejectedRow>,
        override val message: String =
            "The file has ${rows.size} invalid row(s). No product was written.",
    ) : DomainError {
        override val code = "IMPORT_INVALID_ROWS"

        /** Una fila rechazada, con el número **físico** del archivo (la cabecera es la 1). */
        @Serializable
        data class RejectedRow(val rowNumber: Int, val reason: String)
    }
}
