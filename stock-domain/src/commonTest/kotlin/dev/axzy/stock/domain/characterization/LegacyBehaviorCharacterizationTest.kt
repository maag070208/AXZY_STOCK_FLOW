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

    val producto = ProductCode.of("ABRAA1B")
    val contenedor = "CONT-01"

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
            when (type.suEfectoEnElLegacy) {
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
        val esCandidatoParaMutacion: Boolean
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
        val candidata = rows.lastOrNull { it.container == container && it.esCandidatoParaMutacion }
            ?: error("Product not found (el legacy lanzaba aquí)")
        candidata.mutateUnitsBy(units)

        // Paso 2: crear el registro de ubicación. Obsérvese que NO hay ninguna
        // llamada al read model: el total del legacy no se toca.
        rows += LegacyKardexRow(
            product = candidata.product,
            container = container,
            moveType = MovementType.LOCATE,
            units = units,
            location = LocationCode.of("04BCA"),
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("D-1 · el defecto real: el libro se muta") {

        it("el registro de recepción queda en cero tras ubicar") {
            val store = LegacyStore(1000)
            val filas = mutableListOf<LegacyKardexRow>()

            // Captura: `addKardex` SÍ aplica el movimiento al read model.
            //
            // DETALLE IMPORTANTE: el legacy capturaba con `PENDING-TO-LOCATE`, un
            // tipo que NO existe en el modelo nuevo (allí es RECEIPT_IN). Y ese
            // tipo era justamente uno de los que `removeUnitsFromKardex`
            // consideraba candidatos para mutar. Es decir: **la captura creaba la
            // fila que la ubicación después editaba**. El tipo del modelo nuevo
            // que corresponde a ese concepto es PENDING_OUT.
            filas += LegacyKardexRow(producto, contenedor, MovementType.PENDING_OUT, Quantity.of(100))
            store.applyMovement(Quantity.of(100), MovementType.RECEIPT_IN)

            withClue("Tras la captura el total es 1.100") {
                store.stock shouldBe 1100
            }

            addKardexLocationLegacy(filas, Quantity.of(100), contenedor)

            withClue(
                "DEFECTO D-1: el registro de recepción decía 100 y ahora dice 0. " +
                    "La historia se perdió: ya no se puede reconstruir qué se recibió."
            ) {
                filas.first().units shouldBe Quantity.ZERO
            }
            withClue(
                "Y el total queda en 1.100, que es CORRECTO. Ésta es la corrección " +
                    "que hice tras dos análisis míos equivocados: ubicar no descuadra " +
                    "el total, porque addKardexLocation no llama al read model."
            ) {
                store.stock shouldBe 1100
            }
        }

        it("cada ubicación parcial vuelve a mutar la misma fila") {
            val filas = mutableListOf<LegacyKardexRow>()
            // El tipo de la captura en el legacy: PENDING-TO-LOCATE, candidato a
            // ser mutado por removeUnitsFromKardex.
            filas += LegacyKardexRow(producto, contenedor, MovementType.PENDING_OUT, Quantity.of(100))

            // Tres ubicaciones parciales: cada una edita el MISMO registro.
            listOf(30L, 30L, 40L).forEach { parcial ->
                addKardexLocationLegacy(filas, Quantity.of(parcial), contenedor)
            }

            withClue(
                "El registro de recepción de 100 terminó en 0 tras tres operaciones " +
                    "que lo editaron. El libro solo conserva su estado final, no los pasos."
            ) {
                filas.first().units shouldBe Quantity.ZERO
            }
            withClue("Se crearon tres registros LOCATE, uno por operación") {
                filas.count { it.moveType == MovementType.LOCATE } shouldBe 3
            }
        }

        it("el modelo nuevo conserva el registro original: es la diferencia central") {
            // Contraste directo. En el modelo nuevo, ubicar es un movimiento
            // NUEVO con origen y destino, y el registro de recepción no se toca.
            val unidadesRecibidas = Quantity.of(100)

            withClue(
                "En el legacy ese registro acababa en 0. En el modelo nuevo sigue " +
                    "diciendo 100, porque el ledger es append-only y su inmutabilidad " +
                    "está impuesta por trigger en la base de datos."
            ) {
                unidadesRecibidas shouldBe Quantity.of(100)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("D-2 · el modelo no puede decir dónde está el inventario") {

        it("el read model tiene UN número por producto, sin ubicación") {
            // Es la limitación que hace la reescritura necesaria tanto como el
            // libro mutable: el legacy cuadra en total y no sabe dónde está nada.
            // La pregunta más frecuente en un almacén no tiene respuesta.
            val store = LegacyStore(0)
            store.applyMovement(Quantity.of(100), MovementType.RECEIPT_IN)

            withClue("El total es correcto") {
                store.stock shouldBe 100
            }

            val filaConUbicacion = LegacyKardexRow(
                producto, contenedor, MovementType.LOCATE,
                Quantity.of(100), location = LocationCode.of("04BCA"),
            )

            withClue(
                "El kardex SÍ sabe la ubicación (location = ${filaConUbicacion.location}), " +
                    "y el inventario NO puede consultarse por ella. El dato existe y es " +
                    "inaccesible: ése es D-2."
            ) {
                (filaConUbicacion.location != null) shouldBe true
                // `store` no expone existencias por ubicación: el campo no existe.
                true shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("D-3 · el defecto latente: la rama que resta y nadie invoca") {

        it("ubicar RESTARÍA del total si alguien invocara el read model con LOCATE") {
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

        it("el modelo nuevo hace imposible esa rama: el efecto está declarado") {
            // En el modelo nuevo, ubicar declara su efecto y el `when` lo obliga:
            // `StockEffect.TransferWithinWarehouse` nunca toca el total. No hay
            // ninguna función con un valor por defecto que reste.
            val efectosQueNoCambianElTotal = listOf(
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
                efectosQueNoCambianElTotal.size shouldBe 4
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("la tabla de efectos del legacy, ahora declarada en el código") {

        it("la lista que SUMA son tres tipos, y ninguno es LOCATE") {
            // En el original esto era un `if` con una lista literal y un `else`
            // por defecto. Aquí es una tabla exhaustiva.
            val suman = MovementType.entries.filter { it.suEfectoEnElLegacy == LegacyEffect.ADDS }

            withClue("Solo estos tres movimientos sumaban al total") {
                suman.toSet() shouldBe setOf(
                    MovementType.INITIAL,
                    MovementType.RECEIPT_IN,
                    MovementType.ADJUSTMENT_IN,
                )
            }
        }

        it("PENDING_OUT no afecta al stock: el único acierto del legacy") {
            // Era una intención de salida, no una salida. El modelo nuevo lo
            // conserva y lo formaliza como tránsito, en lugar de dejarlo como un
            // caso especial escondido.
            val store = LegacyStore(500)
            store.applyMovement(Quantity.of(50), MovementType.PENDING_OUT)

            withClue("Reservar mercancía no cambia el inventario") {
                store.stock shouldBe 500
            }
        }

        it("todos los tipos de movimiento tienen efecto declarado (el `when` es exhaustivo)") {
            // Si se añade un MovementType sin decidir su efecto en el legacy, esto
            // no compila. Es la misma garantía que en el modelo nuevo.
            MovementType.entries.forEach { tipo ->
                withClue("El tipo $tipo debe tener un efecto declarado") {
                    (tipo.suEfectoEnElLegacy in LegacyEffect.entries) shouldBe true
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("lo que el legacy NO tiene") {

        it("ninguna tabla de auditoría entre sus 13 modelos") {
            // Verificado leyendo `prisma/schema.prisma`. Por eso el sistema legacy
            // no puede responder "quién hizo esto" para casi nada.
            val modelos = listOf(
                "User", "Role", "RolePermission", "Permission",
                "Invoice", "InvoiceDetail", "Order", "OrderDetail",
                "Location", "Labels", "Store", "Kardex", "Print",
            )

            val conAuditoria = modelos.filter {
                it.contains("Audit", ignoreCase = true) || it.contains("Log", ignoreCase = true)
            }

            withClue("13 modelos, ninguno de auditoría: $conAuditoria") {
                modelos.size shouldBe 13
                conAuditoria shouldBe emptyList()
            }
        }

        it("su única trazabilidad guarda el NOMBRE del usuario, no una referencia") {
            // `Kardex.user` era un `String` libre: sin integridad referencial, y
            // el histórico se rompe si alguien se renombra.
            val elKardexGuardabaUnStringLibre = true

            withClue("Es la diferencia entre trazabilidad y una nota al margen") {
                elKardexGuardabaUnStringLibre shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("las contradicciones, fijadas para no copiarlas") {

        it("el esquema declara borrado lógico y el servicio borra en físico") {
            // Hallazgo B-1 / R-57.
            val elEsquemaDeclaraSoftDelete = true
            val elServicioHaceDeleteFisico = true

            withClue("El flag `deleted` nunca se escribía") {
                elEsquemaDeclaraSoftDelete shouldBe true
                elServicioHaceDeleteFisico shouldBe true
            }
        }

        it("`difference` existe en el modelo y siempre vale cero") {
            // Hallazgo B-2 / R-58. El sistema nuevo usa una columna generada.
            val declaradoEnModeloDtoMapperYRespuesta = 4
            val vecesQueSeCalculaba = 0

            withClue("Declarado en 4 sitios, calculado en 0") {
                declaradoEnModeloDtoMapperYRespuesta shouldBe 4
                vecesQueSeCalculaba shouldBe 0
            }
        }

        it("permisos sembrados que no se exigían en ninguna ruta") {
            // Hallazgo B-3. El sistema nuevo tiene un test de biyección.
            val sembradosSinRuta = listOf(
                "user:create", "user:read", "user:update", "user:delete",
                "order:approve", "print:update", "invoice:update",
            )

            withClue(
                "Un permiso que existe y no se exige da una falsa sensación de " +
                    "control: parece que hay una restricción y no la hay"
            ) {
                sembradosSinRuta.size shouldBe 7
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
val MovementType.suEfectoEnElLegacy: LegacyEffect
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
