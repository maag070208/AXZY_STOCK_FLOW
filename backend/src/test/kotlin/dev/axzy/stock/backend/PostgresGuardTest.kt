package dev.axzy.stock.backend

import dev.axzy.stock.backend.catalog.CatalogTestDb
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Canario del entorno: **sin PostgreSQL, la suite falla aquí y lo dice.**
 *
 * ── POR QUÉ EXISTE ─────────────────────────────────────────────────────────
 *
 * Los tests que necesitan la base comprueban la conexión y hacen `return` sin
 * ejecutar nada. Eso **no los salta: los aprueba**. Con la base caída, la suite
 * entera salía verde y un «0 fallos, 0 saltadas» significaba «no se probó nada»,
 * que es el autoengaño contra el que escribe `docs/pruebas/verificacion.md`.
 *
 * Probado el 2026-10-09: se intentó primero con `@EnabledIf` y luego abortando
 * el spec con `TestAbortedException`. Ninguno de los dos vale:
 *
 *   - `@EnabledIf` **quita los tests del informe** (292 → 128, `saltadas = 0`):
 *     desaparecen sin decirlo, que es peor que aprobarlos.
 *   - `TestAbortedException` cuenta saltos **y** deja el spec en rojo con un
 *     «Before Spec Error».
 *
 * Este canario es lo único que hace visible la falta de base sin tocar los trece
 * specs ni mentir en el recuento: **un fallo con el motivo y la instrucción**.
 */
class PostgresGuardTest : FunSpec({

    test("PostgreSQL responde: sin base, los tests que la necesitan no se ejecutan y se cuentan como aprobados") {
        val problema = CatalogTestDb.connectionProblem()

        withClue(
            """
            |
            |  PostgreSQL no responde${if (problema != null) ": $problema" else ""}
            |
            |  Sin base, los tests que la necesitan DEVUELVEN SIN EJECUTARSE y el motor
            |  los cuenta como APROBADOS: un «0 fallos, 0 saltadas» no prueba nada.
            |  Levántala con:  colima start && cd infra && docker compose up -d
            |
            """.trimMargin()
        ) {
            problema shouldBe null
        }
    }
})
