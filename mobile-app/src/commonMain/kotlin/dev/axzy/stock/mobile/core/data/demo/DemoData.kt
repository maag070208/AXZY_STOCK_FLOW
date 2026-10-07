package dev.axzy.stock.mobile.core.data.demo

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.mobile.core.data.AppRepositories
import dev.axzy.stock.mobile.core.data.CatalogRepository
import dev.axzy.stock.mobile.core.data.FulfillmentRepository
import dev.axzy.stock.mobile.core.data.HomeCategory
import dev.axzy.stock.mobile.core.data.HomeRepository
import dev.axzy.stock.mobile.core.data.InventoryRepository
import dev.axzy.stock.mobile.core.data.InvoiceLineView
import dev.axzy.stock.mobile.core.data.InvoiceView
import dev.axzy.stock.mobile.core.data.KardexEntryView
import dev.axzy.stock.mobile.core.data.LabelFieldView
import dev.axzy.stock.mobile.core.data.LabelTemplateView
import dev.axzy.stock.mobile.core.data.LabelingRepository
import dev.axzy.stock.mobile.core.data.LocationKind
import dev.axzy.stock.mobile.core.data.LocationView
import dev.axzy.stock.mobile.core.data.OrderView
import dev.axzy.stock.mobile.core.data.PrintJobView
import dev.axzy.stock.mobile.core.data.ProductView
import dev.axzy.stock.mobile.core.data.ReceivingRepository
import dev.axzy.stock.mobile.core.data.StockLevelView

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DATOS DE EJEMPLO — para que la app arranque y sea revisable sin backend
 *
 * QUÉ SON Y QUÉ NO SON
 *   · Son coherentes a propósito: los totales de producto son la SUMA de sus
 *     existencias por ubicación, `pendingLines` es `totalLines - locatedLines` y
 *     `difference` es `physical - expected`. Una maqueta con números que no cuadran
 *     esconde justo el error que este proyecto existe para corregir.
 *   · NO son datos reales del Excel de 1.398 productos. La migración real es la
 *     Fase 7 del plan; aquí solo hacen falta unos pocos para navegar las pantallas.
 *   · Viven en `core/data/demo` y no en cada feature: cuando llegue
 *     `stock-network`, se borra **esta carpeta entera** y las pantallas no cambian,
 *     porque dependen de las interfaces, no de esto.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object DemoData {

    // ── Productos ───────────────────────────────────────────────────────────
    // Códigos del alfabeto real del catálogo (0-9 y A-Z, sin Ñ, O ni W).
    private val productos = listOf(
        Triple("ABRAA1B", "ABRAZADERA DE ARRANQUE 1-5/8\"", "PZA"),
        Triple("TORMM10", "TORNILLO MILIMETRICO M10 X 40", "PZA"),
        Triple("CINTA20", "CINTA AISLANTE 20M NEGRA", "PZA"),
        Triple("FILTRO1A", "FILTRO DE ACEITE HIDRAULICO", "PZA"),
        Triple("GUANTE9", "GUANTE DE CARNAZA TALLA 9", "PZA"),
        Triple("ACEITE15", "ACEITE HIDRAULICO ISO 68 (CUBETA)", "CUB"),
    )

    // ── Ubicaciones ─────────────────────────────────────────────────────────
    // `RECEPCION` es la antigua pseudo-ubicación ADMIN, ahora REAL y con
    // `kind = RECEIVING`. Las demás siguen el formato del legacy (`04BCA` =
    // pasillo 04, localidad B, posición C, nivel A), porque es lo que está
    // IMPRESO en los estantes.
    private val recepcion = LocationView(
        id = 100,
        code = LocationCode.of("RECEPCION"),
        displayName = "RECEPCION",
        warehouseCode = "PRINCIPAL",
        hall = "00",
        position = "00",
        levels = 1,
        kind = LocationKind.RECEIVING,
    )

    private val estantes = listOf(
        LocationView(1, LocationCode.of("04BCA"), "04-B-C-A", "PRINCIPAL", "04", "C", 3, LocationKind.SHELF),
        LocationView(2, LocationCode.of("04BCB"), "04-B-C-B", "PRINCIPAL", "04", "C", 3, LocationKind.SHELF),
        LocationView(3, LocationCode.of("05ADA"), "05-A-D-A", "PRINCIPAL", "05", "D", 3, LocationKind.SHELF),
        LocationView(4, LocationCode.of("06BAA"), "06-B-A-A", "PRINCIPAL", "06", "A", 4, LocationKind.SHELF),
    )

    private val todasLasUbicaciones = listOf(recepcion) + estantes

    // ── Existencias ─────────────────────────────────────────────────────────
    // (productId, locationId, cantidad). Los totales de abajo son la SUMA exacta
    // de estas filas: si alguien añade una, el total se recalcula solo.
    private val existencias: List<Triple<Long, Long, Double>> = listOf(
        Triple(1L, 1L, 5193.0),
        Triple(2L, 2L, 1840.0),
        Triple(3L, 3L, 420.0),
        Triple(4L, 4L, 36.0),
        Triple(5L, 1L, 250.0),
        Triple(6L, 3L, 12.0),
        Triple(1L, 100L, 300.0),
        Triple(3L, 100L, 80.0),
    )

    private val productosBase: List<ProductView> = productos.mapIndexed { index, (code, name, unit) ->
        val id = (index + 1).toLong()
        ProductView(
            id = id,
            code = ProductCode.of(code),
            name = name,
            description = null,
            unitMeasure = unit,
            totalStock = existencias.filter { it.first == id }.sumOf { it.third },
            active = true,
        )
    }

    private val niveles: List<StockLevelView> = existencias.map { (productId, locationId, quantity) ->
        val producto = productosBase.first { it.id == productId }
        val ubicacion = todasLasUbicaciones.first { it.id == locationId }
        StockLevelView(
            productId = productId,
            productCode = producto.code,
            productName = producto.name,
            locationId = locationId,
            locationDisplayName = ubicacion.displayName,
            warehouseCode = ubicacion.warehouseCode,
            quantity = quantity,
        )
    }

    // ── Kardex ──────────────────────────────────────────────────────────────
    private val kardex = listOf(
        KardexEntryView(
            id = 501, movedAtIso = "2026-10-07T21:24:37Z", productCode = ProductCode.of("ABRAA1B"),
            moveType = "RECEIPT_IN", units = 300.0, quantityAfter = 5493.0,
            fromLocationDisplayName = null, toLocationDisplayName = "RECEPCION",
            userName = "Juan Pérez", note = "Factura F-2024-0158",
        ),
        KardexEntryView(
            id = 502, movedAtIso = "2026-10-07T21:30:02Z", productCode = ProductCode.of("ABRAA1B"),
            moveType = "LOCATE", units = 300.0, quantityAfter = 5193.0,
            fromLocationDisplayName = "RECEPCION", toLocationDisplayName = "04-B-C-A",
            userName = "Juan Pérez", note = null,
        ),
        KardexEntryView(
            id = 503, movedAtIso = "2026-10-07T22:05:11Z", productCode = ProductCode.of("TORMM10"),
            moveType = "FULFILLMENT_OUT", units = 60.0, quantityAfter = 1840.0,
            fromLocationDisplayName = "04-B-C-B", toLocationDisplayName = null,
            userName = "María López", note = "Orden ORD-2024-0042",
        ),
        KardexEntryView(
            id = 504, movedAtIso = "2026-10-08T09:12:44Z", productCode = ProductCode.of("CINTA20"),
            moveType = "DAMAGE", units = 5.0, quantityAfter = 420.0,
            fromLocationDisplayName = "05-A-D-A", toLocationDisplayName = null,
            userName = "María López", note = "Caja rota en el pasillo 3",
        ),
    )

    // ── Facturas ────────────────────────────────────────────────────────────
    // `pendingLines` se calcula, no se escribe: es el tipo de número que en el
    // legacy se declaraba cuatro veces y se desincronizaba.
    private fun invoice(
        id: Long,
        number: String,
        date: String,
        status: String,
        total: Int,
        located: Int,
    ) = InvoiceView(id, number, date, status, total, located, total - located)

    private val facturas = listOf(
        invoice(15, "F-2024-0158", "2026-10-07", "IN_PROGRESS", total = 12, located = 8),
        invoice(16, "F-2024-0159", "2026-10-08", "PENDING_TO_LOCATE", total = 4, located = 0),
        invoice(17, "F-2024-0160", "2026-10-08", "PENDING", total = 6, located = 0),
    )

    private val lineasFactura15 = listOf(
        InvoiceLineView(
            id = 88, productId = 1, productCode = ProductCode.of("ABRAA1B"),
            productName = "ABRAZADERA DE ARRANQUE 1-5/8\"", container = "CONT-01", unitMeasure = "PZA",
            expectedQuantity = 100.0, physicalQuantity = 80.0, locatedQuantity = 0.0, unitsPerBox = 50,
            status = "DISCREPANCY", capturedByName = "Juan Pérez", locationDisplayName = null,
        ),
        InvoiceLineView(
            id = 89, productId = 2, productCode = ProductCode.of("TORMM10"),
            productName = "TORNILLO MILIMETRICO M10 X 40", container = "CONT-01", unitMeasure = "PZA",
            expectedQuantity = 500.0, physicalQuantity = 500.0, locatedQuantity = 500.0, unitsPerBox = 100,
            status = "LOCATED", capturedByName = "Juan Pérez", locationDisplayName = "04-B-C-B",
        ),
        InvoiceLineView(
            id = 90, productId = 3, productCode = ProductCode.of("CINTA20"),
            productName = "CINTA AISLANTE 20M NEGRA", container = "CONT-02", unitMeasure = "PZA",
            expectedQuantity = 200.0, physicalQuantity = 200.0, locatedQuantity = 0.0, unitsPerBox = 20,
            status = "CAPTURED", capturedByName = "María López", locationDisplayName = null,
        ),
        InvoiceLineView(
            id = 91, productId = 5, productCode = ProductCode.of("GUANTE9"),
            productName = "GUANTE DE CARNAZA TALLA 9", container = null, unitMeasure = "PZA",
            expectedQuantity = 250.0, physicalQuantity = null, locatedQuantity = 0.0, unitsPerBox = 25,
            status = "PENDING", capturedByName = null, locationDisplayName = null,
        ),
    )

    private val lineasFactura16 = listOf(
        InvoiceLineView(
            id = 95, productId = 6, productCode = ProductCode.of("ACEITE15"),
            productName = "ACEITE HIDRAULICO ISO 68 (CUBETA)", container = null, unitMeasure = "CUB",
            expectedQuantity = 12.0, physicalQuantity = 12.0, locatedQuantity = 0.0, unitsPerBox = 1,
            status = "CAPTURED", capturedByName = "María López", locationDisplayName = null,
        ),
        InvoiceLineView(
            id = 96, productId = 4, productCode = ProductCode.of("FILTRO1A"),
            productName = "FILTRO DE ACEITE HIDRAULICO", container = null, unitMeasure = "PZA",
            expectedQuantity = 36.0, physicalQuantity = 36.0, locatedQuantity = 0.0, unitsPerBox = 6,
            status = "CAPTURED", capturedByName = "María López", locationDisplayName = null,
        ),
    )

    // ── Órdenes ─────────────────────────────────────────────────────────────
    private val ordenes = listOf(
        OrderView(30, "ORD-2024-0042", "2026-10-07", "IN_PROGRESS", null, 5, 2, false, null),
        OrderView(31, "ORD-2024-0043", "2026-10-08", "PENDING", null, 3, 0, false, null),
        OrderView(32, "ORD-2024-0041", "2026-10-06", "COMPLETED", "CLI-118", 4, 4, true, "SUCCESS"),
    )

    // ── Etiquetas ───────────────────────────────────────────────────────────
    private val plantillas = listOf(
        LabelTemplateView(
            id = 1, code = "receipt", name = "Recibo de mercancía", size = "4x6", isActive = true,
            fields = listOf(
                LabelFieldView("productCode", "Código", required = true),
                LabelFieldView("name", "Nombre", required = true),
                LabelFieldView("quantity", "Cantidad", required = false),
            ),
        ),
        LabelTemplateView(
            id = 2, code = "mesh", name = "Malla de ubicación", size = "4x6", isActive = true,
            fields = listOf(
                LabelFieldView("locationCode", "Ubicación", required = true),
                LabelFieldView("productCode", "Código", required = true),
            ),
        ),
    )

    private val trabajos = listOf(
        PrintJobView(501, "receipt", 2, "PRINTED", 1, null),
        PrintJobView(502, "mesh", 1, "FAILED", 3, "La impresora no responde"),
        PrintJobView(503, "receipt", 1, "PENDING", 0, null),
    )

    // ── Implementaciones ────────────────────────────────────────────────────

    private object Catalog : CatalogRepository {
        override suspend fun searchProducts(query: String): List<ProductView> {
            val q = query.trim().uppercase()
            if (q.isEmpty()) return productosBase
            return productosBase.filter { it.code.value.contains(q) || it.name.uppercase().contains(q) }
        }

        override suspend fun productByCode(code: ProductCode): ProductView? =
            productosBase.firstOrNull { it.code == code }

        override suspend fun productById(id: Long): ProductView? =
            productosBase.firstOrNull { it.id == id }

        override suspend fun locations(): List<LocationView> = todasLasUbicaciones

        override suspend fun locationByCode(code: LocationCode): LocationView? =
            todasLasUbicaciones.firstOrNull { it.code == code }

        override suspend fun locationContent(locationId: Long): List<StockLevelView> =
            niveles.filter { it.locationId == locationId }
    }

    private object Inventory : InventoryRepository {
        override suspend fun stockForProduct(productId: Long): List<StockLevelView> =
            niveles.filter { it.productId == productId }

        override suspend fun kardexForProduct(code: ProductCode): List<KardexEntryView> =
            kardex.filter { it.productCode == code }
    }

    /** Estado mutable SOLO de las líneas capturadas: la demo tiene que reaccionar. */
    private val lineas = mutableMapOf(15L to lineasFactura15, 16L to lineasFactura16)

    private object Receiving : ReceivingRepository {
        override suspend fun invoices(): List<InvoiceView> = facturas

        override suspend fun invoice(id: Long): InvoiceView? = facturas.firstOrNull { it.id == id }

        override suspend fun invoiceLines(invoiceId: Long): List<InvoiceLineView> =
            lineas[invoiceId].orEmpty()

        override suspend fun captureLine(
            invoiceId: Long,
            lineId: Long,
            physicalQuantity: Double,
            idempotencyKey: String,
        ): Result<InvoiceLineView> {
            val actuales = lineas[invoiceId] ?: return Result.failure(IllegalStateException("Factura $invoiceId inexistente"))
            val linea = actuales.firstOrNull { it.id == lineId }
                ?: return Result.failure(IllegalStateException("Línea $lineId inexistente"))

            // El estado lo decide la REGLA: coincide → CAPTURED, no coincide →
            // DISCREPANCY. En el servidor esto es una columna derivada; aquí se
            // replica para que la demo se comporte como el sistema real.
            val estado = if (physicalQuantity == linea.expectedQuantity) "CAPTURED" else "DISCREPANCY"
            val actualizada = linea.copy(physicalQuantity = physicalQuantity, status = estado)
            lineas[invoiceId] = actuales.map { if (it.id == lineId) actualizada else it }
            return Result.success(actualizada)
        }
    }

    private object Fulfillment : FulfillmentRepository {
        override suspend fun orders(): List<OrderView> = ordenes
        override suspend fun order(id: Long): OrderView? = ordenes.firstOrNull { it.id == id }
    }

    private object Labeling : LabelingRepository {
        override suspend fun templates(): List<LabelTemplateView> = plantillas

        override suspend fun printJobs(status: String?): List<PrintJobView> =
            if (status == null) trabajos else trabajos.filter { it.status == status }

        override suspend fun print(
            templateId: Long,
            copies: Int,
            data: List<Map<String, String>>,
        ): Result<List<Long>> {
            // Se devuelve PENDING y no PRINTED a propósito: el trabajo no está
            // impreso hasta que la impresora confirma. El legacy lo marcaba al
            // enviarlo, así que si la Zebra estaba apagada la etiqueta se perdía.
            val ids = data.indices.map { 600L + it }
            return Result.success(ids)
        }
    }

    private object Home : HomeRepository {
        override suspend fun categories(): List<HomeCategory> = listOf(
            HomeCategory("invoices", "Recepción", "Facturas, captura y ubicación", facturas.sumOf { it.pendingLines }),
            HomeCategory("orders", "Surtido", "Órdenes, empaque y confirmación", ordenes.count { it.status != "COMPLETED" }),
            HomeCategory("locations", "Ubicaciones", "Contenido y búsqueda por estante", todasLasUbicaciones.size),
            HomeCategory("inventory", "Inventario", "Movimientos, kardex y ajustes", null),
            HomeCategory("labels", "Etiquetas", "Impresión libre y malla de ubicación", trabajos.count { it.status == "FAILED" }),
        )
    }

    /** Puertos ya cableados, listos para el contenedor de la app. */
    val repositories: AppRepositories = AppRepositories(
        catalog = Catalog,
        inventory = Inventory,
        receiving = Receiving,
        fulfillment = Fulfillment,
        labeling = Labeling,
        home = Home,
    )
}
