package dev.axzy.stock.application.reports

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.inventory.KardexLine
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.common.asRight

/** The formats the contract admits for the ledger export. */
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
 * An export plan: the filters, the format and the row chunks.
 *
 * The chunks are a **lazy** `Sequence` on purpose: the first query goes out
 * when the adapter starts writing the response, and each next chunk
 * when it is needed. That way the file of a year's ledger is not loaded whole into
 * memory, and the budget is one query per chunk, not one per row (R-P2).
 */
data class KardexExport(
    val format: ExportFormat,
    val chunks: Sequence<List<KardexLine>>,
) {
    companion object {
        /** How many rows each query brings. */
        const val CHUNK_SIZE = 1_000
    }
}

/**
 * Exports the ledger with **the same filters as the listing**: if the user sees 200
 * filtered rows and exports, they have to receive those 200. A file with another filter
 * forces reconciling two views of the same data.
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
                        // An incomplete chunk is the last one: requesting another would give an empty list
                        // and one query too many.
                        if (chunk.size < KardexExport.CHUNK_SIZE) break
                        offset += chunk.size
                    }
                },
            ).asRight()
        }
}
