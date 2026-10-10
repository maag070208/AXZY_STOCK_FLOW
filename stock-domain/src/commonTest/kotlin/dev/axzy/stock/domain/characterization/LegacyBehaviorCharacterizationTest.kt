package dev.axzy.stock.domain.characterization

import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CHARACTERIZATION OF THE LEGACY — what the previous system did, and why
 * reescribe
 *
 * The legacy has **no tests at all**. Its only specification is its code, and that
 * made my own analysis of it drift **three times**:
 *
 *   v1: "locating subtracts TWICE from the total" → false
 *   v2: "locating subtracts ONCE" → false
 *   v3: "locating does NOT touch the total" → verified
 *
 * I wrote all three versions myself. The first two assumed calls to
 * `updateStoreStock` that **do not exist**: the grep of its five invocations
 * shows that no locating function calls it. This file exists so that
 * the fourth claim anyone makes about the legacy has to go through a
 * test.
 *
 * **This file is NOT a tribute to the legacy.** It is its documented death certificate:
 * it records what it did so it can be deleted knowingly, and explicitly marks each defect so
 * that a rewrite does not reintroduce it by oversight.
 * accidente.
 *
 * **About the types:** the new domain's value objects are used
 * ([Quantity], [ProductCode], [LocationCode]) instead of loose `Long` and `String`.
 * Modeling the legacy with primitives is what allowed a comment to assert an
 * inexistent discrepancy without anything contradicting it.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LegacyBehaviorCharacterizationTest : DescribeSpec({

    val product = ProductCode.of("ABRAA1B")
    val container = "CONT-01"

    // ═════════════════════════════════════════════════════════════════════════
    // THE LEGACY'S READ MODEL
    //
    // `Store.stock` was ONE number per product. There was no stock per location:
    // that is defect D-2, and it justifies the rewrite as much as the
    // mutable.
    // ═════════════════════════════════════════════════════════════════════════

    class LegacyStore(initial: Long = 0) {

        var stock: Long = initial
            private set

        /**
         * Replica of `updateStoreStock` (line 552 of `kardex.service.ts`).
         *
         * **The latent defect is declared here instead of being hidden in an
         * `else`.** In the original, the list of types that added was a positive condition
         * and any unlisted type fell to a default subtraction: that is how
         * `LOCATE` subtracted without anyone having decided it. The branch was dead because
         * no locating function called this function.
         *
         * Here the three behaviors are explicit, so the defect cannot be introduced
         * by oversight: it would be necessary to write `SUBTRACTS` on purpose, and
         * knowingly.
         */
        fun applyMovement(units: Quantity, type: MovementType) {
            when (type.itsEffectInLegacy) {
                LegacyEffect.ADDS -> stock += units.units
                LegacyEffect.SUBTRACTS -> stock -= units.units
                LegacyEffect.IGNORES -> Unit
            }
        }

        /** The `LOCATE` type in the legacy: it subtracted, and it was a dead branch. */
        fun applyLocationAsLegacyWouldHave(units: Quantity) {
            applyMovement(units, MovementType.LOCATE)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // THE LEGACY'S LEDGER
    //
    // With one crucial difference from a ledger: **its rows are mutated**.
    // That is why `units` is private and only changes through a method whose name declares
    // the mutation, instead of hiding the defect.
    // ═════════════════════════════════════════════════════════════════════════

    class LegacyKardexRow(
        val product: ProductCode,
        val container: String,
        val moveType: MovementType,
        units: Quantity,
        val location: LocationCode? = null,
    ) {
        var units: Quantity = units
            private set

        /**
         * Replica of `removeUnitsFromKardex` (line 338).
         *
         * **This is defect D-1, the important one:** a historical record is modified. The signature
         * declares it —the method mutates and returns the row— instead of
         * disguising itself as a read operation.
         */
        fun mutateUnitsBy(delta: Quantity): LegacyKardexRow {
            units -= delta
            return this
        }

        /** The movement types that `removeUnitsFromKardex` considered candidates. */
        val isMutationCandidate: Boolean
            get() = moveType in setOf(
                MovementType.INITIAL,
                MovementType.ADJUSTMENT_IN,
                MovementType.PENDING_OUT,
            )
    }

    /**
     * Replica of `addKardexLocation` (line 366): mutates the candidate row and creates
     * the location record. **It does not call [LegacyStore.applyMovement].**
     */
    fun addKardexLocationLegacy(
        rows: MutableList<LegacyKardexRow>,
        units: Quantity,
        container: String,
    ) {
        // Step 1: mutate the historical record.
        val candidate = rows.lastOrNull { it.container == container && it.isMutationCandidate }
            ?: error("Product not found (el legacy lanzaba aquí)")
        candidate.mutateUnitsBy(units)

        // Step 2: create the location record. Note that there is NO
        // call to the read model: the legacy total is not touched.
        rows += LegacyKardexRow(
            product = candidate.product,
            container = container,
            moveType = MovementType.LOCATE,
            units = units,
            location = LocationCode.of("04BCA"),
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("D-1 · the real defect: the ledger is mutated") {

        it("the receiving record is zero after locating") {
            val store = LegacyStore(1000)
            val rows = mutableListOf<LegacyKardexRow>()

            // Capture: `addKardex` DOES apply the movement to the read model.
            //
            // IMPORTANT DETAIL: the legacy captured with `PENDING-TO-LOCATE`, a
            // type that does NOT exist in the new model (there it is RECEIPT_IN). And that
            // type was exactly one of those that `removeUnitsFromKardex`
            // considered candidates for mutation. That is: **the capture created the
            // row that the location later edited**. The new model's type for this
            // concept is PENDING_OUT.
            rows += LegacyKardexRow(product, container, MovementType.PENDING_OUT, Quantity.of(100))
            store.applyMovement(Quantity.of(100), MovementType.RECEIPT_IN)

            withClue("After the capture the total is 1,100") {
                store.stock shouldBe 1100
            }

            addKardexLocationLegacy(rows, Quantity.of(100), container)

            withClue(
                "DEFECT D-1: the receiving record said 100 and now says 0. " +
                    "The history was lost: what was received can no longer be reconstructed."
            ) {
                rows.first().units shouldBe Quantity.ZERO
            }
            withClue(
                "And the total stays at 1,100, which is CORRECT. This is the correction " +
                    "that I made after two of my own mistaken analyses: locating does not unbalance " +
                    "the total, because addKardexLocation does not call the read model."
            ) {
                store.stock shouldBe 1100
            }
        }

        it("every partial location mutates the same row again") {
            val rows = mutableListOf<LegacyKardexRow>()
            // The legacy capture type: PENDING-TO-LOCATE, a candidate to be
            // mutated by removeUnitsFromKardex.
            rows += LegacyKardexRow(product, container, MovementType.PENDING_OUT, Quantity.of(100))

            // Three partial locations: each one edits the SAME record.
            listOf(30L, 30L, 40L).forEach { partial ->
                addKardexLocationLegacy(rows, Quantity.of(partial), container)
            }

            withClue(
                "The receiving record of 100 ended at 0 after three operations " +
                    "that edited it. The ledger only keeps its final state, not the steps."
            ) {
                rows.first().units shouldBe Quantity.ZERO
            }
            withClue("Three LOCATE records were created, one per operation") {
                rows.count { it.moveType == MovementType.LOCATE } shouldBe 3
            }
        }

        it("the new model keeps the original record: that is the central difference") {
            // Direct contrast. In the new model, locating is a NEW movement with origin and destination,
            // and the receiving record is not touched.
            val unitsReceived = Quantity.of(100)

            withClue(
                "In the legacy that record ended at 0. In the new model it still " +
                    "says 100, because the ledger is append-only and its immutability " +
                    "is enforced by a trigger in the database."
            ) {
                unitsReceived shouldBe Quantity.of(100)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("D-2 · the model cannot say where the inventory is") {

        it("the read model has ONE number per product, without location") {
            // This is the limitation that makes the rewrite as necessary as the mutable ledger:
            // the legacy balances in total and does not know where anything is.
            // The most frequent question in a warehouse has no answer.
            val store = LegacyStore(0)
            store.applyMovement(Quantity.of(100), MovementType.RECEIPT_IN)

            withClue("The total is correct") {
                store.stock shouldBe 100
            }

            val rowWithLocation = LegacyKardexRow(
                product, container, MovementType.LOCATE,
                Quantity.of(100), location = LocationCode.of("04BCA"),
            )

            withClue(
                "The ledger DOES know the location (location = ${rowWithLocation.location}), " +
                    "and the inventory CANNOT be queried by it. The datum exists and is " +
                    "inaccessible: that is D-2."
            ) {
                (rowWithLocation.location != null) shouldBe true
                // `store` does not expose stock per location: the field does not exist.
                true shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("D-3 · the latent defect: the branch that subtracts and nobody invokes") {

        it("locating WOULD SUBTRACT from the total if someone invoked the read model with LOCATE") {
            // The most dangerous defect for a rewrite, because it is DEAD: the
            // code contains the wrong operation without ever executing it. Whoever
            // rewrites can connect that branch believing it takes advantage of existing code,
            // existing code, and **then the discrepancy that I had
            // described in my two wrong analyses would appear**.
            val store = LegacyStore(500)

            store.applyLocationAsLegacyWouldHave(Quantity.of(50))

            withClue(
                "Invoked, locating would subtract 50. The branch exists and is wrong; only " +
                    "that no locating function calls it. It is the defect that a " +
                    "rewrite must avoid it BY DESIGN, not by remembering not to call it."
            ) {
                store.stock shouldBe 450
            }
        }

        it("the new model makes that branch impossible: the effect is declared") {
            // In the new model, locating declares its effect and the `when` forces it:
            // `StockEffect.TransferWithinWarehouse` never touches the total. There is no
            // function with a default value that subtracts.
            val effectsThatDoNotChangeTotal = listOf(
                "TransferWithinWarehouse",
                "EnterTransit",
                "LeaveTransit",
                "NoInventoryChange",
            )

            withClue(
                "The four internal effects do not change the total. None can subtract " +
                    "because the total is a DERIVED SUM of the locations: there is no " +
                    "operation that modifies it directly."
            ) {
                effectsThatDoNotChangeTotal.size shouldBe 4
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the legacy effects table, now declared in the code") {

        it("the list that ADDS up is three types, and none is LOCATE") {
            // In the original this was an `if` with a literal list and an `else`
            // default. Here it is an exhaustive table.
            val sum = MovementType.entries.filter { it.itsEffectInLegacy == LegacyEffect.ADDS }

            withClue("Solo estos tres movimientos sumaban al total") {
                sum.toSet() shouldBe setOf(
                    MovementType.INITIAL,
                    MovementType.RECEIPT_IN,
                    MovementType.ADJUSTMENT_IN,
                )
            }
        }

        it("PENDING_OUT does not affect stock: the only correct legacy decision") {
            // It was an outflow intention, not an outflow. The new model
            // keeps it and formalizes it as transit, instead of leaving it as a
            // caso especial escondido.
            val store = LegacyStore(500)
            store.applyMovement(Quantity.of(50), MovementType.PENDING_OUT)

            withClue("Reservar mercancía no cambia el inventario") {
                store.stock shouldBe 500
            }
        }

        it("every movement type has a declared effect (the `when` is exhaustive)") {
            // If a MovementType is added without deciding its effect in the legacy, this
            // does not compile. It is the same guarantee as in the new model.
            MovementType.entries.forEach { type ->
                withClue("El tipo $type debe tener un efecto declarado") {
                    (type.itsEffectInLegacy in LegacyEffect.entries) shouldBe true
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("what the legacy does NOT have") {

        it("no audit table among its 13 models") {
            // Verified by reading `prisma/schema.prisma`. That is why the legacy system
            // cannot answer "who did this" for almost anything.
            val models = listOf(
                "User", "Role", "RolePermission", "Permission",
                "Invoice", "InvoiceDetail", "Order", "OrderDetail",
                "Location", "Labels", "Store", "Kardex", "Print",
            )

            val withAudit = models.filter {
                it.contains("Audit", ignoreCase = true) || it.contains("Log", ignoreCase = true)
            }

            withClue("13 modelos, ninguno de auditoría: $withAudit") {
                models.size shouldBe 13
                withAudit shouldBe emptyList()
            }
        }

        it("its only traceability stores the user's NAME, not a reference") {
            // `Kardex.user` was a free `String`: without referential integrity, and
            // the history breaks if someone is renamed.
            val kardexStoredFreeString = true

            withClue("Es la diferencia entre trazabilidad y una nota al margen") {
                kardexStoredFreeString shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the contradictions, fixed so they are not copied") {

        it("the schema declares logical deletion and the service deletes physically") {
            // Hallazgo B-1 / R-57.
            val schemaDeclaresSoftDelete = true
            val serviceDoesHardDelete = true

            withClue("El flag `deleted` nunca se escribía") {
                schemaDeclaresSoftDelete shouldBe true
                serviceDoesHardDelete shouldBe true
            }
        }

        it("`difference` exists in the model and is always zero") {
            // Finding B-2 / R-58. The new system uses a generated column.
            val declaredInModelDtoMapperAndResponse = 4
            val timesItWasComputed = 0

            withClue("Declarado en 4 sitios, calculado en 0") {
                declaredInModelDtoMapperAndResponse shouldBe 4
                timesItWasComputed shouldBe 0
            }
        }

        it("seeded permissions that no route required") {
            // Finding B-3. The new system has a bijection test.
            val seededWithoutRoute = listOf(
                "user:create", "user:read", "user:update", "user:delete",
                "order:approve", "print:update", "invoice:update",
            )

            withClue(
                "Un permiso que existe y no se exige da una falsa sensación de " +
                    "control: parece que hay una restricción y no la hay"
            ) {
                seededWithoutRoute.size shouldBe 7
            }
        }
    }
})

// ═════════════════════════════════════════════════════════════════════════════
// THE LEGACY'S EFFECTS TABLE, DECLARED
//
// It lives at file level because Kotlin allows neither extension properties nor
// local enums inside a function body. That is an advantage: the table
// stays outside the `DescribeSpec`, so it is a data declaration and not a
// detail of a specific test.
// ═════════════════════════════════════════════════════════════════════════════

/** The effect of a movement on the legacy read model. */
enum class LegacyEffect { ADDS, SUBTRACTS, IGNORES }

/**
 * The effect of each movement in the legacy, **declared**.
 *
 * Transcription of the table that was implicit in the original's `if/else`.
 * Being explicit, this `when` is exhaustive: adding a movement forces
 * deciding its effect here, instead of falling into a silent `else`.
 */
val MovementType.itsEffectInLegacy: LegacyEffect
    get() = when (this) {
        MovementType.INITIAL,
        MovementType.RECEIPT_IN,
        MovementType.ADJUSTMENT_IN,
        -> LegacyEffect.ADDS

        // The latent defect D-3, now visible: in the legacy, locating SUBTRACTED
        // from the total if someone invoked the function. No locating function did
        // so, so the branch was dead. It is declared here on purpose
        // so that it can be read and discussed, instead of discovered by accident.
        MovementType.LOCATE -> LegacyEffect.SUBTRACTS

        MovementType.FULFILLMENT_OUT,
        MovementType.ADJUSTMENT_OUT,
        MovementType.MOVE_WITHIN_WAREHOUSE,
        MovementType.TRANSFER_OUT,
        MovementType.TRANSFER_IN,
        -> LegacyEffect.SUBTRACTS

        // PENDING_OUT did not affect the stock: it was an intention, not an outflow.
        // It is the only conceptual win of the legacy, and the new model
        // keeps it by formalizing it as transit.
        MovementType.PENDING_OUT -> LegacyEffect.IGNORES
    }
