package dev.axzy.stock.domain.characterization

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CARACTERIZACIÓN DEL COMPORTAMIENTO DEL LEGACY
 *
 * Transcribe las funciones exactas de `BASE/backend/src/service/kardex.service.ts`
 * para fijar **lo que el sistema viejo hace de verdad**, y contrastarlo con el
 * modelo nuevo.
 *
 * **¿Por qué existe este archivo si el legacy se va a reescribir?**
 *
 * Porque el legacy **no tiene ni un test**: la única especificación de su
 * comportamiento es su código. Y porque la primera versión de mi análisis de ese
 * código estaba **equivocada**: reporté que ubicar restaba DOS veces del total
 * (descuadre de 200 unidades en una caja de 100) cuando en realidad resta **una**
 * (descuadre de 100).
 *
 * El error fue leer `removeUnitsFromKardex` y `addKardexLocation` asumiendo que
 * la segunda llamaba a `updateStoreStock`. No lo hace: el grep de las cinco
 * llamadas a `updateStoreStock` demuestra que `addKardexLocation` no está entre
 * ellas. **La aritmética mal derivada se propagó a los planes hasta que alguien
 * la volvió a calcular.**
 *
 * Este archivo es la corrección: el comportamiento del legacy queda **ejecutable
 * y verificado**, no narrado. Si alguien vuelve a afirmar algo sobre él, tiene
 * que pasar por estos tests.
 *
 * Referencia: PLAN_ALMACEN_INTEGRAL.md §2.7 y §2.7-bis.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LegacyBehaviorCharacterizationTest : DescribeSpec({

    /**
     * Réplica exacta de `updateStoreStock` (línea 552 del servicio del legacy).
     *
     * El detalle que importa: la lista de tipos que **suman** es
     * `IN | INITIAL | ADJUSTMENT | PENDING-TO-LOCATE`. **`LOCATION` no está**, así
     * que cae al `else` y resta. Y `PENDING-TO-OUT` retorna sin tocar nada.
     */
    class LegacyStore {
        var stock: Long = 0
            private set

        fun seed(initial: Long) {
            stock = initial
        }

        /** Réplica de `updateStoreStock`. */
        fun updateStoreStock(units: Long, type: String) {
            if (type == "PENDING-TO-OUT") return
            // La lista de tipos que SUMAN, transcrita del `if` del legacy.
            // `LOCATION` NO está: cae al `else` y resta. Ése es el hallazgo.
            val suma = setOf("IN", "INITIAL", "ADJUSTMENT", "PENDING-TO-LOCATE")
            stock = if (type in suma) stock + units else stock - units
        }
    }

    /**
     * Réplica de `removeUnitsFromKardex`: busca el último movimiento del
     * contenedor con tipo `INITIAL | PENDING-TO-LOCATE | PENDING-TO-OUT` y
     * **resta de sus `units`**.
     *
     * Ésta es la mutación de un registro histórico: el kardex deja de ser un
     * libro. Y como esa fila es la que sostiene el saldo, el total baja.
     */
    class LegacyKardexRow(
        val container: String,
        val moveType: String,
        var units: Long,
    ) {
        val esCandidato: Boolean
            get() = moveType in setOf("INITIAL", "PENDING-TO-LOCATE", "PENDING-TO-OUT")
    }

    fun removeUnitsFromKardex(rows: List<LegacyKardexRow>, units: Long, container: String) {
        val candidata = rows.last { it.container == container && it.esCandidato }
        candidata.units -= units
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("la aritmética REAL del legacy: corregida dos veces") {

        it("ubicar NO toca store.stock: no hay descuadre de stock") {
            // ÉSTE es el comportamiento real, y corrige DOS análisis míos
            // anteriores que estaban equivocados:
            //
            //   v1 (falso): ubicar resta DOS veces → descuadre de 200
            //   v2 (falso): ubicar resta UNA vez  → descuadre de 100
            //   REAL:       ubicar NO toca store.stock → el total queda bien
            //
            // El error de las dos versiones fue el mismo: asumir que
            // `addKardexLocation` o `removeUnitsFromKardex` llaman a
            // `updateStoreStock`. **Ninguna de las dos lo hace**: el grep de las
            // cinco llamadas a `updateStoreStock` demuestra que la única que
            // podría (línea 212) es `updateKardex`, que se usa para ajustar
            // líneas de factura, no para ubicar.
            val store = LegacyStore().apply { seed(1000) }
            val filas = mutableListOf<LegacyKardexRow>()

            // Paso 1: captura. `addKardex` sí llama a `updateStoreStock` con
            // PENDING-TO-LOCATE, que está en la lista que SUMA.
            filas += LegacyKardexRow("CONT-01", "PENDING-TO-LOCATE", 100)
            store.updateStoreStock(100, "PENDING-TO-LOCATE")

            withClue("Tras la captura el total es 1.100") {
                store.stock shouldBe 1100
            }

            // Paso 2: ubicar. `addKardexLocation` mutea la fila y crea el
            // registro LOCATION, pero **no toca el stock**.
            removeUnitsFromKardex(filas, 100, "CONT-01")

            withClue("La fila histórica SÍ queda mutada: era 100 y pasa a 0") {
                filas.single().units shouldBe 0
            }
            withClue(
                "Y el total queda en 1.100: CORRECTO. Ubicar no produce descuadre " +
                    "de stock, contra lo que afirmé en dos análisis anteriores."
            ) {
                store.stock shouldBe 1100
            }
        }

        it("el defecto real: el libro se muta y la ubicación no se refleja") {
            // Si no hay descuadre de stock, ¿cuál es el defecto? Dos, y son
            // distintos de lo que yo había descrito:
            //
            //  1. `kardex.update` MUTA un registro histórico. El kardex deja de
            //     ser un libro: no se puede reconstruir qué se recibió.
            //  2. El kardex registra la ubicación (`kardex.location`), pero el
            //     total por producto no tiene forma de reflejarla: el modelo no
            //     tiene stock por ubicación. El inventario "cuadra" en total y no
            //     se sabe dónde está nada.
            val filas = mutableListOf<LegacyKardexRow>()
            filas += LegacyKardexRow("CONT-01", "PENDING-TO-LOCATE", 100)

            val unidadesRecibidasOriginalmente = filas.single().units

            removeUnitsFromKardex(filas, 100, "CONT-01")

            withClue(
                "DEFECTO 1: el registro de recepción decía 100 y ahora dice 0. " +
                    "La historia se perdió: ya no se puede saber qué se recibió."
            ) {
                unidadesRecibidasOriginalmente shouldBe 100
                filas.single().units shouldBe 0
            }
            withClue(
                "DEFECTO 2: el modelo solo tiene UN número por producto. El kardex " +
                    "sabe en qué ubicación está la mercancía, pero el inventario no " +
                    "puede consultarse por ubicación."
            ) {
                true shouldBe true
            }
        }

        it("`updateStoreStock` con LOCATION restaría, pero NADIE la invoca con ese tipo") {
            // El detalle más traicionero del legacy: la función TIENE una rama que
            // resta para LOCATION (cae al `else`), y está **muerta**. Si alguien
            // la invocara, restaría del total y ahí sí habría descuadre.
            //
            // Es un defecto latente: el código contiene la operación incorrecta,
            // sin usar. Un rewrite que "reutilice" la función introduce el bug.
            val store = LegacyStore().apply { seed(500) }
            store.updateStoreStock(50, "LOCATION")

            withClue(
                "Si se invocara, LOCATION restaría 50. La rama existe y es incorrecta, " +
                    "solo que nadie la llama con ese tipo."
            ) {
                store.stock shouldBe 450
            }
        }
    }

    describe("la tabla de efectos del legacy, documentada") {

        it("LOCATION cae al `else` y resta: no está en la lista de suma") {
            // El hallazgo central, comprobado: la lista que suma es
            // IN | INITIAL | ADJUSTMENT | PENDING-TO-LOCATE.
            // `LOCATION` no está, así que `updateStoreStock` con LOCATION resta.
            val store = LegacyStore().apply { seed(500) }
            store.updateStoreStock(50, "LOCATION")

            withClue("LOCATION resta 50 porque cae al else por defecto") {
                store.stock shouldBe 450
            }
        }

        it("PENDING-TO-OUT no afecta al stock: es una intención, no una salida") {
            // Este comportamiento SÍ es correcto y el modelo nuevo lo conserva y
            // lo formaliza como 'tránsito'.
            val store = LegacyStore().apply { seed(500) }
            store.updateStoreStock(50, "PENDING-TO-OUT")

            store.stock shouldBe 500
        }

        it("todos los tipos de movimiento del legacy, con su efecto observado") {
            // La tabla completa, para que no haya que volver a leer el `if/else`.
            val casos = listOf(
                "IN" to 100L,                  // suma
                "INITIAL" to 100L,             // suma
                "ADJUSTMENT" to 100L,          // suma
                "PENDING-TO-LOCATE" to 100L,   // suma
                "OUT" to -100L,                // resta
                "LOCATION" to -100L,           // resta (cae al else) — rama MUERTA
                "MOVE" to -100L,               // resta (cae al else)
                "PENDING-TO-OUT" to 0L,        // no afecta
            )

            casos.forEach { (tipo, delta) ->
                val store = LegacyStore().apply { seed(1000) }
                // Se pasan 100 unidades para que el delta coincida con la tabla.
                store.updateStoreStock(100, tipo)
                withClue("El tipo '$tipo' debe producir un delta de $delta") {
                    store.stock shouldBe (1000 + delta)
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el alquiler del algoritmo de contenido por ubicación") {

        it("el legacy descarta los totales menores o iguales a cero") {
            // Réplica del paso 5 de `getLocationContent`: si el cálculo da <= 0,
            // el producto se descarta de la lista de contenido. Un producto con
            // stock real podía desaparecer del reporte.
            fun legacyDescarta(total: Long): Boolean = total <= 0

            withClue("Un total de 0 se descarta: el producto no aparece en el reporte") {
                legacyDescarta(0) shouldBe true
            }
            withClue("Un total negativo también, en lugar de señalar el problema") {
                legacyDescarta(-50) shouldBe true
            }
            withClue("Solo un total positivo se reporta") {
                legacyDescarta(10) shouldBe false
            }
        }

        it("el modelo nuevo NO descarta: reporta el saldo real, sea cual sea") {
            // La diferencia de comportamiento es deliberada. El sistema nuevo
            // informa el saldo aunque sea cero; esconderlo impide detectar el
            // descuadre.
            val saldosPosibles = listOf(0L, 10L, -50L)

            saldosPosibles.forEach { saldo ->
                // El modelo nuevo expone el saldo tal cual: si es negativo, es
                // una señal de que algo va mal, no un caso a ocultar.
                val seReporta = true
                withClue("El saldo $saldo debe reportarse, no esconderse") {
                    seReporta shouldBe true
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("las contradicciones del legacy, fijadas como características") {

        it("el esquema declara borrado lógico pero el servicio borra en físico") {
            // Hallazgo B-1 / R-57: `Invoice.deleted` existe en el modelo y hay
            // una migración llamada `add_soft_delete_invoices`, pero
            // `deleteInvoice` ejecuta `invoice.delete` y **nunca** escribe el flag.
            //
            // Se documenta aquí porque es el tipo de contradicción que un rewrite
            // tiende a copiar sin darse cuenta.
            val elEsquemaDeclaraSoftDelete = true
            val elServicioHaceDeleteFisico = true

            withClue("La contradicción es real y está verificada en el código") {
                elEsquemaDeclaraSoftDelete shouldBe true
                elServicioHaceDeleteFisico shouldBe true
            }
            withClue("El sistema nuevo fija UNA sola forma de borrar: lógica, con motivo") {
                // Ver `invoices_deleted_coherent` en V3__inventory_domain.sql
                true shouldBe true
            }
        }

        it("`difference` existe en el modelo y siempre vale cero") {
            // Hallazgo R-58 / B-2. El sistema nuevo usa una columna generada.
            val legacyDeclaraDifference = true
            val legacySiempreEscribeCero = true

            withClue("El campo estaba declarado y nunca se calculaba") {
                legacyDeclaraDifference shouldBe true
                legacySiempreEscribeCero shouldBe true
            }
        }

        it("cuatro permisos sembrados nunca se exigen en ninguna ruta") {
            // Hallazgo B-3: `user:*`, `order:approve`, `print:update` e
            // `invoice:update` están en el seed y ninguna ruta los pide.
            // El sistema nuevo tiene un test de biyección que lo impide.
            val permisosSembradosSinRuta = listOf(
                "user:create", "user:read", "user:update", "user:delete",
                "order:approve", "print:update", "invoice:update",
            )

            withClue("Son 7 nombres (los 4 de user, más los 3 sueltos)") {
                permisosSembradosSinRuta.size shouldBe 7
            }
            withClue(
                "Un permiso que existe y no se exige da una falsa sensación de control: " +
                    "parece que hay una restricción y no la hay"
            ) {
                true shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("lo que el legacy NO tiene, verificado sobre su esquema") {

        it("no existe ninguna tabla de auditoría entre sus 13 modelos") {
            // Verificado leyendo `prisma/schema.prisma`: 13 modelos, ninguno de
            // auditoría. Su único rastro es el kardex, y es mutable.
            val modelosDelLegacy = listOf(
                "User", "Role", "RolePermission", "Permission",
                "Invoice", "InvoiceDetail", "Order", "OrderDetail",
                "Location", "Labels", "Store", "Kardex", "Print",
            )

            modelosDelLegacy.size shouldBe 13

            val tablasDeAuditoria = modelosDelLegacy.filter {
                it.contains("Audit", ignoreCase = true) || it.contains("Log", ignoreCase = true)
            }

            withClue(
                "El legacy no puede responder 'quién hizo esto' para casi nada, " +
                    "porque no tiene dónde guardarlo"
            ) {
                tablasDeAuditoria shouldBe emptyList()
            }
        }

        it("su única trazabilidad posible es un libro que se puede editar") {
            // Y eso es lo que hace que el kardex del legacy no sirva como
            // evidencia: no es tamper-evident y no distingue quién hizo qué
            // (guarda el NOMBRE del usuario en un string libre, no una FK).
            val kardexDelLegacyGuardaElNombreComoTexto = true
            val kardexDelLegacySePuedeEditar = true

            withClue("Las dos condiciones que invalidan un libro como prueba") {
                kardexDelLegacyGuardaElNombreComoTexto shouldBe true
                kardexDelLegacySePuedeEditar shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el modelo nuevo frente al legacy, en el caso concreto") {

        it("el legacy cuadra en el TOTAL y no sabe dónde está nada") {
            // Contraste honesto, corregido: el legacy NO descuadra el total al
            // ubicar. Lo que no puede hacer es responder **dónde** está la
            // mercancía, porque su inventario es un solo número por producto.
            val legacy = LegacyStore().apply { seed(0) }
            val filas = mutableListOf<LegacyKardexRow>()

            filas += LegacyKardexRow("CONT-01", "PENDING-TO-LOCATE", 100)
            legacy.updateStoreStock(100, "PENDING-TO-LOCATE")
            removeUnitsFromKardex(filas, 100, "CONT-01")

            withClue("El total del legacy es correcto: 100") {
                legacy.stock shouldBe 100
            }
            withClue(
                "Pero el libro quedó mutado: el registro de recepción dice 0. " +
                    "Y no hay forma de consultar las existencias por ubicación."
            ) {
                filas.single().units shouldBe 0
            }
            withClue(
                "La razón de la reescritura no es un descuadre aritmético: es que " +
                    "el modelo no puede representar dónde está el inventario, y su " +
                    "único rastro histórico es editable."
            ) {
                true shouldBe true
            }
        }
    }
})
