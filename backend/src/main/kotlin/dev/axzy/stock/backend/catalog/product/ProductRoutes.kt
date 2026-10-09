package dev.axzy.stock.backend.catalog.product

import dev.axzy.stock.backend.catalog.CatalogImportService
import dev.axzy.stock.backend.catalog.ImportReportResponse
import dev.axzy.stock.backend.catalog.JsonQuantity
import dev.axzy.stock.backend.catalog.ProductCreateRequest
import dev.axzy.stock.backend.catalog.ProductUpdateRequest
import dev.axzy.stock.backend.catalog.UnitOfMeasureRepository
import dev.axzy.stock.backend.catalog.aDto
import dev.axzy.stock.backend.catalog.aResponse
import dev.axzy.stock.backend.catalog.filterBoolean
import dev.axzy.stock.backend.catalog.location.aDto
import dev.axzy.stock.backend.catalog.page
import dev.axzy.stock.backend.catalog.productCode
import dev.axzy.stock.backend.catalog.productNotFound
import dev.axzy.stock.backend.catalog.readImportFile
import dev.axzy.stock.backend.catalog.reasonRequired
import dev.axzy.stock.backend.catalog.requirePermission
import dev.axzy.stock.backend.catalog.routeId
import dev.axzy.stock.backend.catalog.textRequired
import dev.axzy.stock.backend.catalog.translatingDbErrors
import dev.axzy.stock.backend.catalog.unitUnknown
import dev.axzy.stock.backend.catalog.warehouse.aDto
import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.domain.value.ProductCode
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import javax.sql.DataSource

/** Rutas de productos. */
internal fun Route.productRoutes(
    products: ProductRepository,
    units: UnitOfMeasureRepository,
    importService: CatalogImportService,
    verifier: PermissionChecker,
) = route("/v1/products") {

    get {
        call.requirePermission(verifier, "product.view")

        val page = call.page(ProductRepository.SORTABLE_FIELDS, ProductRepository.DEFAULT_ORDER)
        val search = call.request.queryParameters["q"]
        val active = call.filterBoolean("active")

        val result = products.list(page, search, active)
        call.respond(HttpStatusCode.OK, result.aResponse { it.aDto() })
    }

    post {
        call.requirePermission(verifier, "product.create")

        val request = call.receive<ProductCreateRequest>()
        val code = productCode(request.productCode)
        val name = textRequired(request.name, "name", 120)
        val unit = units.byCode(request.unitMeasure)
            ?: throw unitUnknown(request.unitMeasure)

        // El prechequeo da el 409 legible en el caso normal; la garantía la
        // tiene el índice único de la base, que es el que no tiene carrera.
        if (products.existsCode(code)) {
            throw ConflictException(
                code = "DUPLICATE_PRODUCT_CODE",
                message = "Ya existe un producto con el código '$code'.",
            )
        }

        val created = translatingDbErrors {
            products.create(
                data = NewProduct(
                    code = code,
                    name = name,
                    description = request.description?.trim()?.takeIf { it.isNotEmpty() },
                    unitMeasureCode = unit.code,
                ),
                unitOfMeasureId = unit.id,
            )
        }

        call.respond(HttpStatusCode.Created, created.aDto())
    }

    // La ruta constante va antes que la de parámetro. Ktor resuelve por
    // prioridad, pero declararla primero deja claro que `/import` no es un id.
    post("/import") {
        call.requirePermission(verifier, "product.import")

        val (content, dryRun) = call.readImportFile()
        val report: ImportReportResponse = importService.importCatalog(content, dryRun)

        // 200 también en el import real: el cuerpo es el informe, y el informe
        // de una simulación y el de una carga difieren en `dryRun` y `persisted`.
        call.respond(HttpStatusCode.OK, report)
    }

    get("/{id}") {
        call.requirePermission(verifier, "product.view")

        val id = call.routeId()
        val product = products.byId(id) ?: throw productNotFound(id)
        call.respond(HttpStatusCode.OK, product.aDto())
    }

    put("/{id}") {
        call.requirePermission(verifier, "product.edit")

        val id = call.routeId()
        val current = products.byId(id) ?: throw productNotFound(id)
        val request = call.receive<ProductUpdateRequest>()

        // Tolerar el código igual permite el ida y vuelta de un cliente que
        // recibe el objeto del GET y lo devuelve modificado. Cambiarlo se
        // rechaza: está impreso en las etiquetas y en los estantes.
        request.productCode?.let { sent ->
            if (sent != current.productCode) {
                throw UnprocessableException(
                    code = "PRODUCT_CODE_IMMUTABLE",
                    message = "El código de producto no se puede cambiar ('${current.productCode}' → " +
                        "'$sent'): es el código de barras impreso en las etiquetas y en los " +
                        "estantes, y cambiarlo obliga a reetiquetar el almacén. Existe una acción " +
                        "específica para reasignar códigos.",
                    details = mapOf("productCode" to sent),
                )
            }
        }

        val name = textRequired(request.name, "name", 120)
        val unit = units.byCode(request.unitMeasure)
            ?: throw unitUnknown(request.unitMeasure)

        val updated = products.update(
            id = id,
            changes = ProductChanges(
                name = name,
                // En un PUT el cuerpo reemplaza al recurso: si no viene
                // descripción, se guarda sin descripción. Es la única forma de
                // borrarla, y omitirla no puede significar «déjala como está»
                // sin inventar un campo nuevo en el contrato.
                description = request.description?.trim()?.takeIf { it.isNotEmpty() },
                unitOfMeasure = unit.code,
            ),
            unitOfMeasureId = unit.id,
        ) ?: throw productNotFound(id)

        call.respond(HttpStatusCode.OK, updated.aDto())
    }

    delete("/{id}") {
        call.requirePermission(verifier, "product.delete")

        val id = call.routeId()
        // El motivo se exige ANTES de mirar el producto: es un requisito de la
        // petición, no del recurso, y el contrato lo pide siempre.
        val reason = call.reasonRequired()

        val product = products.byId(id) ?: throw productNotFound(id)

        if (!product.active) {
            throw ConflictException(
                code = "PRODUCT_ALREADY_INACTIVE",
                message = "El producto '$id' ya estaba desactivado. Volver a desactivarlo " +
                    "sobrescribiría el motivo del primer borrado y perdería la traza.",
            )
        }

        // Un producto con existencias no se desactiva: el stock quedaría en un
        // producto invisible para el listado y el descuadre aparecería en el
        // siguiente conteo. Primero se ajusta a cero (con motivo) o se traslada.
        if (product.stock.signum() != 0) {
            throw ConflictException(
                code = "PRODUCT_HAS_STOCK",
                message = "El producto '${product.productCode}' tiene existencias y no se " +
                    "puede desactivar. Ajusta o traslada el stock primero.",
                details = mapOf("stock" to JsonQuantity.fromDecimal(product.stock).asText()),
            )
        }

        if (!products.disable(id)) {
            // Carrera: otra petición lo desactivó entre la lectura y el UPDATE.
            throw ConflictException(
                code = "PRODUCT_ALREADY_INACTIVE",
                message = "El producto '$id' ya estaba desactivado.",
            )
        }

        // El motivo no tiene columna en `products` y el esquema no se toca: se
        // registra para que quede rastro del porqué. La traza con autor y fecha
        // corresponde a `audit_log`.
        logger.info(
            "Producto {} ({}) desactivado. Motivo declarado: {}",
            id,
            product.productCode,
            reason,
        )

        call.respond(HttpStatusCode.NoContent)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// UBICACIONES
// ═════════════════════════════════════════════════════════════════════════════
