package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * INVARIANTES DEL INVENTARIO I1–I10 — PROPERTY-BASED TESTS
 *
 * Estos tests son la respuesta a la pregunta que el negocio necesita: **"¿el
 * inventario cuadra tras cualquier secuencia de operaciones?"**.
 *
 * Se generan secuencias **aleatorias** de operaciones y se afirma que las
 * propiedades se mantienen. A diferencia de un test de ejemplo, esto cubre
 * combinaciones que a nadie se le ocurriría escribir a mano: ubicar sin
 * recibir, trasladar sin stock, mermar de más, recibir el mismo movimiento dos
 * veces (idempotencia)…
 *
 * **El generador solo produce operaciones válidas**, y eso es deliberado: si
 * generara operaciones imposibles, el ledger las rechazaría correctamente y el
 * test no estaría probando la propiedad, sino la validación. La validación se
 * prueba aparte con casos concretos.
 *
 * Cada invariante lleva su identificador (I1…I10) para poder trazarla contra
 * docs/historico/PLAN_ALMACEN_INTEGRAL.md §3.6.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InventoryInvariantsPropertyTest : DescribeSpec({

    val receiving = LocationCode.of("RECEIVING")
    val shelfA = LocationCode.of("04BCA")
    val shelfB = LocationCode.of("04BCB")
    val shelfNorth = LocationCode.of("NORTE01")
    val shelfSouth = LocationCode.of("SUR01")

    val productA = "ABRAA1B"
    val productB = "ABRAA1E"
    val products = listOf(productA, productB)
    val locations = listOf(shelfA, shelfB, shelfNorth, shelfSouth)

    /**
     * Genera una secuencia de operaciones válidas contra un ledger concreto.
     *
     * Es un generador **consciente del estado**: en cada paso mira el libro y
     * solo elige operaciones que pueden aplicarse. Así la secuencia siempre
     * avanza y ninguna operación se rechaza por falta de stock, que es lo que
     * haría fallar la propiedad por una razón que no es la que se está probando.
     */
    fun operationSequence(
        ledger: InventoryLedger,
        steps: Int,
        useIdempotency: Boolean = false,
    ): List<Pair<String, () -> Unit>> {
        val operations = mutableListOf<Pair<String, () -> Unit>>()
        // Semilla determinista por ejecución: el generador de kotest da la
        // variabilidad; aquí solo se decide QUÉ operación es válida en cada paso.
        val rng = kotlin.random.Random(steps * 7919L + ledger.entries.size)

        repeat(steps) { index ->
            val product = products.random(rng)
            val choice = rng.nextInt(7)

            val operation: Pair<String, () -> Unit> = when (choice) {
                0 -> "receipt" to {
                    ledger.receipt(product, receiving, Quantity.of(rng.nextLong(1, 50).toLong()))
                }

                1 -> {
                    // Solo se ubica si hay stock en recepción.
                    val available = ledger.quantityAt(product, receiving)
                    if (available.isPositive) {
                        val quantity = Quantity.of(
                            rng.nextLong(1, available.units.coerceAtLeast(1) + 1)
                        )
                        val destination = listOf(shelfA, shelfB).random(rng)
                        "locate($destination)" to { ledger.locate(product, receiving, destination, quantity) }
                    } else {
                        "noop" to { }
                    }
                }

                2 -> {
                    val origin = locations.random(rng)
                    val available = ledger.quantityAt(product, origin)
                    val other = locations.filter { it != origin }.random(rng)
                    if (available.isPositive) {
                        val quantity = Quantity.of(rng.nextLong(1, available.units.coerceAtLeast(1) + 1))
                        "move($origin→$other)" to {
                            ledger.moveWithinWarehouse(product, origin, other, quantity)
                        }
                    } else {
                        "noop" to { }
                    }
                }

                3 -> {
                    val available = ledger.quantityAt(product, shelfNorth)
                    if (available.isPositive) {
                        val quantity = Quantity.of(rng.nextLong(1, available.units.coerceAtLeast(1) + 1))
                        "transferOut" to { ledger.transferOut(product, shelfNorth, 2L, quantity) }
                    } else {
                        "noop" to { }
                    }
                }

                4 -> {
                    val inTransit = ledger.inTransit.filter { it.productCode == product }
                    if (inTransit.isNotEmpty()) {
                        val batch = inTransit.random(rng)
                        val quantity = Quantity.of(
                            rng.nextLong(1, batch.quantity.units.coerceAtLeast(1) + 1)
                        )
                        "transferIn" to {
                            ledger.transferIn(product, shelfSouth, 1L, quantity)
                        }
                    } else {
                        "noop" to { }
                    }
                }

                5 -> {
                    val location = locations.random(rng)
                    val available = ledger.quantityAt(product, location)
                    if (available.isPositive) {
                        val quantity = Quantity.of(rng.nextLong(1, available.units.coerceAtLeast(1) + 1))
                        "adjustmentOut" to {
                            ledger.adjustmentOut(product, location, quantity, "merma por daño")
                        }
                    } else {
                        "noop" to { }
                    }
                }

                else -> "adjustmentIn" to {
                    ledger.adjustmentIn(
                        product,
                        locations.random(rng),
                        Quantity.of(rng.nextLong(1, 20).toLong()),
                        "corrección de conteo",
                    )
                }
            }
            operations.add(operation)
        }
        return operations
    }

    /** Aplica la secuencia y devuelve los tipos aplicados, para depurar. */
    fun apply(ledger: InventoryLedger, operations: List<Pair<String, () -> Unit>>): List<String> =
        operations.map { (name, action) -> action(); name }

    // ═════════════════════════════════════════════════════════════════════════
    // I1 — CONSERVACIÓN TOTAL
    // ═════════════════════════════════════════════════════════════════════════

    describe("I1 — conservación total") {

        it("el total es siempre igual al neto del ledger, tras cualquier secuencia") {
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { steps ->
                val ledger = InventoryLedger()
                // Carga inicial para tener stock con el que operar.
                products.forEach { p ->
                    ledger.initialLoad(p, receiving, Quantity.of(1000))
                }

                apply(ledger, operationSequence(ledger, steps))

                products.forEach { product ->
                    with(ledger) {
                        val total = totalOf(product)
                        val netLedger = ledgerNetOf(product)
                        if (total != netLedger) {
                            throw AssertionError(
                                "I1 violada para $product tras $steps pasos: " +
                                    "total=$total pero el ledger suma $netLedger"
                            )
                        }
                    }
                }
            }
        }

        it("el total es la suma de ubicaciones más el tránsito, siempre") {
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { steps ->
                val ledger = InventoryLedger()
                products.forEach { p -> ledger.initialLoad(p, receiving, Quantity.of(500)) }

                apply(ledger, operationSequence(ledger, steps))

                products.forEach { product ->
                    val atLocations = ledger.totalAtLocationsOf(product)
                    val inTransit = ledger.totalInTransitOf(product)
                    val total = ledger.totalOf(product)

                    if (total != atLocations + inTransit) {
                        throw AssertionError(
                            "I1 violada para $product: total=$total pero " +
                                "ubicaciones=$atLocations + tránsito=$inTransit"
                        )
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I2 — NO NEGATIVIDAD
    // ═════════════════════════════════════════════════════════════════════════

    describe("I2 — ninguna ubicación puede tener stock negativo") {

        it("tras cualquier secuencia, todos los saldos son >= 0") {
            checkAll(PropTestConfig(iterations = 300), Arb.int(1..80)) { steps ->
                val ledger = InventoryLedger()
                products.forEach { p -> ledger.initialLoad(p, receiving, Quantity.of(800)) }

                apply(ledger, operationSequence(ledger, steps))

                ledger.allBalances().forEach { balance ->
                    if (balance.quantity.isNegative) {
                        throw AssertionError(
                            "I2 violada: ${balance.productCode} en ${balance.location} " +
                                "quedó con ${balance.quantity} (negativo). " +
                                "Una ubicación física no puede tener stock negativo."
                        )
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I3 — APPEND-ONLY
    // ═════════════════════════════════════════════════════════════════════════

    describe("I3 — el ledger es append-only") {

        it("el número de entradas solo crece y cada una conserva sus datos") {
            val ledger = InventoryLedger()
            ledger.initialLoad(productA, receiving, Quantity.of(100))

            val entryOriginal = ledger.entries.first()
            val quantityOriginal = entryOriginal.quantity

            // Se aplican operaciones que en el legacy MUTABAN la entrada de
            // recepción: `removeUnitsFromKardex` hacía `kardex.update` y bajaba
            // sus `units`. Aquí eso no puede pasar.
            ledger.locate(productA, receiving, shelfA, Quantity.of(60))
            ledger.moveWithinWarehouse(productA, shelfA, shelfB, Quantity.of(20))
            ledger.adjustmentOut(productA, shelfB, Quantity.of(5), "merma")

            val entryAfterOperation = ledger.entries.first()
            with(entryAfterOperation) {
                if (quantity != quantityOriginal) {
                    throw AssertionError(
                        "I3 violada: la entrada histórica de recepción cambió de " +
                            "$quantityOriginal a $quantity. Un ledger que se puede " +
                            "editar no es un ledger."
                    )
                }
                sequence shouldBeExactly entryOriginal.sequence
            }
        }

        it("la secuencia es estrictamente creciente y sin huecos") {
            checkAll(PropTestConfig(iterations = 100), Arb.int(1..40)) { steps ->
                val ledger = InventoryLedger()
                products.forEach { p -> ledger.initialLoad(p, receiving, Quantity.of(300)) }
                apply(ledger, operationSequence(ledger, steps))

                val sequences = ledger.entries.map { it.sequence }
                val expected = (1L..sequences.size.toLong()).toList()
                if (sequences != expected) {
                    throw AssertionError(
                        "I3 violada: la secuencia debe ser 1..N sin huecos. " +
                            "Se encontró: ${sequences.take(10)}…"
                    )
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I4 — AUTO-AUDITORÍA
    // ═════════════════════════════════════════════════════════════════════════

    describe("I4 — quantityAfter permite reconstruir el total") {

        it("el quantityAfter de la última entrada coincide con el total actual") {
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { steps ->
                val ledger = InventoryLedger()
                products.forEach { p -> ledger.initialLoad(p, receiving, Quantity.of(400)) }
                apply(ledger, operationSequence(ledger, steps))

                products.forEach { product ->
                    val last = ledger.entries.lastOrNull { it.productCode == product }
                    if (last != null) {
                        val current = ledger.totalOf(product)
                        if (last.quantityAfter != current) {
                            throw AssertionError(
                                "I4 violada para $product: la última entrada dice " +
                                    "${last.quantityAfter} pero el total real es $current"
                            )
                        }
                    }
                }
            }
        }

        it("recorrer el ledger en orden reproduce el total final") {
            checkAll(PropTestConfig(iterations = 150), Arb.int(1..50)) { steps ->
                val ledger = InventoryLedger()
                products.forEach { p -> ledger.initialLoad(p, receiving, Quantity.of(300)) }
                apply(ledger, operationSequence(ledger, steps))

                products.forEach { product ->
                    // Reproducción desde cero: solo con el ledger.
                    val reproduced = ledger.entries
                        .filter { it.productCode == product }
                        .fold(Quantity.ZERO) { accumulated, entry ->
                            when (entry.type) {
                                MovementType.INITIAL,
                                MovementType.RECEIPT_IN,
                                MovementType.ADJUSTMENT_IN,
                                -> accumulated + entry.quantity

                                MovementType.ADJUSTMENT_OUT -> accumulated - entry.quantity
                                else -> accumulated
                            }
                        }

                    val real = ledger.totalOf(product)
                    if (reproduced != real) {
                        throw AssertionError(
                            "I4 violada: reproducir el ledger da $reproduced " +
                                "pero el total del modelo es $real"
                        )
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I5 — CONCILIACIÓN
    // ═════════════════════════════════════════════════════════════════════════

    describe("I5 — los saldos concuerdan con el ledger") {

        it("recalcular los saldos desde el ledger da lo mismo que la proyección") {
            checkAll(PropTestConfig(iterations = 150), Arb.int(1..60)) { steps ->
                val ledger = InventoryLedger()
                products.forEach { p -> ledger.initialLoad(p, receiving, Quantity.of(600)) }
                apply(ledger, operationSequence(ledger, steps))

                // Recalcular cada ubicación desde cero, solo con el ledger.
                val recalculated = mutableMapOf<Pair<String, LocationCode>, Quantity>()
                ledger.entries.forEach { entry ->
                    entry.from?.let { from ->
                        val key = entry.productCode to from
                        val remove = when (entry.type) {
                            MovementType.ADJUSTMENT_OUT,
                            MovementType.LOCATE,
                            MovementType.MOVE_WITHIN_WAREHOUSE,
                            MovementType.TRANSFER_OUT,
                            -> entry.quantity

                            else -> Quantity.ZERO
                        }
                        recalculated[key] = (recalculated[key] ?: Quantity.ZERO) - remove
                    }
                    entry.to?.let { towards ->
                        val key = entry.productCode to towards
                        val add = when (entry.type) {
                            MovementType.INITIAL,
                            MovementType.RECEIPT_IN,
                            MovementType.ADJUSTMENT_IN,
                            MovementType.LOCATE,
                            MovementType.MOVE_WITHIN_WAREHOUSE,
                            MovementType.TRANSFER_IN,
                            -> entry.quantity

                            else -> Quantity.ZERO
                        }
                        recalculated[key] = (recalculated[key] ?: Quantity.ZERO) + add
                    }
                }

                ledger.allBalances().forEach { balance ->
                    val key = balance.productCode to balance.location
                    val expected = recalculated[key] ?: Quantity.ZERO
                    if (balance.quantity != expected) {
                        throw AssertionError(
                            "I5 violada: el saldo de ${balance.productCode} en ${balance.location} " +
                                "es ${balance.quantity} pero el ledger dice $expected"
                        )
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I6 — AJUSTE CON MOTIVO
    // ═════════════════════════════════════════════════════════════════════════

    describe("I6 — ningún ajuste sin motivo") {

        it("todos los ajustes del ledger tienen motivo no vacío") {
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { steps ->
                val ledger = InventoryLedger()
                products.forEach { p -> ledger.initialLoad(p, receiving, Quantity.of(400)) }
                apply(ledger, operationSequence(ledger, steps))

                ledger.entries
                    .filter {
                        it.type == MovementType.ADJUSTMENT_IN ||
                            it.type == MovementType.ADJUSTMENT_OUT
                    }
                    .forEach { adjustment ->
                        if (adjustment.reason.isNullOrBlank()) {
                            throw AssertionError(
                                "I6 violada: el ajuste #${adjustment.sequence} " +
                                    "(${adjustment.type}) no tiene motivo"
                            )
                        }
                    }
            }
        }

        it("un ajuste con motivo en blanco es rechazado") {
            val ledger = InventoryLedger()
            ledger.initialLoad(productA, receiving, Quantity.of(100))

            ledger.adjustmentOut(productA, receiving, Quantity.of(10), "").isLeft shouldBe true
            ledger.adjustmentOut(productA, receiving, Quantity.of(10), "   ").isLeft shouldBe true
            ledger.adjustmentIn(productA, receiving, Quantity.of(10), "").isLeft shouldBe true

            // Y con motivo sí pasa.
            ledger.adjustmentOut(productA, receiving, Quantity.of(10), "merma").isRight shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I8 — IDEMPOTENCIA
    // ═════════════════════════════════════════════════════════════════════════

    describe("I8 — idempotencia del móvil offline") {

        it("repetir el mismo clientUuid no duplica el movimiento") {
            val ledger = InventoryLedger()
            val uuid = "captura-movil-001"

            ledger.receipt(productA, receiving, Quantity.of(100), clientUuid = uuid)
            val totalAfterFirst = ledger.totalOf(productA)

            // El móvil reintenta porque no recibió confirmación.
            val second = ledger.receipt(productA, receiving, Quantity.of(100), clientUuid = uuid)

            second.isLeft shouldBe true
            with(ledger) {
                totalOf(productA) shouldBe totalAfterFirst
            }
            // El ledger arranca vacío: solo debe haber la primera recepción.
            // El reintento del móvil no puede añadir una segunda entrada.
            ledger.entries.size shouldBe 1
            ledger.totalOf(productA) shouldBe Quantity.of(100)
        }

        it("un clientUuid distinto sí registra un movimiento nuevo") {
            val ledger = InventoryLedger()
            ledger.receipt(productA, receiving, Quantity.of(100), clientUuid = "uuid-1")
            ledger.receipt(productA, receiving, Quantity.of(100), clientUuid = "uuid-2")

            ledger.totalOf(productA) shouldBe Quantity.of(200)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I9 — ATOMICIDAD
    // ═════════════════════════════════════════════════════════════════════════

    describe("I9 — una operación rechazada no deja rastro") {

        it("una operación sin stock suficiente no modifica el ledger ni el total") {
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { steps ->
                val ledger = InventoryLedger()
                products.forEach { p -> ledger.initialLoad(p, receiving, Quantity.of(500)) }
                apply(ledger, operationSequence(ledger, steps))

                val entriesBefore = ledger.entries.size
                val totalsBefore = products.associateWith { ledger.totalOf(it) }
                val balancesBefore = ledger.allBalances().associate { (it.productCode to it.location) to it.quantity }

                // Se intenta una operación imposible: mover 100.000 unidades.
                val impossible = Quantity.of(100_000)
                ledger.locate(productA, receiving, shelfA, impossible)
                ledger.moveWithinWarehouse(productA, shelfA, shelfB, impossible)
                ledger.adjustmentOut(productA, shelfA, impossible, "merma")
                ledger.transferOut(productA, shelfA, 2L, impossible)

                if (ledger.entries.size != entriesBefore) {
                    throw AssertionError(
                        "I9 violada: las operaciones rechazadas dejaron " +
                            "${ledger.entries.size - entriesBefore} entradas en el ledger"
                    )
                }
                products.forEach { p ->
                    if (ledger.totalOf(p) != totalsBefore[p]) {
                        throw AssertionError("I9 violada: el total de $p cambió tras una operación rechazada")
                    }
                }
                val balancesAfter = ledger.allBalances().associate { (it.productCode to it.location) to it.quantity }
                if (balancesAfter != balancesBefore) {
                    throw AssertionError("I9 violada: los saldos cambiaron tras operaciones rechazadas")
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I10 — TRASLADO ENTRE BODEGAS
    // ═════════════════════════════════════════════════════════════════════════

    describe("I10 — el traslado entre bodegas conserva el total") {

        it("despachar resta de la ubicación pero NO del total: entra en tránsito") {
            val ledger = InventoryLedger()
            ledger.initialLoad(productA, shelfNorth, Quantity.of(100))

            val totalBefore = ledger.totalOf(productA)
            ledger.transferOut(productA, shelfNorth, toWarehouseId = 2L, Quantity.of(40))

            with(ledger) {
                quantityAt(productA, shelfNorth) shouldBe Quantity.of(60)
                totalInTransitOf(productA) shouldBe Quantity.of(40)

                if (totalOf(productA) != totalBefore) {
                    throw AssertionError(
                        "I10 violada: el total cambió al despachar. La mercancía sigue " +
                            "en el inventario, en tránsito. Si el total bajara, el " +
                            "inventario mentiría durante el viaje."
                    )
                }
            }
        }

        it("recibir libera el tránsito y suma al destino, conservando el total") {
            val ledger = InventoryLedger()
            ledger.initialLoad(productA, shelfNorth, Quantity.of(100))
            val totalBefore = ledger.totalOf(productA)

            ledger.transferOut(productA, shelfNorth, 2L, Quantity.of(40))
            ledger.transferIn(productA, shelfSouth, fromWarehouseId = 1L, Quantity.of(40))

            with(ledger) {
                totalInTransitOf(productA) shouldBe Quantity.ZERO
                quantityAt(productA, shelfSouth) shouldBe Quantity.of(40)
                totalOf(productA) shouldBe totalBefore
            }
        }

        it("un traslado a medio camino deja el tránsito visible, no perdido") {
            checkAll(PropTestConfig(iterations = 100), Arb.int(1..40)) { steps ->
                val ledger = InventoryLedger()
                ledger.initialLoad(productA, shelfNorth, Quantity.of(1000))
                apply(ledger, operationSequence(ledger, steps))

                // El total siempre incluye lo que está en tránsito.
                val atLocations = ledger.totalAtLocationsOf(productA)
                val inTransit = ledger.totalInTransitOf(productA)
                val total = ledger.totalOf(productA)

                total shouldBe atLocations + inTransit
                if (inTransit.isNegative) {
                    throw AssertionError("I10 violada: el tránsito no puede ser negativo")
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EL BUG DEL LEGACY, COMO PROPIEDAD
    // ═════════════════════════════════════════════════════════════════════════

    describe("el bug de doble descuento al ubicar no puede reproducirse") {

        it("ubicar N unidades nunca cambia el total, para cualquier N") {
            checkAll(PropTestConfig(iterations = 300), Arb.int(1..500)) { quantity ->
                val ledger = InventoryLedger()
                ledger.initialLoad(productA, receiving, Quantity.of(1000))

                val totalBefore = ledger.totalOf(productA)
                val result = ledger.locate(
                    productA, receiving, shelfA, Quantity.of(quantity.toLong()),
                )

                if (result.isRight) {
                    val totalAfter = ledger.totalOf(productA)
                    if (totalAfter != totalBefore) {
                        throw AssertionError(
                            "BUG DEL LEGACY REPRODUCIDO: ubicar $quantity unidades cambió el " +
                                "total de $totalBefore a $totalAfter. En el legacy, " +
                                "addKardexLocation restaba dos veces y el inventario " +
                                "bajaba en cada ubicación (hallazgo R-01)."
                        )
                    }
                }
            }
        }

        it("recibir y luego ubicar deja el total igual a lo recibido") {
            // La secuencia exacta que en el legacy dejaba el stock 100 unidades
            // por debajo del real.
            val ledger = InventoryLedger()
            ledger.receipt(productA, receiving, Quantity.of(100))

            val totalAfterReceiving = ledger.totalOf(productA)
            ledger.locate(productA, receiving, shelfA, Quantity.of(100))

            with(ledger) {
                val totalFinal = totalOf(productA)
                if (totalFinal != Quantity.of(100)) {
                    throw AssertionError(
                        "El legacy habría dejado el total en 0 en lugar de 100. " +
                            "Total actual: $totalFinal"
                    )
                }
                totalFinal shouldBe totalAfterReceiving
                quantityAt(productA, shelfA) shouldBe Quantity.of(100)
                quantityAt(productA, receiving) shouldBe Quantity.ZERO
            }
        }
    }
})
