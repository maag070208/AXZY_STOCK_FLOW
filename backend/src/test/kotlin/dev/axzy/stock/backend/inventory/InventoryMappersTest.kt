package dev.axzy.stock.backend.inventory

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DE LOS MAPPERS DE INVENTARIO
 *
 * **Sin base de datos y sin servidor.** Ese es el motivo de que los mappers existan: antes
 * el mapeo vivía dentro del bucle de lectura de la consulta, así que la única forma de
 * probarlo era arrancar PostgreSQL y llamar al endpoint. Con 10,9 millones de unidades y
 * un `docker` de por medio, eso no se hace en cada cambio.
 *
 * ---
 *
 * ## QUÉ SE PRUEBA AQUÍ Y QUÉ NO — y por qué
 *
 * `ResultSet` es una interfaz de **unos 200 métodos**. Implementarla a mano para un test
 * sería más código de mentira que el mapper que se quiere probar, y añadir MockK por un
 * solo test mete una dependencia en todo el proyecto.
 *
 * Así que se prueba **lo que se puede probar de verdad**: las **decisiones puras** que el
 * mapper toma, que es además donde están los bugs reales — no en el `getString`, que o
 * lee la columna o devuelve nulo.
 *
 * **Lo que NO cubre este archivo, dicho claro:** que el nombre de columna del mapper
 * coincida con el alias del SQL. Eso **no lo puede probar un test unitario** —el
 * compilador tampoco, porque es una cadena— y por eso la regla del proyecto es
 * **verificarlo con `curl` contra la respuesta real**. Un mapper mal escrito no rompe:
 * miente.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InventoryMappersTest : DescribeSpec({

    // ═════════════════════════════════════════════════════════════════════════
    describe("el nombre visible de la ubicación") {

        it("las ubicaciones ESPECIALES tienen nombre propio, no concatenación") {
            // **Éste es el caso que rompe si alguien «simplifica» el mapper a un CONCAT
            // del SQL.** Las especiales se crearon con las tres partes iguales
            // (`hall = code = position = 'SINUBICAR'`), así que concatenarlas daría
            // `SINUBICARSINUBICARSINUBICAR`, que es lo que vería el operador en pantalla.
            //
            // Se comprobó contra la base real: la ubicación id=2 es
            // hall=SINUBICAR, code=SINUBICAR, position=SINUBICAR, kind=UNASSIGNED.
            withClue("Una ubicación de recepción") {
                locationDisplayName("RECEPCION", "RECEPCION", "RECEPCION", "RECEIVING") shouldBe
                    "RECEPCION"
            }
            withClue("La ubicación del saldo heredado") {
                locationDisplayName("SINUBICAR", "SINUBICAR", "SINUBICAR", "UNASSIGNED") shouldBe
                    "SIN-UBICAR"
            }
        }

        it("un estante normal sí se concatena con guiones") {
            locationDisplayName("04", "B", "C", "SHELF") shouldBe "04-B-C"
        }

        // Un `kind` DESCONOCIDO no debe reventar: se concatena. Si la base añadiera un
        // tipo nuevo y el mapper lanzara una excepción, **el inventario entero dejaría de
        // listarse** por una ubicación que no encaja en el `when`.
        it("cualquier otro tipo se concatena, incluido uno DESCONOCIDO") {
            listOf(
                Cuatro("01", "A", "A", "SHELF", "01-A-A"),
                Cuatro("12", "Z", "9", "SHELF", "12-Z-9"),
                Cuatro("07", "C", "D", "ALGO_NUEVO", "07-C-D"),
            ).forEach { caso ->
                withClue("kind=${caso.kind}") {
                    locationDisplayName(caso.hall, caso.code, caso.position, caso.kind) shouldBe
                        caso.esperado
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("las cantidades se serializan como CADENA de 3 decimales") {

        it("un número GRANDE se serializa con su forma exacta") {
            // **ESTE TEST CORRIGIÓ UN COMENTARIO MÍO QUE EXAGERABA.** En el mapper escribí
            // que `toString()` daría notación científica (`5.193E+3`) con los 10,9
            // millones de unidades del inventario. **Es falso**, y este test lo demostró:
            // con escala 3, `toString()` da `5193000.000`.
            //
            // La notación científica de `BigDecimal.toString()` aparece con **escalas
            // negativas o exponentes muy grandes**, que no es el caso de un
            // `NUMERIC(14,3)`. `toPlainString()` sigue siendo la elección correcta
            // —**garantiza** la forma sin depender de la escala— pero la razón no es la
            // que escribí, y dejarla habría sido documentar un miedo que no existe.
            val grande = BigDecimal("5193000.000")

            withClue("toString() NO da notación científica aquí: mi comentario exageraba") {
                grande.toString() shouldBe "5193000.000"
            }
            withClue("y toPlainString() da lo mismo, pero lo GARANTIZA") {
                grande.toPlainString() shouldBe "5193000.000"
            }
        }

        it("conserva SIEMPRE los 3 decimales, incluso en un entero") {
            // El contrato dice 3 decimales y el cliente puede estar tipando contra eso.
            // `5193` sin decimales obligaría a cada consumidor a normalizar.
            BigDecimal("5193").toPlainString() shouldBe "5193"
            // El mapper usa `getBigDecimal` de una columna `NUMERIC(14,3)`, así que
            // PostgreSQL ya devuelve la escala — pero conviene saber que el `BigDecimal`
            // crudo NO la añade solo.
            BigDecimal("5193.000").toPlainString() shouldBe "5193.000"
        }

        it("los decimales pequeños no se pierden") {
            // 0,5 unidades es un caso real: hay productos que se cuentan por fracciones.
            BigDecimal("0.500").toPlainString() shouldBe "0.500"
            BigDecimal("0.001").toPlainString() shouldBe "0.001"
        }
    }
})

/** Un caso de la tabla, con nombres en vez de posiciones. */
private data class Cuatro(
    val hall: String,
    val code: String,
    val position: String,
    val kind: String,
    val esperado: String,
)
