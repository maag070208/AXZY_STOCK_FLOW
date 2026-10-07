package dev.axzy.stock.backend.catalog

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REPOSITORIO DE UNIDADES DE MEDIDA
 *
 * El catálogo de unidades es **cerrado y vive en la base**: es la defensa
 * contra el clásico `KG` / `Kg` / `KGS` como tres unidades distintas, que es lo
 * que pasa cuando la unidad es un campo de texto libre.
 *
 * Por eso el importador del dominio se construye con las unidades que existen
 * de verdad en la base (ver `CatalogImportService`), no con una lista escrita
 * en el código: si una unidad se da de alta en el catálogo, el import la acepta
 * sin tocar el código.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class UnitOfMeasureRepository(private val db: Database) {

    /** Todas las unidades, ordenadas por código. */
    fun listar(): List<UnitOfMeasureRecord> = transaction(db) {
        UnitsOfMeasure
            .selectAll()
            .orderBy(UnitsOfMeasure.code)
            .map { fila ->
                UnitOfMeasureRecord(
                    id = fila[UnitsOfMeasure.id],
                    code = fila[UnitsOfMeasure.code],
                    name = fila[UnitsOfMeasure.name],
                    allowsFraction = fila[UnitsOfMeasure.allowsFraction],
                )
            }
    }

    /** Mapa `código → id`, que es lo que necesita el import masivo. */
    fun mapaCodigoId(): Map<String, Long> = listar().associate { it.code to it.id }

    /**
     * Busca por código, normalizando a mayúsculas.
     *
     * Un `pza` en minúsculas es inequívocamente la misma unidad: a diferencia
     * del código de producto —donde una minúscula indica que algo cambió en el
     * origen— aquí no hay ambigüedad posible.
     */
    fun porCodigo(codigo: String): UnitOfMeasureRecord? {
        val normalizado = codigo.trim().uppercase()
        return listar().firstOrNull { it.code == normalizado }
    }
}

/** Una unidad de medida del catálogo. */
data class UnitOfMeasureRecord(
    val id: Long,
    val code: String,
    val name: String,
    val allowsFraction: Boolean,
)
