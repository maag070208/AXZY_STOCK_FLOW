package dev.axzy.stock.domain.transfer

import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE TRANSFER STATE MACHINE
 *
 * **The invariant these tests protect is in contract §7:**
 *
 * > `Σ stock_by_location + Σ stock_in_transit` is preserved on every transition.
 *
 * The proof of that invariant for real —with the database and the stock— is in the test
 * integration test. Here **which transitions are legal** is tested, which is what
 * the database cannot check: an `UPDATE ... SET status='RECEIVED'` would always
 * work, and that is precisely why the state is not written by hand.
 *
 * They are PURE tests: they touch no database or server, so they run in milliseconds and
 * **are never skipped**.
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
            // `DISPATCHED` and `IN_TRANSIT` are two distinct facts, and both
            // allow receiving. Forgetting the second would leave transfers that are travelling
            // legitimately unable to be closed.
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

            // **The concrete damage:** the destination inventory would grow without the
            // origin having gone down, so the total would RISE. It is a creation
            // of goods out of nothing, and no report would flag it as an error
            // because a rising total looks like a legitimate receipt.
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
            // If it were allowed, the goods would stay in the destination locations
            // of a cancelled transfer: counted in the wrong place and without
            // a trace. To undo it, a transfer back is made.
            val error = transfer(TransferStatus.RECEIVED).cancel().errorOrNull()

            error.shouldBeInstanceOf<DomainError.InvalidTransition>()
            (error as DomainError.InvalidTransition).action shouldBe "cancelar"
        }

        it("cancelling a CANCELLED is rejected: it is not cancelled twice") {
            transfer(TransferStatus.CANCELLED).cancel().errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("dispatching an already dispatched transfer is rejected") {
            // It is the one that prevents the double deduction: if it were dispatched twice, the
            // goods would leave the origin locations twice and the total
            // would DROP. A total that drops suddenly looks like a sale, not an error.
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

            // **Why this rule exists.** The goods would leave the
            // locations and enter transit towards the same warehouse: the total
            // does NOT change, so the reconciliation comes out balanced and the stock
            // report only shows fewer goods. It is a hole that is
            // discovered by a physical count months later.
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
            // `Quantity` stores whole thousandths, so the sum is exact: there is no
            // floating point to round. It is the same representation the
            // rest of the system uses.
            t.totalUnits shouldBe Quantity.ofScaled(1750)
        }

        it("a transfer without lines adds up to zero, it does not fail") {
            val t = transfer(TransferStatus.DRAFT, lines = emptyList())

            t.lineCount shouldBe 0
            t.totalUnits shouldBe Quantity.ZERO
        }

        it("`isInTransit` is true only when the goods are outside the locations") {
            // It is the query that feeds `GET /v1/transfers/in-transit`. If
            // it included `DRAFT`, the report would show as "on the way" goods
            // that are still on their shelf.
            transfer(TransferStatus.DRAFT).status.isInTransit shouldBe false
            transfer(TransferStatus.DISPATCHED).status.isInTransit shouldBe true
            transfer(TransferStatus.IN_TRANSIT).status.isInTransit shouldBe true
            transfer(TransferStatus.RECEIVED).status.isInTransit shouldBe false
            transfer(TransferStatus.CANCELLED).status.isInTransit shouldBe false
        }

        it("only DRAFT is editable") {
            // With the goods already on the truck, changing the lines would make what was
            // dispatched and what was declared stop matching.
            transfer(TransferStatus.DRAFT).status.isEditable shouldBe true
            transfer(TransferStatus.DISPATCHED).status.isEditable shouldBe false
            transfer(TransferStatus.IN_TRANSIT).status.isEditable shouldBe false
            transfer(TransferStatus.RECEIVED).status.isEditable shouldBe false
        }
    }
})
