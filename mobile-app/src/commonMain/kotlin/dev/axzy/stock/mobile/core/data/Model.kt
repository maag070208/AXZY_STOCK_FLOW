package dev.axzy.stock.mobile.core.data

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MODELO DE LECTURA DE LA APP MÓVIL
 *
 * ESTO NO ES EL DOMINIO, y la distinción importa:
 *
 *   · `stock-domain` tiene los INVARIANTES (unidades por caja > 0, transiciones
 *     válidas, ventana del escáner). Es puro y lo comparten backend y móvil.
 *   · Estos tipos son **lo que la pantalla necesita pintar**: una fila de lista,
 *     un detalle ya resuelto con nombres en lugar de ids.
 *
 * POR QUÉ NO SE REUTILIZAN LOS DTOs DE RED AQUÍ: porque no existen todavía en este
 * módulo —viven en `stock-network`, que construye otro agente— y porque un DTO de
 * transporte no debe llegar a la UI: en cuanto la pantalla lee un campo del JSON,
 * un cambio de contrato rompe la interfaz en tiempo de ejecución en lugar de en
 * tiempo de compilación.
 *
 * POR QUÉ NO SE USA `Quantity` DEL DOMINIO EN TODAS PARTES: en los puntos donde
 * hay una REGLA se usa `Quantity` (el escáner, la captura). En una tarjeta de
 * resumen se usa el valor ya formateado, porque un `Quantity` en la UI invita a
 * hacer aritmética de inventario en un composable, que es justo donde no debe
 * estar.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Producto tal y como aparece en un listado o en un detalle. */
data class ProductView(
    val id: Long,
    val code: ProductCode,
    val name: String,
    val description: String?,
    val unitMeasure: String,
    /** Existencia total (suma de todas las ubicaciones). El API la incluye ya. */
    val totalStock: Double,
    val active: Boolean = true,
)

/** Ubicación física. `displayName` es el código que está impreso en el estante. */
data class LocationView(
    val id: Long,
    val code: LocationCode,
    val displayName: String,
    val warehouseCode: String,
    val hall: String,
    val position: String,
    val levels: Int,
    /**
     * `SHELF` o `RECEIVING`.
     *
     * `RECEIVING` es una ubicación REAL con id real: es la antigua pseudo-ubicación
     * `ADMIN` del legacy, que se devolvía hardcodeada con el id 1 y podía colisionar
     * con la ubicación real 1.
     */
    val kind: LocationKind,
)

enum class LocationKind { SHELF, RECEIVING }

/** Existencia de un producto en una ubicación concreta. */
data class StockLevelView(
    val productId: Long,
    val productCode: ProductCode,
    val productName: String,
    val locationId: Long,
    val locationDisplayName: String,
    val warehouseCode: String,
    val quantity: Double,
)

/** Entrada del kardex. Es **append-only**: no hay `id` editable ni borrado. */
data class KardexEntryView(
    val id: Long,
    val movedAtIso: String,
    val productCode: ProductCode,
    val moveType: String,
    val units: Double,
    val quantityAfter: Double,
    val fromLocationDisplayName: String?,
    val toLocationDisplayName: String?,
    val userName: String,
    val note: String?,
)

/** Factura de recepción, en el listado. */
data class InvoiceView(
    val id: Long,
    val invoiceNumber: String,
    val receiptDateIso: String,
    val status: String,
    val totalLines: Int,
    val locatedLines: Int,
    /** `totalLines - locatedLines`, y el API lo manda ya calculado. */
    val pendingLines: Int,
)

/** Línea de factura. `difference` la calcula el SERVIDOR (columna generada). */
data class InvoiceLineView(
    val id: Long,
    val productId: Long,
    val productCode: ProductCode,
    val productName: String,
    val container: String?,
    val unitMeasure: String,
    val expectedQuantity: Double,
    val physicalQuantity: Double?,
    val locatedQuantity: Double,
    val unitsPerBox: Long,
    val status: String,
    val capturedByName: String?,
    val locationDisplayName: String?,
) {
    /**
     * Diferencia entre lo físico y lo esperado.
     *
     * Se calcula aquí **solo para pintar mientras no hay red**. Cuando llegue
     * `stock-network`, el valor que manda es el del servidor: el contrato lo declara
     * columna generada de la base precisamente para que no pueda desincronizarse.
     * Si esta propiedad se usara para decidir (aprobar, ubicar), habría dos
     * verdades, que es el defecto que el contrato corrige.
     */
    val difference: Double? get() = physicalQuantity?.minus(expectedQuantity)
}

/** Orden de surtido. */
data class OrderView(
    val id: Long,
    val orderNumber: String,
    val packagingDateIso: String,
    val status: String,
    val customerCode: String?,
    val totalLines: Int,
    val packagedLines: Int,
    val converted: Boolean,
    val exportStatus: String?,
)

/** Plantilla de etiqueta. `fieldSchema` es lo que la pantalla usa para el formulario. */
data class LabelTemplateView(
    val id: Long,
    val code: String,
    val name: String,
    val size: String,
    val fields: List<LabelFieldView>,
    val isActive: Boolean,
)

/** Campo de una plantilla. `key` es el nombre que espera el ZPL del servidor. */
data class LabelFieldView(
    val key: String,
    val label: String,
    val required: Boolean,
)

/** Trabajo de impresión. `PENDING` no es `PRINTED`: la impresora tiene que confirmar. */
data class PrintJobView(
    val id: Long,
    val templateCode: String,
    val copies: Int,
    val status: String,
    val attempts: Int,
    val lastError: String?,
)

/** Categoría del menú de inicio. El `count` es un contador vivo, no un adorno. */
data class HomeCategory(
    val id: String,
    val label: String,
    val description: String,
    val count: Int?,
)
