package dev.axzy.stock.domain.transfer

import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA MÁQUINA DE ESTADOS DEL TRASLADO
 *
 * **La invariante que estas pruebas protegen está en el contrato §7:**
 *
 * > `Σ stock_by_location + Σ stock_in_transit` se conserva en cada transición.
 *
 * La prueba de esa invariante de verdad —con la base y el stock— está en el test
 * de integración. Aquí se prueba **qué transiciones son legales**, que es lo que
 * la base no puede comprobar: un `UPDATE ... SET status='RECEIVED'` siempre
 * funcionaría, y es justo por eso por lo que el estado no se escribe a mano.
 *
 * Son pruebas PURAS: no tocan base ni servidor, así que corren en milisegundos y
 * **no se saltan nunca**.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class TransferStateMachineTest : DescribeSpec({

    fun linea(cantidad: Long = 1000) = TransferLine(
        id = 1,
        productId = 1,
        productCode = "AAA111",
        productName = "Producto",
        quantity = Quantity.ofScaled(cantidad),
    )

    fun transfer(
        status: TransferStatus,
        lines: List<TransferLine> = listOf(linea()),
    ) = Transfer(
        id = 1,
        code = "TR-2026-0001",
        fromWarehouseId = 1,
        toWarehouseId = 2,
        status = status,
        lines = lines,
    )

    describe("the legal transitions") {

        it("DRAFT → dispatching leaves the transfer DISPATCHED with its owner") {
            val resultado = transfer(TransferStatus.DRAFT).dispatch(userId = 7)

            val despachado = resultado.getOrNull()!!
            despachado.status shouldBe TransferStatus.DISPATCHED
            despachado.dispatchedBy shouldBe 7
        }

        it("DISPATCHED → receiving leaves the transfer RECEIVED with its owner") {
            val resultado = transfer(TransferStatus.DISPATCHED).receive(userId = 9)

            val recibido = resultado.getOrNull()!!
            recibido.status shouldBe TransferStatus.RECEIVED
            recibido.receivedBy shouldBe 9
        }

        it("IN_TRANSIT can also be received: it is the normal path") {
            // `DISPATCHED` e `IN_TRANSIT` son dos hechos distintos y los dos
            // permiten recibir. Olvidar el segundo dejaría traslados que viajan
            // legítimamente sin poder cerrarse.
            transfer(TransferStatus.IN_TRANSIT).receive(1).getOrNull()!!.status shouldBe
                TransferStatus.RECEIVED
        }

        it("cancelling DRAFT and DISPATCHED is legal") {
            transfer(TransferStatus.DRAFT).cancel().getOrNull()!!.status shouldBe
                TransferStatus.CANCELLED
            transfer(TransferStatus.DISPATCHED).cancel().getOrNull()!!.status shouldBe
                TransferStatus.CANCELLED
        }
    }

    describe("the ILLEGAL transitions, and the damage they would do") {

        it("receiving a DRAFT is rejected: goods that never left would arrive") {
            val error = transfer(TransferStatus.DRAFT).receive(1).errorOrNull()

            // **El daño concreto:** el inventario de destino crecería sin que el
            // de origen hubiera bajado, así que el total SUBIRÍA. Es una creación
            // de mercancía de la nada, y ningún reporte la señalaría como error
            // porque un total que sube parece una entrada legítima.
            error.shouldBeInstanceOf<DomainError.InvalidTransition>()
            (error as DomainError.InvalidTransition).code shouldBe "INVALID_TRANSITION"
            error.from shouldBe "DRAFT"
            error.action shouldBe "recibir"
        }

        it("receiving an ALREADY received transfer is rejected") {
            transfer(TransferStatus.RECEIVED).receive(1).errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("cancelling a RECEIVED transfer is rejected: what was received is not erased") {
            // Si se pudiera, la mercancía quedaría en las ubicaciones de destino
            // con un traslado cancelado: contada en el sitio equivocado y sin
            // rastro. Para deshacerlo se hace un traslado de vuelta.
            val error = transfer(TransferStatus.RECEIVED).cancel().errorOrNull()

            error.shouldBeInstanceOf<DomainError.InvalidTransition>()
            (error as DomainError.InvalidTransition).action shouldBe "cancelar"
        }

        it("cancelling a CANCELLED is rejected: it is not cancelled twice") {
            transfer(TransferStatus.CANCELLED).cancel().errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("dispatching an already dispatched transfer is rejected") {
            // Es el que evita el doble descuento: si se despachara dos veces, la
            // mercancía saldría dos veces de las ubicaciones de origen y el total
            // BAJARÍA. Un total que baja de golpe parece una venta, no un error.
            transfer(TransferStatus.DISPATCHED).dispatch(1).errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("dispatching a transfer without lines is rejected") {
            val error = transfer(TransferStatus.DRAFT, lines = emptyList()).dispatch(1).errorOrNull()

            error.shouldBeInstanceOf<DomainError.EmptyTransfer>()
            (error as DomainError.EmptyTransfer).code shouldBe "EMPTY_TRANSFER"
        }
    }

    describe("the rules that do not depend on the state") {

        it("same origin and destination is rejected, and it is what no report would see") {
            val error = Transfer.validate(
                fromWarehouseId = 1,
                toWarehouseId = 1,
                lines = listOf(linea()),
            ).errorOrNull()

            // **Por qué esta regla existe.** La mercancía saldría de las
            // ubicaciones y entraría en tránsito hacia la misma bodega: el total
            // NO cambia, así que la reconciliación sale cuadrada y el informe de
            // existencias solo enseña menos mercancía. Es un agujero que se
            // descubre con un conteo físico meses después.
            error.shouldBeInstanceOf<DomainError.SameWarehouse>()
            (error as DomainError.SameWarehouse).code shouldBe "SAME_WAREHOUSE"
        }

        it("without lines it is rejected: a transit debt with no goods to explain it") {
            Transfer.validate(1, 2, emptyList()).errorOrNull()
                .shouldBeInstanceOf<DomainError.EmptyTransfer>()
        }

        it("a different origin with lines is accepted") {
            Transfer.validate(1, 2, listOf(linea())).isRight shouldBe true
        }
    }

    describe("what the transfer computes from its lines") {

        it("`lineCount` counts products and `totalUnits` adds quantities") {
            val t = transfer(
                TransferStatus.DRAFT,
                lines = listOf(linea(1000), linea(500), linea(250)),
            )

            t.lineCount shouldBe 3
            // `Quantity` guarda milésimas enteras, así que la suma es exacta: no
            // hay coma flotante que redondear. Es la misma representación que usa
            // el resto del sistema.
            t.totalUnits shouldBe Quantity.ofScaled(1750)
        }

        it("a transfer without lines adds up to zero, it does not fail") {
            val t = transfer(TransferStatus.DRAFT, lines = emptyList())

            t.lineCount shouldBe 0
            t.totalUnits shouldBe Quantity.ZERO
        }

        it("`isInTransit` is true only when the goods are outside the locations") {
            // Es la consulta que alimenta `GET /v1/transfers/in-transit`. Si
            // incluyera `DRAFT`, el informe enseñaría como «en camino» mercancía
            // que todavía está en su estante.
            transfer(TransferStatus.DRAFT).status.isInTransit shouldBe false
            transfer(TransferStatus.DISPATCHED).status.isInTransit shouldBe true
            transfer(TransferStatus.IN_TRANSIT).status.isInTransit shouldBe true
            transfer(TransferStatus.RECEIVED).status.isInTransit shouldBe false
            transfer(TransferStatus.CANCELLED).status.isInTransit shouldBe false
        }

        it("only DRAFT is editable") {
            // Con la mercancía ya en el camión, cambiar las líneas haría que lo
            // despachado y lo declarado dejaran de coincidir.
            transfer(TransferStatus.DRAFT).status.isEditable shouldBe true
            transfer(TransferStatus.DISPATCHED).status.isEditable shouldBe false
            transfer(TransferStatus.IN_TRANSIT).status.isEditable shouldBe false
            transfer(TransferStatus.RECEIVED).status.isEditable shouldBe false
        }
    }
})
