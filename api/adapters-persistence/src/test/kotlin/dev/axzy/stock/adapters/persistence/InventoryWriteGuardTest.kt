package dev.axzy.stock.adapters.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * La regla del plan: **el saldo lo cambia la base con bloqueo, y solo por el motor**
 * (requisitos Q7 y R-P6, y el riesgo «dos rutas de escritura al inventario» de
 * PLAN-IMPLEMENTACION §6).
 *
 * Ninguna capa de adaptadores puede escribir a mano `stock_by_location`,
 * `product_stock` ni `kardex`: las tres las mueve `record_movement`, dentro de
 * su transacción y con el bloqueo que evita perder escrituras. Un `UPDATE` suelto
 * desde la API compilaría, pasaría las pruebas de felicidad y rompería la
 * concurrencia justo cuando el almacén tiene carga.
 *
 * **Por qué mira el código fuente y no la base.** Es una regla sobre cómo está
 * escrito el adaptador, y en SQL no hay forma de distinguir un `SELECT` legítimo de
 * un `UPDATE` por consultar el catálogo de la base. Se revisan los archivos
 * `.kt` de la capa: la lista es corta, la regla es clara y el fallo dice el archivo y
 * la línea.
 */
class InventoryWriteGuardTest {

    /** Las tres tablas que solo crecen o que solo mueve el motor. */
    private val protectedTables = listOf("stock_by_location", "product_stock", "kardex", "audit_log")

    private val persistenceRoot = File("src/main/kotlin/dev/axzy/stock/adapters/persistence")

    @Test
    fun `no adapter writes by hand the tables the engine moves`() {
        val offenders = mutableListOf<String>()

        persistenceRoot.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                // Se analiza el CÓDIGO, no la prosa: las líneas de un bloque de
                // comentario (`/* … */`, que empiezan por `*`) hablan de escrituras
                // sin serlo, y la primera versión de esta guardia acusaba a un
                // comentario que explicaba justo lo que prohíbe.
                val trimmed = line.trim()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                val code = line.substringBefore("//").lowercase()
                val writes = code.contains("update ") || code.contains("delete from ") || code.contains("insert into ")
                val touchesProtected = protectedTables.any { code.contains(it) }
                // El kardex sí se INSERTA, pero solo desde el motor: `record_movement`
                // es una función de la base, y este adaptador la llama con un SELECT.
                if (writes && touchesProtected) {
                    offenders += "${file.path}:${index + 1} → ${line.trim()}"
                }
            }
        }

        assertThat(offenders)
            .describedAs("una escritura directa al inventario tiene que salir del motor SQL, no de la API")
            .isEmpty()
    }

    @Test
    fun `the only one that applies movements is the ledger, and it does so through the engine function`() {
        val ledger = File(persistenceRoot, "inventory/JdbcStockLedger.kt").readText().lowercase()

        assertThat(ledger).contains("record_movement")
        // Sin `UPDATE` ni `INSERT` propios: el motor los hace dentro de su transacción.
        assertThat(ledger).doesNotContain("update stock_by_location")
        assertThat(ledger).doesNotContain("insert into stock_by_location")
        assertThat(ledger).doesNotContain("insert into kardex")
    }
}
