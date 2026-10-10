package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Recepción (M03) de punta a punta por HTTP real.
 *
 * Los nombres R1…R8 son los de docs/flujos/recepcion.md §6. Aquí están R2–R6 y R8;
 * **R1 (una línea en discrepancia no se puede ubicar) y R7 (ubicar no cambia el
 * total) dependen del flujo de ubicación**, que es la ruta de inventario de M02: R7
 * está cubierta allí (`InventoryFlowTest` A1) y R1 es la integración que falta
 * —la ruta de ubicar todavía no recibe la línea de factura—, documentada como
 * pendiente en el plan.
 */
class ReceivingFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun supervisor(): String {
        createUser("supervisor", USER_PASSWORD, jefa)
        return accessTokenOf("supervisor", USER_PASSWORD)
    }

    private fun product(token: String, code: String = "ABRAA1B") =
        post("/v1/products", mapOf("productCode" to code, "name" to "ABRAZADERA", "unitMeasure" to "PZA"), token)

    /** Una factura de una línea: 10 unidades, cajas de 5. */
    private fun invoice(
        token: String,
        number: String = "F-2026-001",
        quantity: Int = 10,
        unitsPerBox: Int = 5,
        lines: Int = 1,
    ): Long {
        val body = mapOf(
            "invoiceNumber" to number,
            "receiptDate" to "2026-10-07",
            "invoiceType" to "NATIONAL",
            "lines" to (1..lines).map {
                mapOf(
                    "productCode" to "ABRAA1B",
                    "container" to "CONT-0$it",
                    "unitMeasure" to "PZA",
                    "quantity" to quantity,
                    "boxQuantity" to quantity / unitsPerBox,
                    "unitsPerBox" to unitsPerBox,
                )
            },
        )
        val response = post("/v1/invoices", body, token)
        assertThat(response.status).describedAs("alta de factura: ${response.raw}").isEqualTo(201)
        return response.at<Int>("id").toLong()
    }

    private fun lineIds(token: String, invoiceId: Long): List<Long> =
        get("/v1/invoices/$invoiceId", token).at<List<Map<String, Any?>>>("lines").map { (it["id"] as Number).toLong() }

    private fun capture(token: String, invoiceId: Long, lineId: Long, quantity: Int, boxes: Int) =
        post(
            "/v1/invoices/$invoiceId/lines/$lineId/capture",
            mapOf("physicalQuantity" to quantity, "physicalBoxQuantity" to boxes),
            token,
        )

    private fun decide(token: String, invoiceId: Long, route: String, reason: String = "SUPPLIER_ERROR") =
        post("/v1/invoices/$invoiceId/$route", mapOf("adjustmentReason" to reason, "note" to "Revisado"), token)

    /** Deja saldo en la recepción: es de donde sale una ubicación. */
    /** Ubica mercancía de la recepción a un estante, diciendo de qué línea sale. */
    private fun locate(
        token: String,
        lineId: Long?,
        to: String = "04-B-C",
        quantity: Int,
        from: String = "RECEPCION",
    ) = post(
        "/v1/inventory/locate",
        buildMap<String, Any?> {
            put("productCode", "ABRAA1B")
            put("fromLocationCode", from)
            put("toLocationCode", to)
            put("quantity", quantity)
            if (lineId != null) put("invoiceLineId", lineId)
        },
        token,
    )

    private fun shelf(token: String) =
        post("/v1/locations", mapOf("warehouseId" to 1, "hall" to "04", "code" to "B", "position" to "C"), token)

    /** El aprobador de los aumentos: cualquier persona distinta de quien los pide. */
    private fun approver(token: String): Long {
        val existing = db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).list()
        return existing.firstOrNull() ?: supervisor().let {
            db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).single()
        }
    }

    private fun quantityAt(token: String, location: String): String? {
        val items = get("/v1/inventory/stock?onlyWithStock=false&locationCode=$location", token)
            .at<List<Map<String, Any?>>>("items")
        return items.firstOrNull()?.get("quantity") as String?
    }

    private fun line(token: String, invoiceId: Long, lineId: Long): Map<String, Any?> =
        get("/v1/invoices/$invoiceId", token).at<List<Map<String, Any?>>>("lines")
            .first { (it["id"] as Number).toLong() == lineId }

    // ── Alta y consulta ─────────────────────────────────────────────────────

    @Test
    fun `el alta responde 201 con la referencia y el detalle trae las lineas`() {
        val token = manager()
        product(token)

        val id = invoice(token)
        val detail = get("/v1/invoices/$id", token)

        assertThat(detail.at<String>("invoiceNumber")).isEqualTo("F-2026-001")
        assertThat(detail.at<String>("status")).isEqualTo("PENDING")
        assertThat(detail.at<Int>("totalLines")).isEqualTo(1)
        assertThat(detail.at<Int>("pendingApprovalLines")).isEqualTo(1)
        assertThat(detail.at<List<Map<String, Any?>>>("lines").single()["quantity"]).isEqualTo("10.000")
    }

    @Test
    fun `una factura sin lineas y un numero repetido se rechazan`() {
        val token = manager()
        product(token)

        val sinLineas = post(
            "/v1/invoices",
            mapOf("invoiceNumber" to "F-1", "receiptDate" to "2026-10-07", "lines" to emptyList<Any>()),
            token,
        )
        assertThat(sinLineas.status).isEqualTo(422)
        assertThat(sinLineas.code).isEqualTo("FIELD_REQUIRED")

        invoice(token, number = "F-REPE")
        val repetida = post(
            "/v1/invoices",
            mapOf(
                "invoiceNumber" to "F-REPE",
                "receiptDate" to "2026-10-07",
                "lines" to listOf(
                    mapOf("productCode" to "ABRAA1B", "quantity" to 5, "unitsPerBox" to 5),
                ),
            ),
            token,
        )
        assertThat(repetida.status).isEqualTo(409)
        assertThat(repetida.code).isEqualTo("DUPLICATE_INVOICE_NUMBER")
    }

    @Test
    fun `un producto que no existe y una fecha mal formada se rechazan`() {
        val token = manager()
        product(token)

        val producto = post(
            "/v1/invoices",
            mapOf(
                "invoiceNumber" to "F-2",
                "receiptDate" to "2026-10-07",
                "lines" to listOf(mapOf("productCode" to "NOEXISTE", "quantity" to 5, "unitsPerBox" to 5)),
            ),
            token,
        )
        val fecha = post(
            "/v1/invoices",
            mapOf(
                "invoiceNumber" to "F-3",
                "receiptDate" to "07/10/2026",
                "lines" to listOf(mapOf("productCode" to "ABRAA1B", "quantity" to 5, "unitsPerBox" to 5)),
            ),
            token,
        )

        assertThat(producto.code).isEqualTo("INVALID_PRODUCT_CODE")
        assertThat(fecha.status).isEqualTo(400)
        assertThat(fecha.code).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `una factura que no existe responde INVOICE_NOT_FOUND y una linea tampoco`() {
        val token = manager()
        product(token)
        val id = invoice(token)

        assertThat(get("/v1/invoices/999999", token).code).isEqualTo("INVOICE_NOT_FOUND")

        val linea = capture(token, id, 999999, quantity = 10, boxes = 2)
        assertThat(linea.status).isEqualTo(404)
        assertThat(linea.code).isEqualTo("LINE_NOT_FOUND")
    }

    // ── R2: toda diferencia pide aprobación, sin umbral ─────────────────────

    @Test
    fun `R2 - una diferencia de una unidad deja la linea en DISCREPANCY`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()

        // Se facturaron 10 y llegaron 9: una unidad de diferencia.
        val response = capture(token, id, lineId, quantity = 9, boxes = 1)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("status")).isEqualTo("DISCREPANCY")
        assertThat(response.at<String>("difference")).isEqualTo("-1.000")
        // El contador de la cabecera dice lo mismo que la línea: es lo que impide cerrar.
        assertThat(get("/v1/invoices/$id", token).at<Int>("pendingApprovalLines")).isEqualTo(1)
    }

    @Test
    fun `una captura exacta deja la linea en CAPTURED y no pide aprobacion`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()

        val response = capture(token, id, lineId, quantity = 10, boxes = 2)

        assertThat(response.at<String>("status")).isEqualTo("CAPTURED")
        assertThat(response.at<String>("difference")).isEqualTo("0.000")
        // Sin líneas bloqueantes la factura pasa a «pendiente de ubicar».
        assertThat(get("/v1/invoices/$id", token).at<String>("status")).isEqualTo("PENDING_TO_LOCATE")
        assertThat(get("/v1/invoices/$id", token).at<Int>("pendingApprovalLines")).isZero()
    }

    @Test
    fun `un conteo con cajas incoherentes se rechaza`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()

        // 9 piezas no pueden ser 2 cajas de 5: el dato se contradice a sí mismo.
        val response = capture(token, id, lineId, quantity = 9, boxes = 2)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INCONSISTENT_BOX_COUNT")
    }

    // ── R3 y R4: quién aprueba y a quién cubre ──────────────────────────────

    @Test
    fun `R3 - quien capturo no puede aprobar su propia discrepancia`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 9, boxes = 1)

        // El mismo usuario que capturó (es administrador, tiene el permiso).
        val response = decide(token, id, "approve")

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("SEGREGATION_OF_DUTIES")
        // Y la línea sigue como estaba: no se aprobó nada.
        assertThat(get("/v1/invoices/$id", token).at<List<Map<String, Any?>>>("lines").single()["status"])
            .isEqualTo("DISCREPANCY")
    }

    @Test
    fun `R4 - una aprobacion cubre todas las discrepancias de la factura`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val id = invoice(token, lines = 2)
        lineIds(token, id).forEach { capture(token, id, it, quantity = 9, boxes = 1) }

        val response = decide(jefe, id, "approve")
        val lines = response.at<List<Map<String, Any?>>>("lines")

        assertThat(response.status).isEqualTo(200)
        assertThat(lines.map { it["status"] }).containsOnly("APPROVED")
        // El mismo aprobador en las dos, y con su nombre: la bitácora se lee sin ids.
        val approvers = lines.map { it["approvedByName"] }.distinct()
        assertThat(approvers).hasSize(1)
        assertThat(approvers.single()).isEqualTo("Usuario supervisor")
        assertThat(lines.map { it["adjustmentReason"] }).containsOnly("SUPPLIER_ERROR")
    }

    @Test
    fun `rechazar una discrepancia devuelve la linea a captura`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 9, boxes = 1)

        val response = decide(jefe, id, "reject-discrepancy")
        val line = response.at<List<Map<String, Any?>>>("lines").single()

        assertThat(response.status).isEqualTo(200)
        assertThat(line["status"]).isEqualTo("PENDING")
        // El conteo se limpió: el motivo del rechazo es que hay que volver a contar.
        assertThat(line["physicalQuantity"]).isNull()
        assertThat(line["capturedByName"]).isNull()
    }

    @Test
    fun `una decision sin motivo responde REASON_REQUIRED`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 9, boxes = 1)

        val response = post("/v1/invoices/$id/approve", mapOf("note" to "sin motivo"), jefe)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("REASON_REQUIRED")
    }

    // ── R5 y R6: cierre y aprobación sin pendientes ─────────────────────────

    @Test
    fun `R5 - no se cierra una factura con lineas sin resolver`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 9, boxes = 1)

        val response = post("/v1/invoices/$id/close", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INVOICE_HAS_PENDING_LINES")
        assertThat(response.at<Map<String, String>>("details")["pendingLines"]).isEqualTo("1")
    }

    @Test
    fun `R5 - se cierra cuando nada bloquea y no se cierra dos veces`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        val cerrada = post("/v1/invoices/$id/close", emptyMap<String, Any>(), token)
        val otraVez = post("/v1/invoices/$id/close", emptyMap<String, Any>(), token)

        assertThat(cerrada.status).isEqualTo(200)
        assertThat(cerrada.at<String>("status")).isEqualTo("COMPLETED")
        assertThat(otraVez.status).isEqualTo(409)
        assertThat(otraVez.code).isEqualTo("INVOICE_ALREADY_CLOSED")
    }

    @Test
    fun `R6 - aprobar una factura sin discrepancias responde NO_PENDING_APPROVAL`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        val response = decide(jefe, id, "approve")

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("NO_PENDING_APPROVAL")
    }

    // ── La bandeja de pendientes ────────────────────────────────────────────

    @Test
    fun `la bandeja de pendientes trae solo lo que espera decision`() {
        val token = manager()
        product(token)
        val conDiscrepancia = invoice(token, number = "F-DISC")
        val sinDiscrepancia = invoice(token, number = "F-OK")
        capture(token, conDiscrepancia, lineIds(token, conDiscrepancia).single(), quantity = 9, boxes = 1)
        capture(token, sinDiscrepancia, lineIds(token, sinDiscrepancia).single(), quantity = 10, boxes = 2)

        val response = get("/v1/invoices/pending-approval", token)
        val items = response.at<List<Map<String, Any?>>>("items")

        // Sin sobre de paginación: es `{ items: [...] }` y nada más.
        assertThat(response.raw).contains("\"items\"").doesNotContain("totalPages")
        assertThat(items).hasSize(1)
        assertThat(items.single()["invoiceNumber"]).isEqualTo("F-DISC")
        assertThat(items.single()["pendingLines"]).isEqualTo(1)
        // La suma de diferencias ABSOLUTAS: positiva aunque falte mercancía.
        assertThat(items.single()["totalDifference"]).isEqualTo("1.000")
    }

    @Test
    fun `el listado filtra por estado y rechaza una pagina negativa`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        capture(token, id, lineIds(token, id).single(), quantity = 9, boxes = 1)

        assertThat(get("/v1/invoices?status=IN_PROGRESS", token).at<Int>("totalItems")).isEqualTo(1)
        assertThat(get("/v1/invoices?status=COMPLETED", token).at<Int>("totalItems")).isZero()

        // R8: se rechaza el valor en lugar de recortarlo en silencio.
        val response = get("/v1/invoices?page=-1", token)
        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PAGINATION")
    }

    @Test
    fun `un estado que no existe se rechaza con la lista admitida`() {
        val token = manager()

        val response = get("/v1/invoices?status=LO_QUE_SEA", token)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PARAMETER")
        assertThat(response.at<Map<String, String>>("details")["allowed"]).contains("IN_PROGRESS")
    }

    // ── R1 y R7: ubicar lo que viene de una factura ─────────────────────────

    @Test
    fun `R1 - una linea en discrepancia no se puede ubicar y el inventario no cambia`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        // Se facturaron 10 y se contaron 9: discrepancia sin aprobar.
        capture(token, id, lineId, quantity = 9, boxes = 1)

        val response = locate(token, lineId, quantity = 9)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("DISCREPANCY_NOT_APPROVED")
        // El inventario no cambió: sigue todo en la recepción y nada en el estante.
        assertThat(quantityAt(token, "RECEPCION")).isEqualTo("9.000")
        assertThat(quantityAt(token, "04-B-C")).isNull()
        // Y la línea sigue esperando decisión.
        assertThat(line(token, id, lineId)["status"]).isEqualTo("DISCREPANCY")
    }

    @Test
    fun `R7 - ubicar desde la factura deja la linea localizada y el total igual`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        val response = locate(token, lineId, quantity = 10)
        val line = line(token, id, lineId)

        assertThat(response.status).isEqualTo(201)
        assertThat(line["status"]).isEqualTo("LOCATED")
        assertThat(line["locatedQuantity"]).isEqualTo("10.000")
        assertThat(line["locationDisplayName"]).isEqualTo("04-B-C")
        // El total del producto no cambia: sale de la recepción y entra en el estante.
        assertThat(quantityAt(token, "RECEPCION")).isEqualTo("0.000")
        assertThat(quantityAt(token, "04-B-C")).isEqualTo("10.000")
        // El catálogo manda las cantidades como número (su excepción documentada).
        assertThat(
            (get("/v1/products?q=ABRAA1B", token).at<List<Map<String, Any?>>>("items").single()["stock"] as Number).toDouble(),
        ).isEqualTo(10.0)
    }

    @Test
    fun `ubicar parcialmente no cierra la linea ni la aprueba`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        locate(token, lineId, quantity = 4)
        val line = line(token, id, lineId)

        // Sigue contada: no hubo discrepancia que aprobar, y la base prohíbe
        // `APPROVED` sin aprobador.
        assertThat(line["status"]).isEqualTo("CAPTURED")
        assertThat(line["locatedQuantity"]).isEqualTo("4.000")
    }

    @Test
    fun `no se puede ubicar una linea sin contar`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()

        val response = locate(token, lineId, quantity = 10)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("INVALID_TRANSITION")
        assertThat(quantityAt(token, "04-B-C")).isNull()
    }

    @Test
    fun `ubicar una linea que no existe o de otro producto se rechaza`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        val inexistente = locate(token, lineId = 999999, quantity = 1)
        assertThat(inexistente.status).isEqualTo(404)
        assertThat(inexistente.code).isEqualTo("LINE_NOT_FOUND")

        // Mover el saldo de un producto mientras se anota la línea de otro dejaría la
        // factura diciendo una cosa y el almacén otra.
        product(token, code = "OTRO01")
        val otro = post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "OTRO01", "fromLocationCode" to "RECEPCION",
                "toLocationCode" to "04-B-C", "quantity" to 1, "invoiceLineId" to lineId,
            ),
            token,
        )
        assertThat(otro.status).isEqualTo(400)
        assertThat(otro.code).isEqualTo("INVALID_PARAMETER")
    }

    // ── Permisos por ruta ───────────────────────────────────────────────────

    @Test
    fun `un operador de almacen no captura ni aprueba facturas`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        // El operador de piso consulta facturas —las necesita para ubicar— pero no
        // las crea ni las cierra: eso es del capturista y del supervisor.
        assertThat(get("/v1/invoices", token).status).isEqualTo(200)
        assertThat(post("/v1/invoices", mapOf("invoiceNumber" to "F-X"), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(post("/v1/invoices/1/close", emptyMap<String, Any>(), token).code).isEqualTo("PERMISSION_DENIED")
    }

    // ── M10/M03: la mercancía entra al contar ───────────────────────────────

    private fun kardexOf(token: String, moveType: String): List<Map<String, Any?>> =
        get("/v1/inventory/kardex?moveType=$moveType", token).at<List<Map<String, Any?>>>("items")

    @Test
    fun `al contar, la mercancia entra en RECEPCION`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()

        val response = capture(token, factura, linea, quantity = 10, boxes = 2)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        // Antes de esto, contar no metía nada: el saldo tenía que aparecer por un ajuste a
        // mano y ubicar desde RECEPCION se encontraba cero.
        assertThat(quantityAt(token, "RECEPCION")).isEqualTo("10.000")
        val entradas = kardexOf(token, "RECEIPT_IN")
        assertThat(entradas).hasSize(1)
        assertThat(entradas.single()["units"]).isEqualTo("10.000")
    }

    @Test
    fun `volver a contar no suma dos veces`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        // Se contaron 9 de 10 facturadas: discrepancia, que el supervisor manda a recuento.
        capture(token, factura, linea, quantity = 9, boxes = 1)
        val rechazo = post(
            "/v1/invoices/$factura/reject-discrepancy",
            mapOf("adjustmentReason" to "COUNT_ERROR", "note" to "Recuento pedido"),
            jefe,
        )
        assertThat(rechazo.status).describedAs(rechazo.raw).isEqualTo(200)

        val response = capture(token, factura, linea, quantity = 8, boxes = 1)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        // Quedan 8: entraron 9 y el recuento sacó 1 con un ajuste por error de conteo. Sin
        // la cuenta de lo ya ingresado, el kardex tendría 17 y el stock no cuadraría con nada.
        assertThat(quantityAt(token, "RECEPCION")).isEqualTo("8.000")
        assertThat(kardexOf(token, "RECEIPT_IN")).hasSize(1)
        val ajustes = kardexOf(token, "ADJUSTMENT_OUT")
        assertThat(ajustes.single()["units"]).isEqualTo("1.000")
    }

    @Test
    fun `contar de mas ingresa la diferencia, no el total`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        capture(token, factura, linea, quantity = 9, boxes = 1)
        post(
            "/v1/invoices/$factura/reject-discrepancy",
            mapOf("adjustmentReason" to "COUNT_ERROR", "note" to "Recuento pedido"),
            jefe,
        )

        val response = capture(token, factura, linea, quantity = 14, boxes = 2)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        // 9 + 5: la segunda entrada es la diferencia, no los 14 otra vez.
        assertThat(quantityAt(token, "RECEPCION")).isEqualTo("14.000")
        assertThat(kardexOf(token, "RECEIPT_IN").map { it["units"] }).containsExactly("9.000", "5.000")
    }

    // ── Asignar el conteo a una persona (M03) ───────────────────────────────

    @Test
    fun `asignar una linea deja quien la cuenta y se ve en el detalle`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        val capturista = createUser("capturista", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)

        val response = put("/v1/invoices/$factura/lines/$linea/assign", mapOf("userId" to capturista), token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<String>("status")).isEqualTo("ASSIGNED")
        assertThat((response.at<Number>("assignedUserId")).toLong()).isEqualTo(capturista)
        // El nombre visible, para que el cliente no tenga que resolverlo.
        assertThat(response.at<String>("assignedUserName")).isEqualTo("Usuario capturista")
        // Y el detalle de la factura lo publica.
        assertThat(line(token, factura, linea)["assignedUserName"]).isEqualTo("Usuario capturista")
    }

    @Test
    fun `sin userId la asignacion es a quien llama`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        val jefa = db.sql("SELECT id FROM users WHERE username = 'jefa'").query(Long::class.java).single()

        val response = put("/v1/invoices/$factura/lines/$linea/assign", mapOf<String, Any?>(), token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat((response.at<Number>("assignedUserId")).toLong()).isEqualTo(jefa)
    }

    @Test
    fun `no se reasigna una linea ya contada`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        capture(token, factura, linea, quantity = 10, boxes = 2)

        val response = put("/v1/invoices/$factura/lines/$linea/assign", mapOf<String, Any?>(), token)

        // El conteo tiene responsable: cambiarlo después sería reescribir quién hizo el trabajo.
        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("INVALID_TRANSITION")
    }

    @Test
    fun `asignar exige el permiso y un usuario que exista`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val suToken = accessTokenOf("operador", USER_PASSWORD)

        val sinPermiso = put("/v1/invoices/$factura/lines/$linea/assign", mapOf<String, Any?>(), suToken)
        val inventado = put("/v1/invoices/$factura/lines/$linea/assign", mapOf("userId" to 999999), token)

        assertThat(sinPermiso.code).isEqualTo("PERMISSION_DENIED")
        assertThat(inventado.status).isEqualTo(422)
        assertThat(inventado.code).isEqualTo("ASSIGNEE_NOT_FOUND")
    }
}
