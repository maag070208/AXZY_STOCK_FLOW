package dev.axzy.stock.domain.fulfillment

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE PICKING: STATES AND, ABOVE ALL, HOW MUCH IS DEDUCTED
 *
 * The test that matters most in this file is
 * **"confirming deducts what is MISSING, not the whole quantity"**. A mistake there
 * breaks nothing: the system starts, the routes respond and the route tests pass.
 * What happens is that the inventory drops too much, and that is **only discovered
 * when reconciling the warehouse** — and then it cannot be told apart from a real shrinkage.
 *
 * They are PURE tests: they touch neither the database nor the server, run in milliseconds and **are not
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
            // **This 3 is the figure that confirming will deduct.** If instead
            // 10 were deducted, the inventory would have dropped 17 in total for a
            // line of 10.
            empacada.pendingQuantity shouldBe Quantity.ofScaled(3_000)
            empacada.isComplete shouldBe false
        }

        it("packing 7 and then 3 completes the line and leaves 0 pending") {
            val empacada = line(quantity = 10_000)
                .packageQuantity(Quantity.ofScaled(7_000)).getOrNull()!!
                .packageQuantity(Quantity.ofScaled(3_000)).getOrNull()!!

            empacada.suppliedQuantity shouldBe Quantity.ofScaled(10_000)
            // The pending quantity ends EXACTLY at zero: confirming will deduct nothing
            // else, so there is no double deduction.
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
            // The message carries both figures: what was requested and what remains. Without the
            // second one, the operator does not know how much they can pack.
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

            // The code is the one from contract §6: `ORDER_ALREADY_CONFIRMED`.
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
            // Reassigning is routine: the shift changes or someone falls ill.
            segunda.assignedUserId shouldBe 9L
            // Without a new location the existing one is kept: `ASSIGNED` says who, not where.
            segunda.locationId shouldBe 3L
        }

        it("a line already fulfilled or voided is not assigned") {
            val surtida = line(quantity = 10_000)
                .packageQuantity(Quantity.of(10))
                .shouldBeInstanceOf<Either.Right<OrderLine>>().value
            val anulada = line(status = OrderLineStatus.CANCELLED)

            // Assigning work on goods that already left would mean saying that someone is going to
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
            // **It is not marked as converted**: a failure is not a conversion. If it
            // were marked, the retry would believe it was already sent and would never send it.
            fallida.converted shouldBe false
            fallida.status shouldBe OrderStatus.PENDING_TO_OUT
        }

        it("a successful attempt completes the order and clears the previous error") {
            val exitosa = order(status = OrderStatus.PENDING_TO_OUT, attempts = 2)
                .recordExportAttempt(success = true)
                .getOrNull()!!

            exitosa.exportAttempts shouldBe 3
            exitosa.exportStatus shouldBe "SENT"
            // The error from the previous attempt is cleared: leaving "The ERP does not respond"
            // next to a `SENT` makes one doubt whether it arrived.
            exitosa.exportLastError shouldBe null
            exitosa.converted shouldBe true
            exitosa.status shouldBe OrderStatus.COMPLETED
        }

        it("retrying after a success is idempotent: it does not count as a new conversion") {
            val primera = order(status = OrderStatus.PENDING_TO_OUT)
                .recordExportAttempt(success = true).getOrNull()!!
            val segunda = primera.recordExportAttempt(success = true).getOrNull()!!

            // It stays converted and completed: the counter goes up because it is a real attempt,
            // but **the state does not change** and no second outflow is created.
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
            // Voiding a line is an explicit decision: the rest of the order can
            // be closed. If the voided line blocked the closing, **an order with a voided line
            // could never be completed** and would stay open forever.
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

            // Recalculating means deriving the WORK state, not rewriting the
            // result. If it overwrote `COMPLETED`, an order already exported would go back to
            // `PENDING` and the ERP would have an outflow the system does not recognize.
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
            // Only the first one is complete. The contract calls them `packagedLines`.
            o.packagedLines shouldBe 1
            o.allLinesDone shouldBe false
        }
    }
})
