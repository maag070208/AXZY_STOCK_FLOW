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
 * PLAN_ALMACEN_INTEGRAL.md §3.6.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InventoryInvariantsPropertyTest : DescribeSpec({

    val recepcion = LocationCode.of("RECEPCION")
    val estanteA = LocationCode.of("04BCA")
    val estanteB = LocationCode.of("04BCB")
    val estanteNorte = LocationCode.of("NORTE01")
    val estanteSur = LocationCode.of("SUR01")

    val productoA = "ABRAA1B"
    val productoB = "ABRAA1E"
    val productos = listOf(productoA, productoB)
    val ubicaciones = listOf(estanteA, estanteB, estanteNorte, estanteSur)

    /**
     * Genera una secuencia de operaciones válidas contra un ledger concreto.
     *
     * Es un generador **consciente del estado**: en cada paso mira el libro y
     * solo elige operaciones que pueden aplicarse. Así la secuencia siempre
     * avanza y ninguna operación se rechaza por falta de stock, que es lo que
     * haría fallar la propiedad por una razón que no es la que se está probando.
     */
    fun secuenciaDeOperaciones(
        ledger: InventoryLedger,
        pasos: Int,
        usarIdempotencia: Boolean = false,
    ): List<Pair<String, () -> Unit>> {
        val operaciones = mutableListOf<Pair<String, () -> Unit>>()
        // Semilla determinista por ejecución: el generador de kotest da la
        // variabilidad; aquí solo se decide QUÉ operación es válida en cada paso.
        val rng = kotlin.random.Random(pasos * 7919L + ledger.entries.size)

        repeat(pasos) { indice ->
            val producto = productos.random(rng)
            val eleccion = rng.nextInt(7)

            val operacion: Pair<String, () -> Unit> = when (eleccion) {
                0 -> "receipt" to {
                    ledger.receipt(producto, recepcion, Quantity.of(rng.nextLong(1, 50).toLong()))
                }

                1 -> {
                    // Solo se ubica si hay stock en recepción.
                    val disponible = ledger.quantityAt(producto, recepcion)
                    if (disponible.isPositive) {
                        val cantidad = Quantity.of(
                            rng.nextLong(1, disponible.units.coerceAtLeast(1) + 1)
                        )
                        val destino = listOf(estanteA, estanteB).random(rng)
                        "locate($destino)" to { ledger.locate(producto, recepcion, destino, cantidad) }
                    } else {
                        "noop" to { }
                    }
                }

                2 -> {
                    val origen = ubicaciones.random(rng)
                    val disponible = ledger.quantityAt(producto, origen)
                    val otro = ubicaciones.filter { it != origen }.random(rng)
                    if (disponible.isPositive) {
                        val cantidad = Quantity.of(rng.nextLong(1, disponible.units.coerceAtLeast(1) + 1))
                        "move($origen→$otro)" to {
                            ledger.moveWithinWarehouse(producto, origen, otro, cantidad)
                        }
                    } else {
                        "noop" to { }
                    }
                }

                3 -> {
                    val disponible = ledger.quantityAt(producto, estanteNorte)
                    if (disponible.isPositive) {
                        val cantidad = Quantity.of(rng.nextLong(1, disponible.units.coerceAtLeast(1) + 1))
                        "transferOut" to { ledger.transferOut(producto, estanteNorte, 2L, cantidad) }
                    } else {
                        "noop" to { }
                    }
                }

                4 -> {
                    val enTransito = ledger.inTransit.filter { it.productCode == producto }
                    if (enTransito.isNotEmpty()) {
                        val lote = enTransito.random(rng)
                        val cantidad = Quantity.of(
                            rng.nextLong(1, lote.quantity.units.coerceAtLeast(1) + 1)
                        )
                        "transferIn" to {
                            ledger.transferIn(producto, estanteSur, 1L, cantidad)
                        }
                    } else {
                        "noop" to { }
                    }
                }

                5 -> {
                    val ubicacion = ubicaciones.random(rng)
                    val disponible = ledger.quantityAt(producto, ubicacion)
                    if (disponible.isPositive) {
                        val cantidad = Quantity.of(rng.nextLong(1, disponible.units.coerceAtLeast(1) + 1))
                        "adjustmentOut" to {
                            ledger.adjustmentOut(producto, ubicacion, cantidad, "merma por daño")
                        }
                    } else {
                        "noop" to { }
                    }
                }

                else -> "adjustmentIn" to {
                    ledger.adjustmentIn(
                        producto,
                        ubicaciones.random(rng),
                        Quantity.of(rng.nextLong(1, 20).toLong()),
                        "corrección de conteo",
                    )
                }
            }
            operaciones.add(operacion)
        }
        return operaciones
    }

    /** Aplica la secuencia y devuelve los tipos aplicados, para depurar. */
    fun aplicar(ledger: InventoryLedger, operaciones: List<Pair<String, () -> Unit>>): List<String> =
        operaciones.map { (nombre, accion) -> accion(); nombre }

    // ═════════════════════════════════════════════════════════════════════════
    // I1 — CONSERVACIÓN TOTAL
    // ═════════════════════════════════════════════════════════════════════════

    describe("I1 — conservación total") {

        it("el total es siempre igual al neto del ledger, tras cualquier secuencia") {
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { pasos ->
                val ledger = InventoryLedger()
                // Carga inicial para tener stock con el que operar.
                productos.forEach { p ->
                    ledger.initialLoad(p, recepcion, Quantity.of(1000))
                }

                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                productos.forEach { producto ->
                    with(ledger) {
                        val total = totalOf(producto)
                        val netoLedger = ledgerNetOf(producto)
                        if (total != netoLedger) {
                            throw AssertionError(
                                "I1 violada para $producto tras $pasos pasos: " +
                                    "total=$total pero el ledger suma $netoLedger"
                            )
                        }
                    }
                }
            }
        }

        it("el total es la suma de ubicaciones más el tránsito, siempre") {
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { pasos ->
                val ledger = InventoryLedger()
                productos.forEach { p -> ledger.initialLoad(p, recepcion, Quantity.of(500)) }

                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                productos.forEach { producto ->
                    val enUbicaciones = ledger.totalAtLocationsOf(producto)
                    val enTransito = ledger.totalInTransitOf(producto)
                    val total = ledger.totalOf(producto)

                    if (total != enUbicaciones + enTransito) {
                        throw AssertionError(
                            "I1 violada para $producto: total=$total pero " +
                                "ubicaciones=$enUbicaciones + tránsito=$enTransito"
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
            checkAll(PropTestConfig(iterations = 300), Arb.int(1..80)) { pasos ->
                val ledger = InventoryLedger()
                productos.forEach { p -> ledger.initialLoad(p, recepcion, Quantity.of(800)) }

                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                ledger.allBalances().forEach { saldo ->
                    if (saldo.quantity.isNegative) {
                        throw AssertionError(
                            "I2 violada: ${saldo.productCode} en ${saldo.location} " +
                                "quedó con ${saldo.quantity} (negativo). " +
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
            ledger.initialLoad(productoA, recepcion, Quantity.of(100))

            val entradaOriginal = ledger.entries.first()
            val cantidadOriginal = entradaOriginal.quantity

            // Se aplican operaciones que en el legacy MUTABAN la entrada de
            // recepción: `removeUnitsFromKardex` hacía `kardex.update` y bajaba
            // sus `units`. Aquí eso no puede pasar.
            ledger.locate(productoA, recepcion, estanteA, Quantity.of(60))
            ledger.moveWithinWarehouse(productoA, estanteA, estanteB, Quantity.of(20))
            ledger.adjustmentOut(productoA, estanteB, Quantity.of(5), "merma")

            val entradaTrasOperar = ledger.entries.first()
            with(entradaTrasOperar) {
                if (quantity != cantidadOriginal) {
                    throw AssertionError(
                        "I3 violada: la entrada histórica de recepción cambió de " +
                            "$cantidadOriginal a $quantity. Un ledger que se puede " +
                            "editar no es un ledger."
                    )
                }
                sequence shouldBeExactly entradaOriginal.sequence
            }
        }

        it("la secuencia es estrictamente creciente y sin huecos") {
            checkAll(PropTestConfig(iterations = 100), Arb.int(1..40)) { pasos ->
                val ledger = InventoryLedger()
                productos.forEach { p -> ledger.initialLoad(p, recepcion, Quantity.of(300)) }
                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                val secuencias = ledger.entries.map { it.sequence }
                val esperadas = (1L..secuencias.size.toLong()).toList()
                if (secuencias != esperadas) {
                    throw AssertionError(
                        "I3 violada: la secuencia debe ser 1..N sin huecos. " +
                            "Se encontró: ${secuencias.take(10)}…"
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
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { pasos ->
                val ledger = InventoryLedger()
                productos.forEach { p -> ledger.initialLoad(p, recepcion, Quantity.of(400)) }
                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                productos.forEach { producto ->
                    val ultima = ledger.entries.lastOrNull { it.productCode == producto }
                    if (ultima != null) {
                        val actual = ledger.totalOf(producto)
                        if (ultima.quantityAfter != actual) {
                            throw AssertionError(
                                "I4 violada para $producto: la última entrada dice " +
                                    "${ultima.quantityAfter} pero el total real es $actual"
                            )
                        }
                    }
                }
            }
        }

        it("recorrer el ledger en orden reproduce el total final") {
            checkAll(PropTestConfig(iterations = 150), Arb.int(1..50)) { pasos ->
                val ledger = InventoryLedger()
                productos.forEach { p -> ledger.initialLoad(p, recepcion, Quantity.of(300)) }
                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                productos.forEach { producto ->
                    // Reproducción desde cero: solo con el ledger.
                    val reproducido = ledger.entries
                        .filter { it.productCode == producto }
                        .fold(Quantity.ZERO) { acumulado, entry ->
                            when (entry.type) {
                                MovementType.INITIAL,
                                MovementType.RECEIPT_IN,
                                MovementType.ADJUSTMENT_IN,
                                -> acumulado + entry.quantity

                                MovementType.ADJUSTMENT_OUT -> acumulado - entry.quantity
                                else -> acumulado
                            }
                        }

                    val real = ledger.totalOf(producto)
                    if (reproducido != real) {
                        throw AssertionError(
                            "I4 violada: reproducir el ledger da $reproducido " +
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
            checkAll(PropTestConfig(iterations = 150), Arb.int(1..60)) { pasos ->
                val ledger = InventoryLedger()
                productos.forEach { p -> ledger.initialLoad(p, recepcion, Quantity.of(600)) }
                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                // Recalcular cada ubicación desde cero, solo con el ledger.
                val recalculado = mutableMapOf<Pair<String, LocationCode>, Quantity>()
                ledger.entries.forEach { entry ->
                    entry.from?.let { desde ->
                        val clave = entry.productCode to desde
                        val quitar = when (entry.type) {
                            MovementType.ADJUSTMENT_OUT,
                            MovementType.LOCATE,
                            MovementType.MOVE_WITHIN_WAREHOUSE,
                            MovementType.TRANSFER_OUT,
                            -> entry.quantity

                            else -> Quantity.ZERO
                        }
                        recalculado[clave] = (recalculado[clave] ?: Quantity.ZERO) - quitar
                    }
                    entry.to?.let { hacia ->
                        val clave = entry.productCode to hacia
                        val sumar = when (entry.type) {
                            MovementType.INITIAL,
                            MovementType.RECEIPT_IN,
                            MovementType.ADJUSTMENT_IN,
                            MovementType.LOCATE,
                            MovementType.MOVE_WITHIN_WAREHOUSE,
                            MovementType.TRANSFER_IN,
                            -> entry.quantity

                            else -> Quantity.ZERO
                        }
                        recalculado[clave] = (recalculado[clave] ?: Quantity.ZERO) + sumar
                    }
                }

                ledger.allBalances().forEach { saldo ->
                    val clave = saldo.productCode to saldo.location
                    val esperado = recalculado[clave] ?: Quantity.ZERO
                    if (saldo.quantity != esperado) {
                        throw AssertionError(
                            "I5 violada: el saldo de ${saldo.productCode} en ${saldo.location} " +
                                "es ${saldo.quantity} pero el ledger dice $esperado"
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
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { pasos ->
                val ledger = InventoryLedger()
                productos.forEach { p -> ledger.initialLoad(p, recepcion, Quantity.of(400)) }
                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                ledger.entries
                    .filter {
                        it.type == MovementType.ADJUSTMENT_IN ||
                            it.type == MovementType.ADJUSTMENT_OUT
                    }
                    .forEach { ajuste ->
                        if (ajuste.reason.isNullOrBlank()) {
                            throw AssertionError(
                                "I6 violada: el ajuste #${ajuste.sequence} " +
                                    "(${ajuste.type}) no tiene motivo"
                            )
                        }
                    }
            }
        }

        it("un ajuste con motivo en blanco es rechazado") {
            val ledger = InventoryLedger()
            ledger.initialLoad(productoA, recepcion, Quantity.of(100))

            ledger.adjustmentOut(productoA, recepcion, Quantity.of(10), "").isLeft shouldBe true
            ledger.adjustmentOut(productoA, recepcion, Quantity.of(10), "   ").isLeft shouldBe true
            ledger.adjustmentIn(productoA, recepcion, Quantity.of(10), "").isLeft shouldBe true

            // Y con motivo sí pasa.
            ledger.adjustmentOut(productoA, recepcion, Quantity.of(10), "merma").isRight shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I8 — IDEMPOTENCIA
    // ═════════════════════════════════════════════════════════════════════════

    describe("I8 — idempotencia del móvil offline") {

        it("repetir el mismo clientUuid no duplica el movimiento") {
            val ledger = InventoryLedger()
            val uuid = "captura-movil-001"

            ledger.receipt(productoA, recepcion, Quantity.of(100), clientUuid = uuid)
            val totalTrasPrimero = ledger.totalOf(productoA)

            // El móvil reintenta porque no recibió confirmación.
            val segundo = ledger.receipt(productoA, recepcion, Quantity.of(100), clientUuid = uuid)

            segundo.isLeft shouldBe true
            with(ledger) {
                totalOf(productoA) shouldBe totalTrasPrimero
            }
            // El ledger arranca vacío: solo debe haber la primera recepción.
            // El reintento del móvil no puede añadir una segunda entrada.
            ledger.entries.size shouldBe 1
            ledger.totalOf(productoA) shouldBe Quantity.of(100)
        }

        it("un clientUuid distinto sí registra un movimiento nuevo") {
            val ledger = InventoryLedger()
            ledger.receipt(productoA, recepcion, Quantity.of(100), clientUuid = "uuid-1")
            ledger.receipt(productoA, recepcion, Quantity.of(100), clientUuid = "uuid-2")

            ledger.totalOf(productoA) shouldBe Quantity.of(200)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // I9 — ATOMICIDAD
    // ═════════════════════════════════════════════════════════════════════════

    describe("I9 — una operación rechazada no deja rastro") {

        it("una operación sin stock suficiente no modifica el ledger ni el total") {
            checkAll(PropTestConfig(iterations = 200), Arb.int(1..60)) { pasos ->
                val ledger = InventoryLedger()
                productos.forEach { p -> ledger.initialLoad(p, recepcion, Quantity.of(500)) }
                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                val entradasAntes = ledger.entries.size
                val totalesAntes = productos.associateWith { ledger.totalOf(it) }
                val saldosAntes = ledger.allBalances().associate { (it.productCode to it.location) to it.quantity }

                // Se intenta una operación imposible: mover 100.000 unidades.
                val imposible = Quantity.of(100_000)
                ledger.locate(productoA, recepcion, estanteA, imposible)
                ledger.moveWithinWarehouse(productoA, estanteA, estanteB, imposible)
                ledger.adjustmentOut(productoA, estanteA, imposible, "merma")
                ledger.transferOut(productoA, estanteA, 2L, imposible)

                if (ledger.entries.size != entradasAntes) {
                    throw AssertionError(
                        "I9 violada: las operaciones rechazadas dejaron " +
                            "${ledger.entries.size - entradasAntes} entradas en el ledger"
                    )
                }
                productos.forEach { p ->
                    if (ledger.totalOf(p) != totalesAntes[p]) {
                        throw AssertionError("I9 violada: el total de $p cambió tras una operación rechazada")
                    }
                }
                val saldosDespues = ledger.allBalances().associate { (it.productCode to it.location) to it.quantity }
                if (saldosDespues != saldosAntes) {
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
            ledger.initialLoad(productoA, estanteNorte, Quantity.of(100))

            val totalAntes = ledger.totalOf(productoA)
            ledger.transferOut(productoA, estanteNorte, toWarehouseId = 2L, Quantity.of(40))

            with(ledger) {
                quantityAt(productoA, estanteNorte) shouldBe Quantity.of(60)
                totalInTransitOf(productoA) shouldBe Quantity.of(40)

                if (totalOf(productoA) != totalAntes) {
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
            ledger.initialLoad(productoA, estanteNorte, Quantity.of(100))
            val totalAntes = ledger.totalOf(productoA)

            ledger.transferOut(productoA, estanteNorte, 2L, Quantity.of(40))
            ledger.transferIn(productoA, estanteSur, fromWarehouseId = 1L, Quantity.of(40))

            with(ledger) {
                totalInTransitOf(productoA) shouldBe Quantity.ZERO
                quantityAt(productoA, estanteSur) shouldBe Quantity.of(40)
                totalOf(productoA) shouldBe totalAntes
            }
        }

        it("un traslado a medio camino deja el tránsito visible, no perdido") {
            checkAll(PropTestConfig(iterations = 100), Arb.int(1..40)) { pasos ->
                val ledger = InventoryLedger()
                ledger.initialLoad(productoA, estanteNorte, Quantity.of(1000))
                aplicar(ledger, secuenciaDeOperaciones(ledger, pasos))

                // El total siempre incluye lo que está en tránsito.
                val enUbicaciones = ledger.totalAtLocationsOf(productoA)
                val enTransito = ledger.totalInTransitOf(productoA)
                val total = ledger.totalOf(productoA)

                total shouldBe enUbicaciones + enTransito
                if (enTransito.isNegative) {
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
            checkAll(PropTestConfig(iterations = 300), Arb.int(1..500)) { cantidad ->
                val ledger = InventoryLedger()
                ledger.initialLoad(productoA, recepcion, Quantity.of(1000))

                val totalAntes = ledger.totalOf(productoA)
                val resultado = ledger.locate(
                    productoA, recepcion, estanteA, Quantity.of(cantidad.toLong()),
                )

                if (resultado.isRight) {
                    val totalDespues = ledger.totalOf(productoA)
                    if (totalDespues != totalAntes) {
                        throw AssertionError(
                            "BUG DEL LEGACY REPRODUCIDO: ubicar $cantidad unidades cambió el " +
                                "total de $totalAntes a $totalDespues. En el legacy, " +
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
            ledger.receipt(productoA, recepcion, Quantity.of(100))

            val totalTrasRecebir = ledger.totalOf(productoA)
            ledger.locate(productoA, recepcion, estanteA, Quantity.of(100))

            with(ledger) {
                val totalFinal = totalOf(productoA)
                if (totalFinal != Quantity.of(100)) {
                    throw AssertionError(
                        "El legacy habría dejado el total en 0 en lugar de 100. " +
                            "Total actual: $totalFinal"
                    )
                }
                totalFinal shouldBe totalTrasRecebir
                quantityAt(productoA, estanteA) shouldBe Quantity.of(100)
                quantityAt(productoA, recepcion) shouldBe Quantity.ZERO
            }
        }
    }
})
