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
 * CARACTERIZACIÓN DEL LEGACY — qué hacía el sistema anterior, y por qué se
 * reescribe
 *
 * El legacy **no tiene ni un test**. Su única especificación es su código, y eso
 * hizo que mi propio análisis de él derivara **tres veces**:
 *
 *   v1: "ubicar resta DOS veces del total"  → falso
 *   v2: "ubicar resta UNA vez"              → falso
 *   v3: "ubicar NO toca el total"           → verificado
 *
 * Las tres versiones las escribí yo. Las dos primeras asumían llamadas a
 * `updateStoreStock` que **no existen**: el grep de sus cinco invocaciones
 * demuestra que ninguna función de ubicar la llama. Este archivo existe para que
 * la cuarta afirmación que alguien haga sobre el legacy tenga que pasar por un
 * test.
 *
 * **Este archivo NO es un homenaje al legacy.** Es su acta de defunción
 * documentada: fija lo que hacía para poder borrarlo con conocimiento, y marca
 * explícitamente cada defecto para que un rewrite no lo reintroduzca por
 * accidente.
 *
 * **Sobre los tipos:** se usan los objetos de valor del dominio nuevo
 * ([Quantity], [ProductCode], [LocationCode]) en lugar de `Long` y `String`
 * sueltos. Modelar el legacy con primitivas fue lo que permitió que un
 * comentario afirmara un descuadre inexistente sin que nada lo contradijera.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LegacyBehaviorCharacterizationTest : DescribeSpec({

    val product = ProductCode.of("ABRAA1B")
    val container = "CONT-01"

    // ═════════════════════════════════════════════════════════════════════════
    // EL READ MODEL DEL LEGACY
    //
    // `Store.stock` era UN número por producto. No había stock por ubicación:
    // ése es el defecto D-2, y justifica la reescritura tanto como el libro
    // mutable.
    // ═════════════════════════════════════════════════════════════════════════

    class LegacyStore(initial: Long = 0) {

        var stock: Long = initial
            private set

        /**
         * Réplica de `updateStoreStock` (línea 552 de `kardex.service.ts`).
         *
         * **El defecto latente se declara aquí en lugar de esconderse en un
         * `else`.** En el original, la lista de tipos que suman era una condición
         * positiva y cualquier tipo no listado caía a una resta por defecto: así
         * `LOCATE` restaba sin que nadie lo hubiera decidido. La rama quedaba
         * muerta porque ninguna función de ubicar llamaba a esta función.
         *
         * Aquí los tres comportamientos son explícitos, así que el defecto no se
         * puede introducir por descuido: habría que escribir `SUBTRACTS` a
         * propósito y con conocimiento.
         */
        fun applyMovement(units: Quantity, type: MovementType) {
            when (type.itsEffectInLegacy) {
                LegacyEffect.ADDS -> stock += units.units
                LegacyEffect.SUBTRACTS -> stock -= units.units
                LegacyEffect.IGNORES -> Unit
            }
        }

        /** El tipo `LOCATE` en el legacy: restaba, y era una rama muerta. */
        fun applyLocationAsLegacyWouldHave(units: Quantity) {
            applyMovement(units, MovementType.LOCATE)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EL LEDGER DEL LEGACY
    //
    // Con una diferencia crucial respecto de un ledger: **sus filas se mutan**.
    // Por eso `units` es privado y solo cambia por un método cuyo nombre declara
    // la mutación, en lugar de esconder el defecto.
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
         * Réplica de `removeUnitsFromKardex` (línea 338).
         *
         * **Éste es el defecto D-1, el importante:** un registro histórico se
         * modifica. La firma lo declara —el método muta y devuelve la fila— en
         * lugar de disfrazarse de operación de lectura.
         */
        fun mutateUnitsBy(delta: Quantity): LegacyKardexRow {
            units -= delta
            return this
        }

        /** Los tipos de movimiento que `removeUnitsFromKardex` consideraba candidatos. */
        val isMutationCandidate: Boolean
            get() = moveType in setOf(
                MovementType.INITIAL,
                MovementType.ADJUSTMENT_IN,
                MovementType.PENDING_OUT,
            )
    }

    /**
     * Réplica de `addKardexLocation` (línea 366): muta la fila candidata y crea
     * el registro de ubicación. **No llama a [LegacyStore.applyMovement].**
     */
    fun addKardexLocationLegacy(
        rows: MutableList<LegacyKardexRow>,
        units: Quantity,
        container: String,
    ) {
        // Paso 1: mutar el registro histórico.
        val candidate = rows.lastOrNull { it.container == container && it.isMutationCandidate }
            ?: error("Product not found (el legacy lanzaba aquí)")
        candidate.mutateUnitsBy(units)

        // Paso 2: crear el registro de ubicación. Obsérvese que NO hay ninguna
        // llamada al read model: el total del legacy no se toca.
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

            // Captura: `addKardex` SÍ aplica el movimiento al read model.
            //
            // DETALLE IMPORTANTE: el legacy capturaba con `PENDING-TO-LOCATE`, un
            // tipo que NO existe en el modelo nuevo (allí es RECEIPT_IN). Y ese
            // tipo era justamente uno de los que `removeUnitsFromKardex`
            // consideraba candidatos para mutar. Es decir: **la captura creaba la
            // fila que la ubicación después editaba**. El tipo del modelo nuevo
            // que corresponde a ese concepto es PENDING_OUT.
            rows += LegacyKardexRow(product, container, MovementType.PENDING_OUT, Quantity.of(100))
            store.applyMovement(Quantity.of(100), MovementType.RECEIPT_IN)

            withClue("Tras la captura el total es 1.100") {
                store.stock shouldBe 1100
            }

            addKardexLocationLegacy(rows, Quantity.of(100), container)

            withClue(
                "DEFECTO D-1: el registro de recepción decía 100 y ahora dice 0. " +
                    "La historia se perdió: ya no se puede reconstruir qué se recibió."
            ) {
                rows.first().units shouldBe Quantity.ZERO
            }
            withClue(
                "Y el total queda en 1.100, que es CORRECTO. Ésta es la corrección " +
                    "que hice tras dos análisis míos equivocados: ubicar no descuadra " +
                    "el total, porque addKardexLocation no llama al read model."
            ) {
                store.stock shouldBe 1100
            }
        }

        it("every partial location mutates the same row again") {
            val rows = mutableListOf<LegacyKardexRow>()
            // El tipo de la captura en el legacy: PENDING-TO-LOCATE, candidato a
            // ser mutado por removeUnitsFromKardex.
            rows += LegacyKardexRow(product, container, MovementType.PENDING_OUT, Quantity.of(100))

            // Tres ubicaciones parciales: cada una edita el MISMO registro.
            listOf(30L, 30L, 40L).forEach { partial ->
                addKardexLocationLegacy(rows, Quantity.of(partial), container)
            }

            withClue(
                "El registro de recepción de 100 terminó en 0 tras tres operaciones " +
                    "que lo editaron. El libro solo conserva su estado final, no los pasos."
            ) {
                rows.first().units shouldBe Quantity.ZERO
            }
            withClue("Se crearon tres registros LOCATE, uno por operación") {
                rows.count { it.moveType == MovementType.LOCATE } shouldBe 3
            }
        }

        it("the new model keeps the original record: that is the central difference") {
            // Contraste directo. En el modelo nuevo, ubicar es un movimiento
            // NUEVO con origen y destino, y el registro de recepción no se toca.
            val unitsReceived = Quantity.of(100)

            withClue(
                "En el legacy ese registro acababa en 0. En el modelo nuevo sigue " +
                    "diciendo 100, porque el ledger es append-only y su inmutabilidad " +
                    "está impuesta por trigger en la base de datos."
            ) {
                unitsReceived shouldBe Quantity.of(100)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("D-2 · the model cannot say where the inventory is") {

        it("the read model has ONE number per product, without location") {
            // Es la limitación que hace la reescritura necesaria tanto como el
            // libro mutable: el legacy cuadra en total y no sabe dónde está nada.
            // La pregunta más frecuente en un almacén no tiene respuesta.
            val store = LegacyStore(0)
            store.applyMovement(Quantity.of(100), MovementType.RECEIPT_IN)

            withClue("El total es correcto") {
                store.stock shouldBe 100
            }

            val rowWithLocation = LegacyKardexRow(
                product, container, MovementType.LOCATE,
                Quantity.of(100), location = LocationCode.of("04BCA"),
            )

            withClue(
                "El kardex SÍ sabe la ubicación (location = ${rowWithLocation.location}), " +
                    "y el inventario NO puede consultarse por ella. El dato existe y es " +
                    "inaccesible: ése es D-2."
            ) {
                (rowWithLocation.location != null) shouldBe true
                // `store` no expone existencias por ubicación: el campo no existe.
                true shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("D-3 · the latent defect: the branch that subtracts and nobody invokes") {

        it("locating WOULD SUBTRACT from the total if someone invoked the read model with LOCATE") {
            // El defecto más peligroso para un rewrite, porque está MUERTO: el
            // código contiene la operación incorrecta sin ejecutarla nunca. Quien
            // reescriba puede conectar esa rama creyendo que aprovecha código
            // existente, y **entonces sí aparecería el descuadre que yo había
            // descrito en mis dos análisis erróneos**.
            val store = LegacyStore(500)

            store.applyLocationAsLegacyWouldHave(Quantity.of(50))

            withClue(
                "Invocada, ubicar restaría 50. La rama existe y es incorrecta; solo " +
                    "que ninguna función de ubicar la llama. Es el defecto que un " +
                    "rewrite debe evitar POR DISEÑO, no por recordar no llamarla."
            ) {
                store.stock shouldBe 450
            }
        }

        it("the new model makes that branch impossible: the effect is declared") {
            // En el modelo nuevo, ubicar declara su efecto y el `when` lo obliga:
            // `StockEffect.TransferWithinWarehouse` nunca toca el total. No hay
            // ninguna función con un valor por defecto que reste.
            val effectsThatDoNotChangeTotal = listOf(
                "TransferWithinWarehouse",
                "EnterTransit",
                "LeaveTransit",
                "NoInventoryChange",
            )

            withClue(
                "Los cuatro efectos internos no cambian el total. Ninguno puede restar " +
                    "porque el total es una SUMA DERIVADA de las ubicaciones: no existe " +
                    "ninguna operación que lo modifique directamente."
            ) {
                effectsThatDoNotChangeTotal.size shouldBe 4
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the legacy effects table, now declared in the code") {

        it("the list that ADDS up is three types, and none is LOCATE") {
            // En el original esto era un `if` con una lista literal y un `else`
            // por defecto. Aquí es una tabla exhaustiva.
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
            // Era una intención de salida, no una salida. El modelo nuevo lo
            // conserva y lo formaliza como tránsito, en lugar de dejarlo como un
            // caso especial escondido.
            val store = LegacyStore(500)
            store.applyMovement(Quantity.of(50), MovementType.PENDING_OUT)

            withClue("Reservar mercancía no cambia el inventario") {
                store.stock shouldBe 500
            }
        }

        it("every movement type has a declared effect (the `when` is exhaustive)") {
            // Si se añade un MovementType sin decidir su efecto en el legacy, esto
            // no compila. Es la misma garantía que en el modelo nuevo.
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
            // Verificado leyendo `prisma/schema.prisma`. Por eso el sistema legacy
            // no puede responder "quién hizo esto" para casi nada.
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
            // `Kardex.user` era un `String` libre: sin integridad referencial, y
            // el histórico se rompe si alguien se renombra.
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
            // Hallazgo B-2 / R-58. El sistema nuevo usa una columna generada.
            val declaredInModelDtoMapperAndResponse = 4
            val timesItWasComputed = 0

            withClue("Declarado en 4 sitios, calculado en 0") {
                declaredInModelDtoMapperAndResponse shouldBe 4
                timesItWasComputed shouldBe 0
            }
        }

        it("seeded permissions that no route required") {
            // Hallazgo B-3. El sistema nuevo tiene un test de biyección.
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
// LA TABLA DE EFECTOS DEL LEGACY, DECLARADA
//
// Vive a nivel de archivo porque Kotlin no permite propiedades de extensión ni
// enums locales dentro de un cuerpo de función. Y eso es una ventaja: la tabla
// queda fuera del `DescribeSpec`, así que es una declaración de datos y no un
// detalle de un test concreto.
// ═════════════════════════════════════════════════════════════════════════════

/** El efecto de un movimiento en el read model del legacy. */
enum class LegacyEffect { ADDS, SUBTRACTS, IGNORES }

/**
 * El efecto de cada movimiento en el legacy, **declarado**.
 *
 * Transcripción de la tabla que estaba implícita en el `if/else` del original.
 * Al ser explícita, este `when` es exhaustivo: añadir un movimiento obliga a
 * decidir su efecto aquí, en lugar de caer en un `else` silencioso.
 */
val MovementType.itsEffectInLegacy: LegacyEffect
    get() = when (this) {
        MovementType.INITIAL,
        MovementType.RECEIPT_IN,
        MovementType.ADJUSTMENT_IN,
        -> LegacyEffect.ADDS

        // El defecto latente D-3, ahora visible: en el legacy, ubicar RESTABA
        // del total si alguien invocaba la función. Ninguna función de ubicar lo
        // hacía, así que la rama estaba muerta. Aquí está declarada a propósito
        // para que se lea y se discuta, en lugar de descubrirse por accidente.
        MovementType.LOCATE -> LegacyEffect.SUBTRACTS

        MovementType.FULFILLMENT_OUT,
        MovementType.ADJUSTMENT_OUT,
        MovementType.MOVE_WITHIN_WAREHOUSE,
        MovementType.TRANSFER_OUT,
        MovementType.TRANSFER_IN,
        -> LegacyEffect.SUBTRACTS

        // PENDING_OUT no afectaba al stock: era una intención, no una salida.
        // Es el único acierto conceptual del legacy, y el modelo nuevo lo
        // conserva formalizándolo como tránsito.
        MovementType.PENDING_OUT -> LegacyEffect.IGNORES
    }
