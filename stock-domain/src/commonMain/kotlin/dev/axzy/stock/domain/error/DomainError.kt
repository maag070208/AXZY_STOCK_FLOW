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
        override val message: String = "No se puede '$action' desde el estado '$from'",
    ) : DomainError {
        override val code = "INVALID_TRANSITION"
    }

    // ── Cantidades ──────────────────────────────────────────────────────────

    /** Se intentó ubicar o surtir más de lo que la línea admite. */
    data class ExceedsLineQuantity(
        val requested: Long,
        val available: Long,
        override val message: String =
            "La cantidad solicitada excede lo disponible en la línea",
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
            "La cantidad no es divisible entre las unidades por caja: " +
                "quedaría un remanente de $remainder",
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
            "$boxes caja(s) de $unitsPerBox no caben en $quantity unidades",
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
            "La línea tiene una diferencia de $difference unidades sin aprobar. " +
                "Un supervisor debe autorizarla antes de ubicar la mercancía.",
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
            "No puedes aprobar una discrepancia que tú mismo capturaste. " +
                "Requiere la autorización de otra persona.",
    ) : DomainError {
        override val code = "SEGREGATION_OF_DUTIES"
    }

    /** Se intentó aprobar algo que no está en estado de aprobación. */
    data class NotPendingApproval(
        val currentStatus: String,
        override val message: String =
            "La línea no está pendiente de aprobación (estado actual: $currentStatus)",
    ) : DomainError {
        override val code = "NOT_PENDING_APPROVAL"
    }

    /** Se exige un motivo y no se proporcionó (invariante I6). */
    data class ReasonRequired(
        val context: String,
        override val message: String = "Se requiere un motivo para $context",
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
            "Stock insuficiente en $location: hay $available y se piden $requested",
    ) : DomainError {
        override val code = "INSUFFICIENT_STOCK"
    }

    /**
     * El origen y el destino de un movimiento son iguales.
     * En el legacy esto no se validaba y generaba movimientos sin efecto.
     */
    data class SameSourceAndDestination(
        val location: String,
        override val message: String = "El origen y el destino no pueden ser la misma ubicación",
    ) : DomainError {
        override val code = "SAME_SOURCE_AND_DESTINATION"
    }

    // ── Documentos ──────────────────────────────────────────────────────────

    /** El documento tiene líneas pendientes y no se puede cerrar. */
    data class HasPendingLines(
        val pendingCount: Int,
        override val message: String =
            "El documento tiene $pendingCount línea(s) sin resolver y no se puede cerrar",
    ) : DomainError {
        override val code = "HAS_PENDING_LINES"
    }

    /**
     * Se intentó borrar un documento con efecto en inventario.
     * No se borra: se **anula** con un movimiento compensatorio (R-57 y R-68).
     */
    data class CannotDeleteWithInventoryEffect(
        override val message: String =
            "Este documento afecta al inventario y no se puede eliminar: " +
                "debe anularse con un movimiento compensatorio.",
    ) : DomainError {
        override val code = "CANNOT_DELETE_WITH_INVENTORY_EFFECT"
    }

    /** Conflicto de unicidad (número de factura u orden ya existe). */
    data class Duplicate(
        val entity: String,
        val value: String,
        override val message: String = "Ya existe $entity con el valor '$value'",
    ) : DomainError {
        override val code = "DUPLICATE"
    }

    /** El recurso no existe. */
    data class NotFound(
        val entity: String,
        val id: String,
        override val message: String = "No se encontró $entity con id '$id'",
    ) : DomainError {
        override val code = "NOT_FOUND"
    }

    // ── Acceso (detalle en docs/historico/PLAN_ROLES_PERMISOS_POLITICAS.md) ────────────────

    /** RBAC no alcanza: el usuario no tiene el permiso. */
    data class PermissionDenied(
        val permission: String,
        override val message: String = "No tienes permiso para esta acción",
    ) : DomainError {
        override val code = "PERMISSION_DENIED"
    }

    /** Tiene el permiso, pero no para **ese** recurso concreto. */
    data class ScopeInsufficient(
        val permission: String,
        val scope: String,
        override val message: String =
            "No tienes permiso sobre este recurso (alcance: $scope)",
    ) : DomainError {
        override val code = "SCOPE_INSUFFICIENT"
    }

    /** Una política ABAC denegó la acción. */
    data class PolicyDenied(
        val policyName: String,
        override val message: String = "Denegado por la política '$policyName'",
    ) : DomainError {
        override val code = "POLICY_DENIED"
    }

    /**
     * Hay políticas aplicables y ninguna `ALLOW` coincidió.
     * Es lo que hace que ABAC suba el listón en lugar de bajarlo.
     */
    data class PolicyRequired(
        override val message: String = "Esta acción requiere autorización específica",
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
            "El traslado debe ir a una bodega distinta de la de origen.",
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
        override val message: String = "El traslado no tiene ninguna línea.",
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
                "descontaría el stock dos veces.",
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
            "La orden $orderNumber no está confirmada: no hay nada que exportar.",
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
            "Se intentaron surtir $requested unidades y solo quedan $pending pendientes " +
                "en la línea.",
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
            "Faltan datos para la etiqueta: ${fields.joinToString(", ")}. " +
                "La plantilla los necesita y no se imprimen etiquetas incompletas.",
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
            "El trabajo de impresión $jobId ya está marcado como impreso; " +
                "un reintento no debe imprimir dos veces.",
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
        override val message: String = "Código de producto inválido ('$raw'): $detail",
    ) : DomainError {
        override val code = "INVALID_PRODUCT_CODE"
    }

    /** La unidad no está en el catálogo cerrado (`units_of_measure`). */
    data class UnknownUnitMeasure(
        val unit: String,
        override val message: String =
            "Unidad de medida desconocida '$unit'. Debe existir en el catálogo de unidades.",
    ) : DomainError {
        override val code = "UNKNOWN_UNIT_MEASURE"
    }

    /** El número de niveles de una ubicación no es positivo. */
    data class InvalidLevels(
        val levels: Int,
        override val message: String = "El número de niveles debe ser al menos 1, se recibió $levels",
    ) : DomainError {
        override val code = "INVALID_LEVELS"
    }

    /** El tipo de ubicación no es `SHELF` ni `RECEIVING`. */
    data class InvalidLocationKind(
        val raw: String,
        override val message: String = "Tipo de ubicación inválido '$raw': debe ser SHELF o RECEIVING",
    ) : DomainError {
        override val code = "INVALID_LOCATION_KIND"
    }

    /** Una de las tres partes de la ubicación no cumple el formato canónico. */
    data class InvalidLocationPart(
        val part: String,
        val raw: String,
        val detail: String,
        override val message: String = "Parte de ubicación inválida '$part' ('$raw'): $detail",
    ) : DomainError {
        override val code = "INVALID_LOCATION_PART"
    }

    /** La bodega referenciada no existe. */
    data class UnknownWarehouse(
        val id: Long,
        override val message: String = "No existe la bodega $id",
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
            "El identificador externo '$raw' no es numérico; perdería la referencia con el ERP.",
    ) : DomainError {
        override val code = "IMPORT_INVALID_EXTERNAL_ID"
    }

    /** Se intentó cambiar el código de un producto: está impreso en las etiquetas. */
    data class ProductCodeImmutable(
        val productCode: String,
        override val message: String =
            "El código de producto '$productCode' no se puede cambiar: está impreso en las etiquetas y en los estantes.",
    ) : DomainError {
        override val code = "PRODUCT_CODE_IMMUTABLE"
    }

    /** Se intentó mover una ubicación de bodega: su stock cambiaría de bodega sin movimiento. */
    data class WarehouseImmutable(
        val locationId: Long,
        override val message: String =
            "La ubicación $locationId no se puede mover de bodega: su stock cambiaría de bodega sin un movimiento.",
    ) : DomainError {
        override val code = "WAREHOUSE_IMMUTABLE"
    }

    /** Ya existe un producto con ese código. */
    data class DuplicateProductCode(
        val productCode: String,
        override val message: String = "Ya existe un producto con el código '$productCode'",
    ) : DomainError {
        override val code = "DUPLICATE_PRODUCT_CODE"
    }

    /** Ya existe un producto con ese identificador del ERP. */
    data class DuplicateExternalId(
        val externalId: String,
        override val message: String = "Ya existe un producto con el identificador externo '$externalId'",
    ) : DomainError {
        override val code = "DUPLICATE_EXTERNAL_ID"
    }

    /** Ya existe esa ubicación en la bodega. */
    data class DuplicateLocation(
        val hall: String,
        val locationCode: String,
        val position: String,
        override val message: String =
            "Ya existe la ubicación $hall-$locationCode-$position en esa bodega",
    ) : DomainError {
        override val code = "DUPLICATE_LOCATION"
    }

    /** Ya existe una bodega con ese código. */
    data class DuplicateWarehouseCode(
        val warehouseCode: String,
        override val message: String = "Ya existe una bodega con el código '$warehouseCode'",
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
        override val message: String = "La ubicación '$locationCode' ya estaba desactivada",
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
            "La ubicación de recepción no se puede desactivar: toda mercancía recibida pasa por ella."
    }

    /** La ubicación todavía tiene existencias. */
    data class LocationHasStock(
        val quantity: String,
        override val message: String =
            "La ubicación tiene $quantity unidades. Muévelas o ajústelas antes de desactivarla.",
    ) : DomainError {
        override val code = "LOCATION_HAS_STOCK"
    }

    /** El producto todavía tiene existencias. */
    data class ProductHasStock(
        val stock: String,
        override val message: String =
            "El producto tiene $stock unidades. Ajusta o traslada el stock antes de desactivarlo.",
    ) : DomainError {
        override val code = "PRODUCT_HAS_STOCK"
    }

    /** El producto no existe o está inactivo. */
    data class ProductNotFound(
        val reference: String,
        override val message: String = "No se encontró un producto activo con '$reference'",
    ) : DomainError {
        override val code = "PRODUCT_NOT_FOUND"
    }

    /** La ubicación no existe o está inactiva. */
    data class LocationNotFound(
        val reference: String,
        override val message: String = "No se encontró una ubicación activa con '$reference'",
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
            "El archivo tiene ${rows.size} fila(s) inválida(s). No se escribió ningún producto.",
    ) : DomainError {
        override val code = "IMPORT_INVALID_ROWS"

        /** Una fila rechazada, con el número **físico** del archivo (la cabecera es la 1). */
        @Serializable
        data class RejectedRow(val rowNumber: Int, val reason: String)
    }
}
