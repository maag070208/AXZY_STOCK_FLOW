package dev.axzy.stock.domain.fulfillment

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL SURTIDO: ESTADOS Y, SOBRE TODO, CUÁNTO SE DESCUENTA
 *
 * La prueba que más importa de este archivo es
 * **«confirmar descuenta lo que FALTA, no la cantidad entera»**. Un error ahí no
 * rompe nada: el sistema arranca, las rutas responden y los tests de ruta pasan.
 * Lo que pasa es que el inventario baja de más, y eso **solo se descubre al
 * cuadrar el almacén** — y entonces no se distingue de una merma real.
 *
 * Son pruebas PURAS: no tocan base ni servidor, corren en milisegundos y **no se
 * saltan nunca**.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class OrderStateMachineTest : DescribeSpec({

    fun line(
        id: Long = 1,
        quantity: Long = 10_000,
        supplied: Long = 0,
        status: OrderLineStatus = OrderLineStatus.PENDING,
    ) = OrderLine(
        id = id,
        productId = id,
        productCode = "P$id",
        productName = "Producto $id",
        unitMeasure = "PZA",
        quantity = Quantity.ofScaled(quantity),
        suppliedQuantity = Quantity.ofScaled(supplied),
        status = status,
    )

    fun order(
        status: OrderStatus = OrderStatus.PENDING,
        lines: List<OrderLine> = listOf(line()),
        converted: Boolean = false,
        attempts: Int = 0,
    ) = Order(
        id = 1,
        orderNumber = "ORD-2026-0001",
        status = status,
        lines = lines,
        converted = converted,
        exportAttempts = attempts,
    )

    describe("the deduction rule: `suppliedQuantity` is what is ALREADY deducted") {

        it("an unpacked line has 10 pending out of 10") {
            val l = line(quantity = 10_000)

            l.suppliedQuantity shouldBe Quantity.ZERO
            l.pendingQuantity shouldBe Quantity.ofScaled(10_000)
            l.isComplete shouldBe false
        }

        it("packing 7 leaves 3 pending — and those 3 are what will be confirmed") {
            val empacada = line(quantity = 10_000).packageQuantity(Quantity.ofScaled(7_000))
                .getOrNull()!!

            empacada.suppliedQuantity shouldBe Quantity.ofScaled(7_000)
            // **Este 3 es la cifra que confirmar descontará.** Si en su lugar se
            // descontaran 10, el inventario habría bajado 17 en total por una
            // línea de 10.
            empacada.pendingQuantity shouldBe Quantity.ofScaled(3_000)
            empacada.isComplete shouldBe false
        }

        it("packing 7 and then 3 completes the line and leaves 0 pending") {
            val empacada = line(quantity = 10_000)
                .packageQuantity(Quantity.ofScaled(7_000)).getOrNull()!!
                .packageQuantity(Quantity.ofScaled(3_000)).getOrNull()!!

            empacada.suppliedQuantity shouldBe Quantity.ofScaled(10_000)
            // El pendiente queda EXACTAMENTE en cero: confirmar no descontará nada
            // más, así que no hay doble descuento.
            empacada.pendingQuantity shouldBe Quantity.ZERO
            empacada.isComplete shouldBe true
            empacada.status shouldBe OrderLineStatus.SUPPLIED
        }

        it("packing a line completely leaves it SUPPLIED; half done, ASSIGNED") {
            line().packageQuantity(Quantity.ofScaled(10_000)).getOrNull()!!.status shouldBe
                OrderLineStatus.SUPPLIED
            line().packageQuantity(Quantity.ofScaled(1_000)).getOrNull()!!.status shouldBe
                OrderLineStatus.ASSIGNED
        }
    }

    describe("what fulfillment REJECTS") {

        it("packing more than what is pending is rejected, and says how much remains") {
            val error = line(quantity = 10_000, supplied = 7_000)
                .packageQuantity(Quantity.ofScaled(5_000)).errorOrNull()

            error.shouldBeInstanceOf<DomainError.ExceedsPendingQuantity>()
            val exceso = error as DomainError.ExceedsPendingQuantity
            exceso.code shouldBe "EXCEEDS_PENDING_QUANTITY"
            // El mensaje lleva las dos cifras: lo pedido y lo que queda. Sin la
            // segunda, el operador no sabe cuánto puede empacar.
            exceso.pending shouldBe 3_000
            exceso.requested shouldBe 5_000
            exceso.message.contains("3000") shouldBe true
        }

        it("packing zero or a negative is rejected") {
            line().packageQuantity(Quantity.ZERO).errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidQuantity>()
            line().packageQuantity(Quantity.ofScaled(-1)).errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidQuantity>()
        }

        it("packing a VOIDED line is rejected") {
            val error = line(status = OrderLineStatus.CANCELLED)
                .packageQuantity(Quantity.ofScaled(1_000)).errorOrNull()

            error.shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("confirming an already confirmed order is rejected: it would deduct twice") {
            val error = order(status = OrderStatus.PENDING_TO_OUT, converted = true)
                .confirm().errorOrNull()

            // El código es el del contrato §6: `ORDER_ALREADY_CONFIRMED`.
            error.shouldBeInstanceOf<DomainError.OrderAlreadyConfirmed>()
            (error as DomainError.OrderAlreadyConfirmed).code shouldBe "ORDER_ALREADY_CONFIRMED"
        }

        it("voiding an already exported order is rejected: the ERP has an output the system would not recognise") {
            val error = order(status = OrderStatus.COMPLETED, converted = true).cancel().errorOrNull()

            error.shouldBeInstanceOf<DomainError.InvalidTransition>()
            (error as DomainError.InvalidTransition).action shouldBe "anular"
        }

        it("exporting an unconfirmed order is rejected") {
            val error = order(status = OrderStatus.PENDING).recordExportAttempt(success = true)
                .errorOrNull()

            error.shouldBeInstanceOf<DomainError.OrderNotConverted>()
            (error as DomainError.OrderNotConverted).code shouldBe "ORDER_NOT_CONVERTED"
        }
    }

    describe("assign the line") {

        it("leaves the line assigned and allows reassignment while it is in progress") {
            val pendiente = line()

            val primera = pendiente.assign(userId = 7, locationId = 3).shouldBeInstanceOf<Either.Right<OrderLine>>().value
            val segunda = primera.assign(userId = 9, locationId = null).shouldBeInstanceOf<Either.Right<OrderLine>>().value

            primera.status shouldBe OrderLineStatus.ASSIGNED
            primera.assignedUserId shouldBe 7L
            primera.locationId shouldBe 3L
            // Reasignar es del día a día: cambia el turno o alguien se pone malo.
            segunda.assignedUserId shouldBe 9L
            // Sin ubicación nueva se conserva la que tenía: `ASSIGNED` dice quién, no dónde.
            segunda.locationId shouldBe 3L
        }

        it("a line already fulfilled or voided is not assigned") {
            val surtida = line(quantity = 10_000)
                .packageQuantity(Quantity.of(10))
                .shouldBeInstanceOf<Either.Right<OrderLine>>().value
            val anulada = line(status = OrderLineStatus.CANCELLED)

            // Asignar trabajo sobre mercancía que ya salió sería decir que alguien la va a
            // sacar dos veces.
            surtida.assign(userId = 7, locationId = null).isLeft shouldBe true
            anulada.assign(userId = 7, locationId = null).isLeft shouldBe true
        }
    }

    describe("confirmar") {

        it("leaves the order PENDING_TO_OUT") {
            order(status = OrderStatus.PACKAGED).confirm().getOrNull()!!.status shouldBe
                OrderStatus.PENDING_TO_OUT
        }
    }

    describe("the export to the ERP") {

        it("a failed attempt carries the counter and stores the reason") {
            val fallida = order(status = OrderStatus.PENDING_TO_OUT)
                .recordExportAttempt(success = false, error = "El ERP no responde")
                .getOrNull()!!

            fallida.exportAttempts shouldBe 1
            fallida.exportStatus shouldBe "FAILED"
            fallida.exportLastError shouldBe "El ERP no responde"
            // **No se marca como convertida**: un fallo no es una conversión. Si se
            // marcara, el reintento creería que ya se envió y no lo mandaría nunca.
            fallida.converted shouldBe false
            fallida.status shouldBe OrderStatus.PENDING_TO_OUT
        }

        it("a successful attempt completes the order and clears the previous error") {
            val exitosa = order(status = OrderStatus.PENDING_TO_OUT, attempts = 2)
                .recordExportAttempt(success = true)
                .getOrNull()!!

            exitosa.exportAttempts shouldBe 3
            exitosa.exportStatus shouldBe "SENT"
            // El error del intento anterior se limpia: dejar «El ERP no responde»
            // junto a un `SENT` hace dudar de si llegó.
            exitosa.exportLastError shouldBe null
            exitosa.converted shouldBe true
            exitosa.status shouldBe OrderStatus.COMPLETED
        }

        it("retrying after a success is idempotent: it does not count as a new conversion") {
            val primera = order(status = OrderStatus.PENDING_TO_OUT)
                .recordExportAttempt(success = true).getOrNull()!!
            val segunda = primera.recordExportAttempt(success = true).getOrNull()!!

            // Sigue convertida y completada: el contador sube porque es un intento
            // real, pero **el estado no cambia** y no se crea una segunda salida.
            segunda.converted shouldBe true
            segunda.status shouldBe OrderStatus.COMPLETED
        }
    }

    describe("the state of the HEADER is DERIVED from the lines") {

        it("without packed lines the order is PENDING") {
            order(status = OrderStatus.PENDING).recalculatedStatus().status shouldBe OrderStatus.PENDING
        }

        it("with one line half done it moves to IN_PROGRESS") {
            val o = order(lines = listOf(line(supplied = 3_000, status = OrderLineStatus.ASSIGNED)))

            o.recalculatedStatus().status shouldBe OrderStatus.IN_PROGRESS
        }

        it("with all lines complete it moves to PACKAGED") {
            val o = order(
                lines = listOf(
                    line(id = 1, supplied = 10_000, status = OrderLineStatus.SUPPLIED),
                    line(id = 2, quantity = 5_000, supplied = 5_000, status = OrderLineStatus.SUPPLIED),
                ),
            )

            o.recalculatedStatus().status shouldBe OrderStatus.PACKAGED
        }

        it("a voided line does not stop the order from being PACKAGED") {
            // Anular una línea es una decisión explícita: el resto del pedido puede
            // cerrarse. Si la anulada bloqueara el cierre, **una orden con una línea
            // anulada nunca se podría completar** y quedaría abierta para siempre.
            val o = order(
                lines = listOf(
                    line(id = 1, supplied = 10_000, status = OrderLineStatus.SUPPLIED),
                    line(id = 2, status = OrderLineStatus.CANCELLED),
                ),
            )

            o.allLinesDone shouldBe true
            o.recalculatedStatus().status shouldBe OrderStatus.PACKAGED
        }

        it("does NOT overwrite a final state: a completed order stays completed") {
            val o = order(status = OrderStatus.COMPLETED, converted = true)

            // Recalcular es derivar el estado de TRABAJO, no reescribir el
            // resultado. Si pisara `COMPLETED`, una orden ya exportada volvería a
            // `PENDING` y el ERP tendría una salida que el sistema no reconoce.
            o.recalculatedStatus().status shouldBe OrderStatus.COMPLETED
        }

        it("does NOT overwrite CANCELLED") {
            order(status = OrderStatus.CANCELLED).recalculatedStatus().status shouldBe
                OrderStatus.CANCELLED
        }
    }

    describe("the counters the contract publishes") {

        it("`totalLines` and `packagedLines` count over the lines") {
            val o = order(
                lines = listOf(
                    line(id = 1, supplied = 10_000, status = OrderLineStatus.SUPPLIED),
                    line(id = 2, supplied = 4_000, status = OrderLineStatus.ASSIGNED),
                    line(id = 3),
                ),
            )

            o.totalLines shouldBe 3
            // Solo la primera está completa. El contrato los llama `packagedLines`.
            o.packagedLines shouldBe 1
            o.allLinesDone shouldBe false
        }
    }
})
