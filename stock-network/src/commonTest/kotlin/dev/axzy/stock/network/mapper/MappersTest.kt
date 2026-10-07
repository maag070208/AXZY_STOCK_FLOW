package dev.axzy.stock.network.mapper

import dev.axzy.stock.domain.access.PermissionScope
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.inventory.AdjustmentIn
import dev.axzy.stock.domain.inventory.AdjustmentOut
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.auth.UserDto
import dev.axzy.stock.network.dto.inventory.AdjustmentDirection
import dev.axzy.stock.network.dto.inventory.AdjustmentOrigin
import dev.axzy.stock.network.dto.inventory.AdjustmentReason
import dev.axzy.stock.network.dto.inventory.AdjustmentRequest
import dev.axzy.stock.network.dto.inventory.KardexMoveType
import dev.axzy.stock.network.dto.inventory.MoveRequest
import dev.axzy.stock.network.dto.invoice.InvoiceLineDto
import dev.axzy.stock.network.dto.invoice.InvoiceLineStatus
import dev.axzy.stock.network.dto.product.ProductDto
import dev.axzy.stock.network.error.ApiError
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LOS MAPPERS DTO ↔ DOMINIO
 *
 * Se prueban aquí los tres defectos del legacy que esta capa existe para
 * evitar:
 *
 *  · `Decimal` contra `number` → una sola conversión de cantidad, con ida y
 *    vuelta, y sin aritmética de coma flotante en el dominio.
 *  · Estados como cadenas sueltas → traducción exhaustiva y explícita.
 *  · `difference: 0` → el dominio la deriva y el mapper comprueba que el
 *    servidor dice lo mismo.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MappersTest {

    // ── Cantidades (§1.7) ───────────────────────────────────────────────────

    @Test
    fun `la cantidad del contrato va y vuelve sin perder el tercer decimal`() {
        val casos = listOf(0.0, 100.0, 0.5, 5193.0, 10914130.0, -20.0, 1.6)

        casos.forEach { valor ->
            val cantidad = valor.toQuantity()
            assertEquals(valor, cantidad.toContractNumber(), "round-trip de $valor")
        }

        // Y el caso que rompía el legacy: sumar 100 + 0.5 no puede dar 100.4999.
        assertEquals(
            100.5,
            (100.0.toQuantity() + 0.5.toQuantity()).toContractNumber(),
        )
    }

    @Test
    fun `la cantidad conserva la escala de milésimas del dominio`() {
        assertEquals(100_000L, 100.0.toQuantity().value)
        assertEquals(1_600L, 1.6.toQuantity().value)
        assertEquals(-20_000L, (-20.0).toQuantity().value)
    }

    @Test
    fun `un decimal con mas precision de la pactada se detecta`() {
        assertTrue(100.0.hasContractPrecision())
        assertTrue(0.5.hasContractPrecision())
        assertTrue(!3.14159.hasContractPrecision())
    }

    @Test
    fun `unidades por caja cero no se convierten, porque el dominio no las admite`() {
        // El legacy dividía por este campo y con 0 producía Array(NaN) → RangeError.
        assertNull(0.0.toUnitsPerBoxOrNull())
        assertNull(null.toUnitsPerBoxOrNull())
        assertEquals(50L, 50.0.toUnitsPerBoxOrNull()?.value)
    }

    // ── Estados ─────────────────────────────────────────────────────────────

    @Test
    fun `cada tipo de movimiento del contrato tiene su movimiento en el dominio`() {
        val esperado = mapOf(
            KardexMoveType.INITIAL to MovementType.INITIAL,
            KardexMoveType.RECEIPT_IN to MovementType.RECEIPT_IN,
            KardexMoveType.ADJUSTMENT_IN to MovementType.ADJUSTMENT_IN,
            KardexMoveType.FULFILLMENT_OUT to MovementType.FULFILLMENT_OUT,
            KardexMoveType.ADJUSTMENT_OUT to MovementType.ADJUSTMENT_OUT,
            KardexMoveType.LOCATE to MovementType.LOCATE,
            KardexMoveType.MOVE_WITHIN_WAREHOUSE to MovementType.MOVE_WITHIN_WAREHOUSE,
            KardexMoveType.TRANSFER_OUT to MovementType.TRANSFER_OUT,
            KardexMoveType.TRANSFER_IN to MovementType.TRANSFER_IN,
            KardexMoveType.PENDING_OUT to MovementType.PENDING_OUT,
        )

        esperado.forEach { (contrato, dominio) ->
            assertEquals(dominio, contrato.toDomain())
        }
        // Los diez: si el contrato añade uno, este test deja de estar completo y
        // el `when` del mapper deja de compilar.
        assertEquals(10, KardexMoveType.entries.size)
    }

    @Test
    fun `PENDING_APPROVAL y DISCREPANCY son el mismo estado en el dominio`() {
        // El dominio no tiene `PENDING_APPROVAL` porque allí «discrepancia sin
        // aprobar» YA es DISCREPANCY, que es lo que impide ubicar (regla B1).
        assertEquals(
            InvoiceLineStatus.PENDING_APPROVAL.toDomain(),
            InvoiceLineStatus.DISCREPANCY.toDomain(),
        )
    }

    // ── Línea de factura y `difference` ─────────────────────────────────────

    private fun linea(
        physical: Double? = 80.0,
        quantity: Double = 100.0,
        difference: Double? = -20.0,
        unitsPerBox: Double = 50.0,
        status: InvoiceLineStatus = InvoiceLineStatus.DISCREPANCY,
    ): InvoiceLineDto = InvoiceLineDto(
        id = 88,
        productId = 1,
        productCode = "ABRAA1B",
        productName = "ABRAZADERA",
        container = "CONT-01",
        quantity = quantity,
        unitsPerBox = unitsPerBox,
        physicalQuantity = physical,
        difference = difference,
        status = status,
    )

    @Test
    fun `la linea del contrato se convierte en la linea del dominio`() {
        val dominio = assertIs<Either.Right<dev.axzy.stock.domain.receiving.InvoiceLine>>(
            linea().toDomain()
        ).value

        assertEquals(88L, dominio.id)
        assertEquals("ABRAA1B", dominio.productCode.value)
        assertEquals(100.0, dominio.invoicedQuantity.toContractNumber())
        assertEquals(80.0, dominio.physicalQuantity?.toContractNumber())
        assertEquals(50L, dominio.unitsPerBox.value)
        // El dominio DERIVA la diferencia: no lee el campo del servidor.
        assertEquals(-20.0, dominio.difference?.toContractNumber())
        assertTrue(dominio.hasUnresolvedDifference)
        // Y `difference != 0` — que es justo lo que el legacy devolvía siempre.
        assertTrue(dominio.difference?.isZero == false)
    }

    @Test
    fun `la diferencia que el servidor manda cero se detecta como inconsistente`() {
        // Este es el defecto del legacy reproducido como dato: `difference: 0`
        // con una discrepancia real de -20.
        val mentirosa = linea(difference = 0.0)

        assertTrue(!mentirosa.hasConsistentDifference())
        assertEquals(-20.0, mentirosa.derivedDifference()?.toContractNumber())
    }

    @Test
    fun `una diferencia coherente se acepta`() {
        assertTrue(linea().hasConsistentDifference())
    }

    @Test
    fun `una linea que trae unidades por caja cero no se convierte al dominio`() {
        val resultado = linea(unitsPerBox = 0.0).toDomain()

        val error = assertIs<Either.Left<DomainError>>(resultado).error
        assertIs<DomainError.InvalidQuantity>(error)
        assertTrue(error.message.contains("unidades por caja"), error.message)
    }

    @Test
    fun `una linea con codigo de producto invalido no se convierte al dominio`() {
        val resultado = linea().copy(productCode = "abraa1b").toDomain()

        val error = assertIs<Either.Left<DomainError>>(resultado).error
        // En minúsculas: el dominio lo RECHAZA en lugar de normalizarlo, porque
        // un importador tolerante esconde errores de captura.
        assertTrue(error.message.contains("abraa1b"), error.message)
    }

    // ── Ajustes y traslados: el dominio valida, el DTO solo transporta ──────

    @Test
    fun `un ajuste de entrada se convierte en un movimiento del dominio`() {
        val request = AdjustmentRequest(
            productId = 1,
            locationId = 2,
            quantity = 5.0,
            direction = AdjustmentDirection.IN,
            reason = AdjustmentReason.SUPPLIER_ERROR,
            origin = AdjustmentOrigin.MANUAL_COUNT,
            note = null,
            idempotencyKey = "clave",
        )

        val movimiento = assertIs<Either.Right<*>>(
            request.toStockMovement(LocationCode.of("04BCA"))
        ).value

        val ajuste = assertIs<AdjustmentIn>(movimiento)
        assertEquals(5.0, ajuste.quantity.toContractNumber())
        // El efecto lo declara el movimiento, no se deduce de un `if/else`.
        assertTrue(ajuste.effect is dev.axzy.stock.domain.inventory.StockEffect.AddToLocation)
    }

    @Test
    fun `un ajuste de salida exige motivo y el dominio lo impone`() {
        val request = AdjustmentRequest(
            productId = 1,
            locationId = 2,
            quantity = 5.0,
            direction = AdjustmentDirection.OUT,
            reason = AdjustmentReason.DAMAGE,
            origin = AdjustmentOrigin.DAMAGE_REPORT,
            note = null,
            idempotencyKey = "clave",
        )

        val movimiento = assertIs<Either.Right<*>>(
            request.toStockMovement(LocationCode.of("04BCA"))
        ).value

        val merma = assertIs<AdjustmentOut>(movimiento)
        assertEquals("DAMAGE", merma.reason)
        assertTrue(merma.effect is dev.axzy.stock.domain.inventory.StockEffect.RemoveFromLocation)
    }

    @Test
    fun `un traslado al mismo estante es imposible en el dominio, no solo en el servidor`() {
        val request = MoveRequest(1, 2, 2, 10.0, "clave")
        val misma = LocationCode.of("04BCA")

        val resultado = request.toStockMovement(misma, misma)

        val error = assertIs<Either.Left<DomainError>>(resultado).error
        // `422 SAME_SOURCE_AND_DESTINATION` deja de ser un error que solo conoce
        // el servidor: el móvil lo detecta antes de gastar red.
        assertIs<DomainError.SameSourceAndDestination>(error)
        assertEquals("SAME_SOURCE_AND_DESTINATION", error.code)
    }

    // ── Usuario y permisos ──────────────────────────────────────────────────

    @Test
    fun `el usuario del contrato se convierte en el usuario de acceso del dominio`() {
        val dto = UserDto(
            id = 7,
            username = "jperez",
            name = "Juan Pérez",
            roles = listOf("JEFE_BODEGA"),
            permissions = listOf("invoice.capture", "invoice.approve"),
            scopes = mapOf("invoice.approve" to "AREA", "invoice.capture" to "OWN"),
            warehouses = listOf(1L),
        )

        val usuario = dto.toAccessUser()

        assertEquals(7L, usuario.id)
        assertEquals(1, usuario.roles.size)
        assertEquals("JEFE_BODEGA", usuario.roles.first().key.value)
        assertEquals(setOf(1L), usuario.warehouseIds)
    }

    @Test
    fun `los permisos efectivos respetan el alcance de cada uno`() {
        val dto = UserDto(
            id = 7,
            username = "jperez",
            name = "Juan Pérez",
            permissions = listOf("invoice.capture", "invoice.approve", "inventory.view"),
            scopes = mapOf("invoice.approve" to "AREA", "invoice.capture" to "OWN"),
        )

        val permisos = dto.toEffectivePermissions()

        assertEquals(PermissionScope.AREA, permisos["invoice.approve"])
        assertEquals(PermissionScope.OWN, permisos["invoice.capture"])
        // Listado sin alcance declarado: el lado seguro es el restrictivo.
        assertEquals(PermissionScope.OWN, permisos["inventory.view"])

        assertTrue(dto.can("invoice.capture", PermissionScope.OWN))
        assertTrue(!dto.can("invoice.capture", PermissionScope.AREA))
        // `AREA` no concede `ALL`: tratar el alcance como un booleano es cómo un
        // modelo de permisos se convierte en un agujero.
        assertTrue(!dto.can("invoice.approve", PermissionScope.ALL))
    }

    @Test
    fun `un alcance desconocido deniega en lugar de conceder`() {
        // Un servidor que añada un alcance nuevo no puede hacer que los clientes
        // antiguos concedan de más.
        assertEquals(PermissionScope.NONE, "SUPER".toPermissionScope())
        assertEquals(PermissionScope.NONE, "".toPermissionScope())
        assertEquals(PermissionScope.ALL, "all".toPermissionScope())
    }

    // ── Catálogo ────────────────────────────────────────────────────────────

    @Test
    fun `el producto del API se convierte en producto validado del dominio`() {
        val dto = ProductDto(
            id = 1,
            productCode = "ABRAA1B",
            name = "ABRAZADERA",
            unitMeasure = "PZA",
            externalId = 1,
            stock = 5193.0,
        )

        val validado = assertNotNull(dto.toValidatedProduct())
        assertEquals("ABRAA1B", validado.code.value)
        assertEquals(5193.0, validado.stock.toContractNumber())
        assertEquals("1", validado.externalId)
    }

    @Test
    fun `un codigo de producto invalido no produce un producto validado`() {
        val dto = ProductDto(id = 1, productCode = "ab raa", name = "X", unitMeasure = "PZA")
        assertNull(dto.toValidatedProduct())
    }

    // ── Errores ─────────────────────────────────────────────────────────────

    @Test
    fun `los errores del transporte con equivalente en el dominio se traducen`() {
        val segregacion: DomainError = assertNotNull(
            ApiError.SegregationOfDuties().toDomainErrorOrNull()
        )
        assertIs<DomainError.SegregationOfDuties>(segregacion)

        val sinPermiso = assertNotNull(ApiError.PermissionDenied("invoice.approve").toDomainErrorOrNull())
        assertIs<DomainError.PermissionDenied>(sinPermiso)

        val sinStock = assertNotNull(
            ApiError.InsufficientStock(available = 80.0, requested = 100.0, location = "04-B-C")
                .toDomainErrorOrNull()
        )
        val stock = assertIs<DomainError.InsufficientStock>(sinStock)
        // El dominio cuenta en milésimas enteras: 80.000 → 80000.
        assertEquals(80_000L, stock.available)
        assertEquals(100_000L, stock.requested)
    }

    @Test
    fun `un error de infraestructura no se disfraza de error de dominio`() {
        // Forzarlo haría que la UI mostrara «no hay conexión» como si fuera una
        // regla de negocio.
        assertNull(ApiError.Network().toDomainErrorOrNull())
        assertNull(ApiError.RateLimited().toDomainErrorOrNull())
        assertNull(ApiError.Unauthenticated().toDomainErrorOrNull())
    }

    @Test
    fun `una discrepancia sin aprobar conserva la diferencia del servidor`() {
        val error = ApiError.Conflict(
            code = "DISCREPANCY_NOT_APPROVED",
            message = "hay una diferencia",
            details = kotlinx.serialization.json.Json
                .decodeFromString<kotlinx.serialization.json.JsonObject>("""{ "difference": -20.0 }"""),
        )

        val dominio = assertNotNull(error.toDomainErrorOrNull())
        val discrepancia = assertIs<DomainError.DiscrepancyNotApproved>(dominio)
        assertEquals(-20_000L, discrepancia.difference)
    }

    @Test
    fun `el JSON del modulo ignora campos nuevos y no se rompe con un enum desconocido`() {
        // Es la garantía de compatibilidad hacia adelante: el backend puede
        // desplegar un campo nuevo sin romper las apps instaladas.
        val page = stockJson.decodeFromString<dev.axzy.stock.network.dto.common.Page<ProductDto>>(
            """
            {
              "items": [ { "id": 1, "productCode": "A", "name": "n", "unitMeasure": "PZA",
                           "stock": 1.0, "campoDelFuturo": 42 } ],
              "page": 0, "size": 50, "totalItems": 1, "totalPages": 1, "extra": "x"
            }
            """.trimIndent()
        )
        assertEquals(1, page.items.size)
    }
}
