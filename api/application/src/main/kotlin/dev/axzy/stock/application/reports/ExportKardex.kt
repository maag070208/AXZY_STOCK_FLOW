package dev.axzy.stock.application.reports

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.inventory.KardexLine
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.common.asRight

/** Los formatos que el contrato admite para la exportación del kardex. */
enum class ExportFormat {
    CSV,
    XLSX,
    ;

    companion object {
        fun parse(raw: String?): ExportFormat? = when (raw?.trim()?.lowercase()) {
            "csv" -> CSV
            "xlsx" -> XLSX
            else -> null
        }
    }
}

/**
 * Un plan de exportación: los filtros, el formato y los trozos de filas.
 *
 * Los trozos son una `Sequence` **perezosa** a propósito: la primera consulta sale
 * cuando el adaptador empieza a escribir la respuesta, y cada trozo siguiente
 * cuando hace falta. Así el archivo de un kardex de un año no se carga entero en
 * memoria, y el presupuesto es una consulta por trozo, no una por fila (R-P2).
 */
data class KardexExport(
    val format: ExportFormat,
    val chunks: Sequence<List<KardexLine>>,
) {
    companion object {
        /** Cuántas filas trae cada consulta. */
        const val CHUNK_SIZE = 1_000
    }
}

/**
 * Exporta el kardex con **los mismos filtros que el listado**: si el usuario ve 200
 * filas filtradas y exporta, tiene que recibir esas 200. Un archivo con otro filtro
 * obliga a reconciliar dos vistas de los mismos datos.
 */
class ExportKardex(private val queries: ReportQueries, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        filter: KardexFilter,
        format: ExportFormat,
        origin: RequestOrigin,
    ): Outcome<KardexExport> =
        access.authorizeOr(actor, "report.export", origin) {
            KardexExport(
                format = format,
                chunks = sequence {
                    var offset = 0
                    while (true) {
                        val chunk = queries.kardexChunk(filter, offset, KardexExport.CHUNK_SIZE)
                        if (chunk.isEmpty()) break
                        yield(chunk)
                        // Un trozo incompleto es el último: pedir otro daría una lista
                        // vacía y una consulta de más.
                        if (chunk.size < KardexExport.CHUNK_SIZE) break
                        offset += chunk.size
                    }
                },
            ).asRight()
        }
}
