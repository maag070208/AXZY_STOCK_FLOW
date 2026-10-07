package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.domain.catalog.CatalogImporter
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * IMPORTACIÓN DEL CATÁLOGO
 *
 * Reutiliza **sin modificarlo** el `CatalogImporter` del dominio, que es donde
 * están las reglas estrictas y el cuadre contra el archivo real (1.398
 * productos, 10.914.130 unidades, 334 en cero). Aquí solo se aporta lo que el
 * dominio no puede saber porque es Kotlin puro:
 *
 *  - **qué unidades de medida existen**, que es un dato de la base;
 *  - **cómo se lee el archivo** (CSV o XLSX), que es E/S;
 *  - **la transacción**, que es lo que hace que el import sea todo o nada.
 *
 * ## Todo o nada
 *
 * El import real corre dentro de **una sola transacción**. Si una fila falla al
 * persistir —por ejemplo, un `external_id` que ya existe en otro producto— no
 * queda nada escrito. Un import a medias con 1.398 productos es peor que un
 * import fallido: deja el catálogo en un estado que nadie sabe describir y sin
 * forma de saber por dónde iba.
 *
 * Y si alguna fila viene mal, **tampoco se escribe nada**: se rechaza el import
 * entero con un 422 y el usuario corrige el archivo con la ayuda del informe de
 * la simulación. Persistir las buenas «de momento» es precisamente el import a
 * medias que se quiere evitar.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class CatalogImportService(
    private val db: Database,
    private val productos: ProductRepository,
    private val unidades: UnitOfMeasureRepository,
) {

    /**
     * @param contenido el archivo tal cual llegó.
     * @param dryRun `true` (por defecto en la ruta) simula y **no escribe nada**.
     */
    fun importar(contenido: ByteArray, dryRun: Boolean): ImportReportResponse {
        // Las unidades válidas salen de la BASE, no de una lista en el código:
        // si mañana se da de alta 'KG' en el catálogo, el import la acepta sin
        // tocar el importador.
        val unidadPorCodigo = unidades.mapaCodigoId()
        val importador = CatalogImporter(knownUnitMeasures = unidadPorCodigo.keys)

        val filas = CatalogFileReader.leer(contenido, importador)
        val informe = importador.import(filas, dryRun = dryRun)

        // El dominio valida el código, el nombre, la unidad y el stock; lo que
        // no puede validar es que el id del ERP quepa en la columna, porque eso
        // es del esquema. `external_id` es BIGINT: un valor no numérico no se
        // puede guardar, y guardarlo como nulo en silencio perdería la
        // referencia cruzada sin que nadie se entere.
        informe.accepted.firstOrNull { fila ->
            // El `let` evita el smart cast sobre una propiedad de otro módulo.
            val externo = fila.externalId
            externo != null && externo.toLongOrNull() == null
        }?.let { fila ->
            throw UnprocessableException(
                code = "IMPORT_INVALID_EXTERNAL_ID",
                message = "El identificador del ERP de la fila ${fila.rowNumber} " +
                    "('${fila.externalId}') no es un número. La columna `external_id` es " +
                    "entera, y guardarla vacía perdería la referencia cruzada en silencio.",
                details = mapOf(
                    "rowNumber" to fila.rowNumber.toString(),
                    "externalId" to fila.externalId.orEmpty(),
                ),
            )
        }

        // ── Simulación: se devuelve el informe y no se toca la base ─────────
        if (dryRun) return informe.aDto(persistidos = 0)

        // ── Import real con filas rechazadas: no se escribe NADA ────────────
        if (informe.hasRejections) {
            val primera = informe.rejected.first()
            throw UnprocessableException(
                code = "IMPORT_HAS_REJECTIONS",
                message = "El archivo tiene ${informe.rejectedCount} fila(s) rechazada(s) " +
                    "de ${informe.totalRows}, así que no se importó ninguna. " +
                    "Vuelve a ejecutar el import con dryRun=true para ver el detalle por fila, " +
                    "corrige el archivo y repítelo. Un import parcial dejaría el catálogo " +
                    "a medias sin forma de saber por dónde iba.",
                details = mapOf(
                    "rejected" to informe.rejectedCount.toString(),
                    "firstRejection" to "fila ${primera.rowNumber}: ${primera.reason}",
                ),
            )
        }

        val filasAPersistir = informe.accepted.map { fila ->
            FilaDeImportacion(
                codigo = fila.code.value,
                nombre = fila.name,
                idExterno = fila.externalId?.toLongOrNull(),
                unidadDeMedida = fila.unitMeasure,
            )
        }

        // ── UNA transacción para las 1.398 filas ────────────────────────────
        // La transacción envuelve al upsert completo. Cualquier fallo —una
        // violación de unicidad a mitad del lote, por ejemplo— deshace todo lo
        // anterior: el catálogo queda exactamente como estaba.
        val persistidos = traduciendoErroresDeBase {
            transaction(db) {
                productos.upsertMasivo(filasAPersistir, unidadPorCodigo)
            }
        }

        logger.info(
            "Import de catálogo aplicado: {} productos persistidos de {} filas, " +
                "{} unidades en el archivo",
            persistidos,
            informe.totalRows,
            informe.totalStock,
        )

        return informe.aDto(persistidos = persistidos)
    }
}
