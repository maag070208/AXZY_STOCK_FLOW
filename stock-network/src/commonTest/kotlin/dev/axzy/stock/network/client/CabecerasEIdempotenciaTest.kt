package dev.axzy.stock.network.client

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.network.MotorGrabado
import dev.axzy.stock.network.StockApi
import dev.axzy.stock.network.cuerpoTexto
import dev.axzy.stock.network.motorSimulado
import dev.axzy.stock.network.testConfig
import dev.axzy.stock.network.tokenStoreWithSession
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.inventory.AdjustmentDirection
import dev.axzy.stock.network.dto.inventory.AdjustmentOrigin
import dev.axzy.stock.network.dto.inventory.AdjustmentReason
import dev.axzy.stock.network.dto.inventory.AdjustmentRequest
import dev.axzy.stock.network.dto.inventory.LocateRequest
import dev.axzy.stock.network.dto.inventory.MoveRequest
import dev.axzy.stock.network.dto.invoice.CaptureLineRequest
import dev.axzy.stock.network.dto.order.PackageLineRequest
import dev.axzy.stock.network.dto.product.ImportReportDto
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CABECERAS (§1.6) E IDEMPOTENCIA (§1.5)
 *
 * Las dos cosas que, si faltan, no se notan hasta que hay un problema real:
 *
 *  · **`Idempotency-Key`**: el móvil sin señal reintenta. Sin esta cabecera, un
 *    reintento duplica un movimiento de inventario, y eso se descubre con un
 *    conteo físico.
 *  · **`X-Client-Channel`**: la auditoría registra el canal, y `MOBILE_OFFLINE`
 *    dispara el camino de sincronización diferida con sus dos horas. Mandar
 *    siempre `MOBILE` oculta que la operación se capturó sin señal.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class CabecerasEIdempotenciaTest {

    private fun MockRequestHandleScope.respuestaOk(cuerpo: String = """{ "id": 1 }""") = respond(
        content = cuerpo,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
    )

    @Test
    fun `toda peticion autenticada lleva identidad, correlacion, canal y zona`() = runTest {
        val (engine, grabado) = motorSimulado { respuestaOk("""{ "items": [] }""") }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        api.products.list()

        val peticion = assertNotNull(grabado.ultimaPara("/v1/products"))
        assertEquals("Bearer access-viejo", peticion.headers[HttpHeaders.Authorization])
        assertEquals("MOBILE", peticion.headers[StockHeaders.CLIENT_CHANNEL])
        assertEquals("America/Mexico_City", peticion.headers[StockHeaders.CLIENT_TIMEZONE])
        // El identificador de correlación viaja en ambas direcciones y aparece
        // en la auditoría del servidor.
        val requestId = assertNotNull(peticion.headers[StockHeaders.REQUEST_ID])
        assertTrue(requestId.length == 36, "no parece un UUID: $requestId")
    }

    @Test
    fun `las peticiones publicas no llevan token`() = runTest {
        val (engine, grabado) = motorSimulado {
            respuestaOk("""{ "status": "UP", "schemaVersion": "4", "version": "1.0.0" }""")
        }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        api.health.health()

        val peticion = assertNotNull(grabado.ultimaPara("/v1/health"))
        assertNull(peticion.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `la paginacion viaja como parametros de consulta y empieza en cero`() = runTest {
        val (engine, grabado) = motorSimulado { respuestaOk("""{ "items": [] }""") }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        api.products.list(ListQuery(page = 2, size = 25, sort = "productCode,asc", q = "abra"))

        val url = assertNotNull(grabado.ultimaPara("/v1/products")).url
        assertEquals("2", url.parameters["page"])
        assertEquals("25", url.parameters["size"])
        assertEquals("productCode,asc", url.parameters["sort"])
        assertEquals("abra", url.parameters["q"])
    }

    @Test
    fun `un filtro nulo no se envia en la consulta`() = runTest {
        val (engine, grabado) = motorSimulado { respuestaOk("""{ "items": [] }""") }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        api.inventory.stock()

        val url = assertNotNull(grabado.ultimaPara("/v1/inventory/stock")).url
        // `?productId=null` filtraría por el texto «null»: es el error que
        // aparece una vez en producción y cuesta una tarde encontrar.
        assertNull(url.parameters["productId"])
        assertNull(url.parameters["onlyWithStock"])
        assertEquals("0", url.parameters["page"])
    }

    @Test
    fun `el ajuste lleva la clave de idempotencia en la cabecera y en el cuerpo`() = runTest {
        val (engine, grabado) = motorSimulado { respuestaOk("""{ "id": 501 }""") }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        api.inventory.adjustment(
            AdjustmentRequest(
                productId = 1,
                locationId = 2,
                quantity = 5.0,
                direction = AdjustmentDirection.OUT,
                reason = AdjustmentReason.DAMAGE,
                origin = AdjustmentOrigin.DAMAGE_REPORT,
                note = null,
                idempotencyKey = "uuid-ajuste",
            )
        )

        val peticion = assertNotNull(grabado.ultimaPara("/v1/inventory/adjustments"))
        assertEquals("uuid-ajuste", peticion.headers[StockHeaders.IDEMPOTENCY_KEY])
        val cuerpo = peticion.cuerpoTexto()
        assertTrue(cuerpo.contains("\"idempotencyKey\":\"uuid-ajuste\""), cuerpo)
    }

    @Test
    fun `ubicar, mover, capturar y empaquetar tambien son idempotentes`() = runTest {
        val (engine, grabado) = motorSimulado { respuestaOk("""{ "id": 1 }""") }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        api.inventory.locate(LocateRequest(88, 2, 100.0, "clave-locate"))
        api.inventory.move(MoveRequest(1, 2, 3, 10.0, "clave-move"))
        api.invoices.captureLine(15, 88, CaptureLineRequest(80.0, 1.6, "clave-capture"))
        api.orders.packageLine(30, 55, PackageLineRequest(10.0, 2, "clave-package"))

        assertEquals("clave-locate", grabado.ultimaPara("/v1/inventory/locate")?.headers?.get(StockHeaders.IDEMPOTENCY_KEY))
        assertEquals("clave-move", grabado.ultimaPara("/v1/inventory/move")?.headers?.get(StockHeaders.IDEMPOTENCY_KEY))
        assertEquals("clave-capture", grabado.ultimaPara("/v1/invoices/15/lines/88/capture")?.headers?.get(StockHeaders.IDEMPOTENCY_KEY))
        assertEquals("clave-package", grabado.ultimaPara("/v1/orders/30/lines/55/package")?.headers?.get(StockHeaders.IDEMPOTENCY_KEY))
    }

    @Test
    fun `el canal MOBILE_OFFLINE se declara para que la auditoria lo sepa`() = runTest {
        val (engine, grabado) = motorSimulado { respuestaOk("""{ "id": 1 }""") }
        val api = StockApi(
            config = testConfig().copy(channel = ClientChannel.MOBILE_OFFLINE),
            tokens = tokenStoreWithSession(),
            engine = engine,
        )

        api.inventory.move(MoveRequest(1, 2, 3, 10.0, "clave"))

        assertEquals(
            "MOBILE_OFFLINE",
            grabado.ultimaPara("/v1/inventory/move")?.headers?.get(StockHeaders.CLIENT_CHANNEL),
        )
    }

    @Test
    fun `el import del catalogo se envia como multipart con el archivo y dryRun`() = runTest {
        val (engine, grabado) = motorSimulado {
            respuestaOk("""{ "dryRun": true, "totalRows": 2, "accepted": 2, "rejected": 0 }""")
        }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        val resultado = api.products.importFile(
            fileBytes = "productCode,name\nABRAA1B,ABRAZADERA\n".encodeToByteArray(),
            fileName = "catalogo.csv",
            dryRun = true,
        )

        assertIs<Either.Right<ImportReportDto>>(resultado)
        val peticion = assertNotNull(grabado.ultimaPara("/v1/products/import"))
        // El contrato exige `multipart/form-data` con el campo `file`.
        assertTrue(
            peticion.body.contentType?.toString()?.startsWith("multipart/form-data") == true,
            "content-type: ${peticion.body.contentType}",
        )
    }
}
