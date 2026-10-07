package dev.axzy.stock.network.dto

import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.auth.AuthSessionDto
import dev.axzy.stock.network.dto.common.ErrorDto
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.common.Page
import dev.axzy.stock.network.dto.health.HealthDto
import dev.axzy.stock.network.dto.health.HealthStatus
import dev.axzy.stock.network.dto.inventory.AdjustmentDirection
import dev.axzy.stock.network.dto.inventory.AdjustmentReason
import dev.axzy.stock.network.dto.inventory.AdjustmentRequest
import dev.axzy.stock.network.dto.inventory.KardexEntryDto
import dev.axzy.stock.network.dto.inventory.KardexMoveType
import dev.axzy.stock.network.dto.inventory.StockLevelDto
import dev.axzy.stock.network.dto.invoice.InvoiceDetailDto
import dev.axzy.stock.network.dto.invoice.InvoiceDto
import dev.axzy.stock.network.dto.invoice.InvoiceLineStatus
import dev.axzy.stock.network.dto.invoice.InvoiceStatus
import dev.axzy.stock.network.dto.label.LabelJobDto
import dev.axzy.stock.network.dto.label.LabelJobStatus
import dev.axzy.stock.network.dto.label.LabelTemplateDto
import dev.axzy.stock.network.dto.label.PrintLabelsResponse
import dev.axzy.stock.network.dto.location.LocationDto
import dev.axzy.stock.network.dto.location.LocationKind
import dev.axzy.stock.network.dto.location.WarehousesDto
import dev.axzy.stock.network.dto.order.OrderDto
import dev.axzy.stock.network.dto.order.OrderExportResultDto
import dev.axzy.stock.network.dto.order.OrderStatus
import dev.axzy.stock.network.dto.product.ImportReportDto
import dev.axzy.stock.network.dto.product.ProductDto
import dev.axzy.stock.network.dto.report.InventoryReportDto
import dev.axzy.stock.network.dto.report.KardexVsProjectionDto
import dev.axzy.stock.network.dto.transfer.InTransitTransferDto
import dev.axzy.stock.network.dto.transfer.TransferDto
import dev.axzy.stock.network.dto.transfer.TransferStatus
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LOS DTOs CONTRA EL CONTRATO
 *
 * Cada JSON de este archivo está copiado **literalmente** de `CONTRATO_API.md`.
 * No es un adorno: es la única forma de que el contrato sea la fuente de verdad
 * de verdad. Un DTO que se desvía del documento y solo se prueba contra un JSON
 * propio pasa los tests y falla en la integración, que es el peor sitio para
 * descubrirlo.
 *
 * Si el contrato cambia, este archivo se actualiza; si alguien cambia un DTO sin
 * tocar el contrato, aquí se ve.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ContratoDtosTest {

    private inline fun <reified T> parse(json: String): T = stockJson.decodeFromString(json)

    // ── Auth (§2) ───────────────────────────────────────────────────────────

    @Test
    fun `el login se decodifica con permisos y alcances`() {
        val session: AuthSessionDto = parse(
            """
            {
              "accessToken": "eyJ...", "refreshToken": "8Kd3...", "expiresIn": 900,
              "user": {
                "id": 7, "username": "jperez", "name": "Juan Pérez",
                "roles": ["JEFE_BODEGA"],
                "permissions": ["invoice.capture", "invoice.approve", "inventory.move"],
                "scopes": { "invoice.approve": "AREA", "inventory.move": "AREA" },
                "warehouses": [1],
                "mustChangePassword": false
              }
            }
            """.trimIndent()
        )

        assertEquals("eyJ...", session.accessToken)
        assertEquals(900L, session.expiresIn)
        assertEquals(7L, session.user.id)
        assertEquals(listOf("JEFE_BODEGA"), session.user.roles)
        assertEquals("AREA", session.user.scopes["invoice.approve"])
        assertEquals(listOf(1L), session.user.warehouses)
        assertEquals(false, session.user.mustChangePassword)
    }

    // ── Catálogo (§3) ───────────────────────────────────────────────────────

    @Test
    fun `el listado de productos se decodifica con su sobre de paginacion`() {
        val page: Page<ProductDto> = parse(
            """
            {
              "items": [
                {
                  "id": 1, "productCode": "ABRAA1B",
                  "name": "ABRAZADERA DE ARRANQUE 1-5/8\"",
                  "description": null,
                  "unitMeasure": "PZA",
                  "externalId": 1,
                  "active": true,
                  "stock": 5193.000
                }
              ],
              "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
            }
            """.trimIndent()
        )

        assertEquals(1, page.items.size)
        assertEquals("ABRAA1B", page.items.first().productCode)
        assertNull(page.items.first().description)
        // Decimal con 3 decimales, nunca cadena: es la regla §1.7 del contrato.
        assertEquals(5193.0, page.items.first().stock)
        // La página empieza en 0, como Spring.
        assertEquals(0, page.page)
        assertEquals(1398, page.totalItems)
        assertEquals(28, page.totalPages)
        assertTrue(page.hasNext)
    }

    @Test
    fun `el informe de importacion conserva el numero de fila de cada rechazo`() {
        val report: ImportReportDto = parse(
            """
            {
              "dryRun": true, "totalRows": 1398, "accepted": 1398, "rejected": 0,
              "totalStock": 10914130.000, "zeroStockCount": 334,
              "unitMeasures": ["PZA"],
              "rejections": [
                { "rowNumber": 42, "productCode": "mal", "reason": "Código de producto inválido" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(42, report.rejections.first().rowNumber)
        assertEquals("mal", report.rejections.first().productCode)
        assertEquals(10914130.0, report.totalStock)
    }

    // ── Almacén físico (§3) ─────────────────────────────────────────────────

    @Test
    fun `la ubicacion de recepcion es real, con id y kind propios`() {
        val location: LocationDto = parse(
            """
            {
              "id": 2, "warehouseId": 1, "warehouseCode": "PRINCIPAL",
              "hall": "04", "code": "B", "position": "C", "levels": 3,
              "kind": "SHELF", "active": true, "displayName": "04-B-C"
            }
            """.trimIndent()
        )

        assertEquals(2L, location.id)
        assertEquals(LocationKind.SHELF, location.kind)
        assertEquals("04-B-C", location.displayName)
    }

    @Test
    fun `las bodegas llegan sin sobre de paginacion`() {
        val warehouses: WarehousesDto = parse(
            """{ "items": [{ "id": 1, "code": "PRINCIPAL", "name": "Bodega principal", "isDefault": true, "active": true }] }"""
        )

        assertEquals(1, warehouses.items.size)
        assertTrue(warehouses.items.first().isDefault)
    }

    // ── Inventario (§4) ─────────────────────────────────────────────────────

    @Test
    fun `las existencias se decodifican con su ubicacion y bodega`() {
        val page: Page<StockLevelDto> = parse(
            """
            {
              "items": [
                { "productId": 1, "productCode": "ABRAA1B", "locationId": 2,
                  "locationDisplayName": "04-B-C", "warehouseId": 1, "quantity": 100.000 }
              ],
              "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
            }
            """.trimIndent()
        )

        assertEquals(100.0, page.items.first().quantity)
        assertEquals("04-B-C", page.items.first().locationDisplayName)
    }

    @Test
    fun `la entrada del kardex conserva los nulos del contrato`() {
        val page: Page<KardexEntryDto> = parse(
            """
            {
              "items": [
                {
                  "id": 501, "movedAt": "2026-10-07T21:24:37.688Z",
                  "productCode": "ABRAA1B", "moveType": "RECEIPT_IN",
                  "units": 100.000, "quantityAfter": 1100.000,
                  "fromLocationId": null, "fromLocationDisplayName": null,
                  "toLocationId": 2, "toLocationDisplayName": "RECEPCION",
                  "container": "CONT-01", "orderNumber": null,
                  "adjustmentReason": null, "userId": 7, "userName": "Juan Pérez",
                  "note": null
                }
              ],
              "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
            }
            """.trimIndent()
        )

        val entry = page.items.first()
        assertEquals(KardexMoveType.RECEIPT_IN, entry.moveType)
        assertEquals(1100.0, entry.quantityAfter)
        assertNull(entry.fromLocationId)
        assertEquals("RECEPCION", entry.toLocationDisplayName)
        // El listado del kardex NO trae `status`: solo lo trae la respuesta de un ajuste.
        assertNull(entry.status)
    }

    @Test
    fun `el ajuste se codifica con direccion, motivo, origen y clave de idempotencia`() {
        val request = AdjustmentRequest(
            productId = 1,
            locationId = 2,
            quantity = 5.0,
            direction = AdjustmentDirection.OUT,
            reason = AdjustmentReason.DAMAGE,
            origin = dev.axzy.stock.network.dto.inventory.AdjustmentOrigin.DAMAGE_REPORT,
            note = "Caja rota en el pasillo 3",
            idempotencyKey = "clave-1",
        )

        val json = stockJson.encodeToString(AdjustmentRequest.serializer(), request)
        assertTrue(json.contains("\"direction\":\"OUT\""), json)
        assertTrue(json.contains("\"reason\":\"DAMAGE\""), json)
        assertTrue(json.contains("\"origin\":\"DAMAGE_REPORT\""), json)
        assertTrue(json.contains("\"idempotencyKey\":\"clave-1\""), json)
    }

    // ── Recepción (§5) ──────────────────────────────────────────────────────

    @Test
    fun `la factura del listado trae sus contadores derivados`() {
        val page: Page<InvoiceDto> = parse(
            """
            {
              "items": [
                {
                  "id": 15, "invoiceNumber": "F-2024-0158", "receiptDate": "2026-10-07",
                  "invoiceType": "NATIONAL", "status": "IN_PROGRESS",
                  "customerCode": null, "concept": null,
                  "totalLines": 12, "locatedLines": 8, "pendingLines": 4,
                  "createdAt": "2026-10-07T21:24:37.688Z"
                }
              ],
              "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
            }
            """.trimIndent()
        )

        val invoice = page.items.first()
        assertEquals(InvoiceStatus.IN_PROGRESS, invoice.status)
        assertEquals(4, invoice.pendingLines)
        assertEquals("2026-10-07", invoice.receiptDate)
    }

    @Test
    fun `la linea de factura trae la diferencia que el legacy dejaba en cero`() {
        val detail: InvoiceDetailDto = parse(
            """
            {
              "id": 15, "invoiceNumber": "F-2024-0158", "status": "IN_PROGRESS",
              "lines": [
                {
                  "id": 88, "productId": 1, "productCode": "ABRAA1B",
                  "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
                  "container": "CONT-01", "unitMeasure": "PZA",
                  "quantity": 100.000, "boxQuantity": 2.000, "unitsPerBox": 50.000,
                  "physicalQuantity": 80.000, "physicalBoxQuantity": 1.600,
                  "locatedQuantity": 0.000,
                  "difference": -20.000,
                  "status": "DISCREPANCY",
                  "capturedBy": 7, "capturedByName": "Juan Pérez",
                  "capturedAt": "2026-10-07T21:00:00Z",
                  "approvedBy": null, "approvedByName": null, "approvedAt": null,
                  "adjustmentReason": null,
                  "locationId": null, "locationDisplayName": null
                }
              ]
            }
            """.trimIndent()
        )

        val line = detail.lines.single()
        assertEquals(InvoiceLineStatus.DISCREPANCY, line.status)
        assertEquals(-20.0, line.difference)
        assertEquals(80.0, line.physicalQuantity)
        assertNull(line.approvedBy)
    }

    // ── Surtido (§6) ────────────────────────────────────────────────────────

    @Test
    fun `la orden y su exportacion se decodifican`() {
        val page: Page<OrderDto> = parse(
            """
            {
              "items": [
                {
                  "id": 30, "orderNumber": "ORD-2024-0042", "packagingDate": "2026-10-07",
                  "status": "IN_PROGRESS", "customerCode": null, "concept": null,
                  "converted": false, "convertedAt": null,
                  "exportStatus": null, "exportAttempts": 0, "exportLastError": null,
                  "totalLines": 5, "packagedLines": 2
                }
              ],
              "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
            }
            """.trimIndent()
        )

        assertEquals(OrderStatus.IN_PROGRESS, page.items.first().status)
        assertEquals(false, page.items.first().converted)

        val export: OrderExportResultDto = parse(
            """{ "attempts": 3, "status": "FAILED", "lastError": "El ERP no responde", "nextRetryAt": "2026-10-07T22:00:00Z" }"""
        )
        assertEquals(3, export.attempts)
        assertEquals("FAILED", export.status)
    }

    // ── Traslados (§7) ──────────────────────────────────────────────────────

    @Test
    fun `el traslado en transito trae responsable y dias transcurridos`() {
        val transfer: TransferDto = parse(
            """
            {
              "id": 9, "code": "TR-2026-0001",
              "fromWarehouseId": 1, "fromWarehouseCode": "PRINCIPAL",
              "toWarehouseId": 2, "toWarehouseCode": "SUCURSAL",
              "status": "IN_TRANSIT",
              "lineCount": 3, "totalUnits": 150.000,
              "dispatchedBy": 7, "dispatchedAt": "2026-10-07T10:00:00Z", "receivedBy": null, "receivedAt": null
            }
            """.trimIndent()
        )

        assertEquals(TransferStatus.IN_TRANSIT, transfer.status)
        assertEquals(150.0, transfer.totalUnits)

        val inTransit: InTransitTransferDto = parse(
            """
            {
              "id": 9, "code": "TR-2026-0001",
              "fromWarehouseId": 1, "toWarehouseId": 2, "status": "IN_TRANSIT",
              "lineCount": 3, "totalUnits": 150.000, "dispatchedBy": 7,
              "dispatchedAt": "2026-10-07T10:00:00Z",
              "responsibleName": "Juan Pérez", "daysInTransit": 4
            }
            """.trimIndent()
        )

        assertEquals(4L, inTransit.daysInTransit)
        assertEquals("Juan Pérez", inTransit.responsibleName)
    }

    // ── Etiquetas (§8) ──────────────────────────────────────────────────────

    @Test
    fun `la plantilla de etiqueta se decodifica con su esquema de campos`() {
        val template: LabelTemplateDto = parse(
            """
            {
              "id": 1, "code": "receipt", "name": "Recibo de mercancía",
              "description": null, "size": "4x6", "isActive": true,
              "fieldSchema": {
                "productCode": { "type": "string", "label": "Código", "required": true },
                "name": { "type": "string", "label": "Nombre", "required": true },
                "quantity": { "type": "number", "label": "Cantidad", "required": false }
              }
            }
            """.trimIndent()
        )

        assertEquals(3, template.fieldSchema.size)
        assertTrue(template.fieldSchema.getValue("productCode").required)
        assertEquals("Cantidad", template.fieldSchema.getValue("quantity").label)
    }

    @Test
    fun `el trabajo de impresion y su encolado se decodifican`() {
        val print: PrintLabelsResponse = parse("""{ "jobIds": [501, 502], "status": "PENDING" }""")
        assertEquals(listOf(501L, 502L), print.jobIds)
        assertEquals(LabelJobStatus.PENDING, print.status)

        val job: LabelJobDto = parse(
            """
            {
              "id": 501, "templateCode": "receipt", "copies": 2,
              "status": "PRINTED", "attempts": 1, "lastError": null,
              "printedAt": "2026-10-07T21:30:00Z", "createdAt": "2026-10-07T21:29:50Z"
            }
            """.trimIndent()
        )
        // Una etiqueta no se marca impresa hasta que la impresora confirma.
        assertEquals(LabelJobStatus.PRINTED, job.status)
    }

    // ── Reportes (§9) ───────────────────────────────────────────────────────

    @Test
    fun `el reporte de existencias trae totales ademas del sobre`() {
        val report: InventoryReportDto = parse(
            """
            {
              "items": [ { "productCode": "ABRAA1B", "name": "...", "locationDisplayName": "04-B-C",
                           "quantity": 100.000, "warehouseCode": "PRINCIPAL" } ],
              "totals": { "distinctProducts": 1398, "totalUnits": 10914130.000 },
              "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
            }
            """.trimIndent()
        )

        assertEquals(1398, report.totals?.distinctProducts)
        assertEquals(10914130.0, report.totals?.totalUnits)
        assertEquals(28, report.toPage().totalPages)
    }

    @Test
    fun `el reporte de reconciliacion expone las divergencias`() {
        val report: KardexVsProjectionDto = parse(
            """
            {
              "checkedAt": "2026-10-07T22:00:00Z", "productsChecked": 1398, "divergences": 0,
              "items": [ { "productCode": "ABRAA1B", "ledgerNet": 5193.000,
                           "projected": 5193.000, "difference": 0.000 } ]
            }
            """.trimIndent()
        )

        assertEquals(0, report.divergences)
        assertEquals(0.0, report.items.single().difference)
    }

    // ── Salud (§10) ─────────────────────────────────────────────────────────

    @Test
    fun `el estado del sistema se decodifica con el diagnostico de la base`() {
        val health: HealthDto = parse(
            """
            {
              "status": "UP", "schemaVersion": "4", "version": "1.0.0",
              "database": { "status": "UP", "latencyMs": 3, "error": null }
            }
            """.trimIndent()
        )

        assertEquals(HealthStatus.UP, health.status)
        assertEquals("4", health.schemaVersion)
        assertEquals(3L, health.database?.latencyMs)
    }

    // ── Error (§1.3) ────────────────────────────────────────────────────────

    @Test
    fun `el contrato de error se decodifica con sus detalles`() {
        val error: ErrorDto = parse(
            """{ "code": "POLICY_DENIED", "message": "No puedes aprobar tu propio conteo", "details": { "campo": "motivo" } }"""
        )

        assertEquals("POLICY_DENIED", error.code)
        assertEquals("motivo", error.detailString("campo"))
        assertNull(error.detailString("no-existe"))
    }

    @Test
    fun `los detalles heterogeneos se leen con su tipo`() {
        val error: ErrorDto = parse(
            """{ "code": "ACCOUNT_LOCKED", "message": "bloqueada", "details": { "until": "2026-10-07T22:00:00Z", "intentos": 5, "activo": true } }"""
        )

        assertEquals("2026-10-07T22:00:00Z", error.detailString("until"))
        assertEquals(5L, error.detailLong("intentos"))
        assertEquals(true, error.detailBoolean("activo"))
    }

    // ── Un valor de enum que este cliente todavía no conoce ──────────────────

    @Test
    fun `un estado desconocido no tumba la lista entera`() {
        val page: Page<InvoiceDto> = parse(
            """
            {
              "items": [ { "id": 1, "invoiceNumber": "F-1", "receiptDate": "2026-10-07", "status": "UN_ESTADO_NUEVO" } ],
              "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
            }
            """.trimIndent()
        )

        // `coerceInputValues` deja el valor por defecto en lugar de lanzar: un
        // estado nuevo del servidor no puede impedir pintar la pantalla.
        assertEquals(InvoiceStatus.PENDING, page.items.single().status)
    }

    // ── Paginación (§1.4) ───────────────────────────────────────────────────

    @Test
    fun `la paginacion empieza en cero y el tamano por defecto es cincuenta`() {
        val query = ListQuery()
        assertEquals(0, query.page)
        assertEquals(50, query.size)
        assertEquals(200, Page.MAX_SIZE)
        assertEquals("productCode,asc", ListQuery.sortOf("productCode"))
    }

    @Test
    fun `un tamano de pagina mayor que el maximo se rechaza en el cliente, no en el servidor`() {
        val fallo = runCatching { ListQuery(size = 500) }.exceptionOrNull()
        assertNotNull(fallo)
        // El contrato dice que un `size` mayor se rechaza con 400 y NO se recorta
        // en silencio: recortar haría creer al cliente que tiene todos los datos.
        assertTrue(fallo.message!!.contains("200"), fallo.message)
    }

    @Test
    fun `una pagina negativa se rechaza`() {
        assertNotNull(runCatching { ListQuery(page = -1) }.exceptionOrNull())
    }
}
