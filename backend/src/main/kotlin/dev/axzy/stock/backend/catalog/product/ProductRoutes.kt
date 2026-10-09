package dev.axzy.stock.backend.catalog.product

import dev.axzy.stock.backend.catalog.CatalogImportService
import dev.axzy.stock.backend.catalog.ImportReportResponse
import dev.axzy.stock.backend.catalog.ProductCreateRequest
import dev.axzy.stock.backend.catalog.ProductUpdateRequest
import dev.axzy.stock.backend.catalog.aResponse
import dev.axzy.stock.backend.catalog.filterBoolean
import dev.axzy.stock.backend.catalog.page
import dev.axzy.stock.backend.catalog.readImportFile
import dev.axzy.stock.backend.catalog.reasonRequired
import dev.axzy.stock.backend.catalog.requirePermission
import dev.axzy.stock.backend.catalog.routeId
import dev.axzy.stock.backend.catalog.aDto
import dev.axzy.stock.backend.security.PermissionChecker
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Rutas de productos. Traducen HTTP a [ProductService]: los permisos, los parámetros y el
 * formato de respuesta viven aquí; las reglas, en el servicio.
 */
internal fun Route.productRoutes(
    service: ProductService,
    importService: CatalogImportService,
    verifier: PermissionChecker,
) = route("/v1/products") {

    get {
        call.requirePermission(verifier, "product.view")

        val page = call.page(ProductRepository.SORTABLE_FIELDS, ProductRepository.DEFAULT_ORDER)
        val search = call.request.queryParameters["q"]
        val active = call.filterBoolean("active")

        val result = service.list(page, search, active)
        call.respond(HttpStatusCode.OK, result.aResponse { it.aDto() })
    }

    post {
        call.requirePermission(verifier, "product.create")

        val request = call.receive<ProductCreateRequest>()
        val created = service.create(
            rawCode = request.productCode,
            name = request.name,
            description = request.description,
            unitMeasure = request.unitMeasure,
        )

        call.respond(HttpStatusCode.Created, created.aDto())
    }

    // La ruta constante va antes que la de parámetro: `/import` no es un id.
    post("/import") {
        call.requirePermission(verifier, "product.import")

        val (content, dryRun) = call.readImportFile()
        val report: ImportReportResponse = importService.importCatalog(content, dryRun)

        // 200 también en el import real: el cuerpo es el informe.
        call.respond(HttpStatusCode.OK, report)
    }

    get("/{id}") {
        call.requirePermission(verifier, "product.view")

        val product = service.byId(call.routeId())
        call.respond(HttpStatusCode.OK, product.aDto())
    }

    put("/{id}") {
        call.requirePermission(verifier, "product.edit")

        val id = call.routeId()
        // Primero el 404: un id inexistente no debe responder 400 por un cuerpo inválido.
        service.byId(id)
        val request = call.receive<ProductUpdateRequest>()

        val updated = service.update(
            id = id,
            sentCode = request.productCode,
            name = request.name,
            description = request.description,
            unitMeasure = request.unitMeasure,
        )

        call.respond(HttpStatusCode.OK, updated.aDto())
    }

    delete("/{id}") {
        call.requirePermission(verifier, "product.delete")

        val id = call.routeId()
        // El motivo se exige ANTES de mirar el producto: es un requisito de la petición.
        val reason = call.reasonRequired()

        service.disable(id, reason)
        call.respond(HttpStatusCode.NoContent)
    }
}
