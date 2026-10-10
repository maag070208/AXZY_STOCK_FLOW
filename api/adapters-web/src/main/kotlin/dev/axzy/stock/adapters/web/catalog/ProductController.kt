package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.catalog.CreateProduct
import dev.axzy.stock.application.catalog.DeactivateProduct
import dev.axzy.stock.application.catalog.GetProduct
import dev.axzy.stock.application.catalog.ImportCatalog
import dev.axzy.stock.application.catalog.ListProducts
import dev.axzy.stock.application.catalog.ProductFilter
import dev.axzy.stock.application.catalog.ProductInput
import dev.axzy.stock.application.catalog.UpdateProduct
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.common.asRight
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Las rutas del catálogo de productos (docs/api/catalogo.md).
 *
 * El controlador **no decide permisos**: toma al actor, arma la petición y llama
 * al caso de uso, que es quien autoriza y audita.
 */
@RestController
@RequestMapping("/v1/products")
class ProductController(
    private val listProducts: ListProducts,
    private val getProduct: GetProduct,
    private val createProduct: CreateProduct,
    private val updateProduct: UpdateProduct,
    private val deactivateProduct: DeactivateProduct,
    private val importCatalog: ImportCatalog,
    private val upload: CatalogUpload,
) {

    @GetMapping
    @RequiresPermission("product.view")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, sort, SORTS, SortSpec("productCode"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        return listProducts.execute(actor, ProductFilter(q, active), request, origin)
            .toResponse { it.toDto { view -> view.toDto() } }
    }

    @GetMapping("/{id}")
    @RequiresPermission("product.view")
    fun detail(@PathVariable id: Long, actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        getProduct.execute(actor, id, origin).toResponse { it.toDto() }

    @PostMapping
    @RequiresPermission("product.create")
    fun create(@RequestBody body: ProductRequest, actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        createProduct.execute(actor, body.toInput(), origin)
            .toResponse(HttpStatus.CREATED) { it.toDto() }

    @PutMapping("/{id}")
    @RequiresPermission("product.edit")
    fun update(
        @PathVariable id: Long,
        @RequestBody body: ProductRequest,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = updateProduct.execute(actor, id, body.toInput(), origin).toResponse { it.toDto() }

    /**
     * Baja **lógica**. El motivo se admite en el cuerpo o en la query porque hay
     * clientes y proxies que descartan el cuerpo de un `DELETE`.
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("product.delete")
    fun deactivate(
        @PathVariable id: Long,
        @RequestBody(required = false) body: ReasonRequest?,
        @RequestParam(required = false) reason: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> =
        deactivateProduct.execute(actor, id, body?.reason ?: reason, origin).toResponse { it.toDto() }

    /**
     * Importa el archivo inicial. **Siempre `200`**, incluso con `dryRun=false`: no
     * crea un recurso, opera sobre una colección y devuelve un informe.
     */
    @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @RequiresPermission("product.import")
    fun import(
        @RequestPart("file", required = false) file: MultipartFile?,
        @RequestParam(required = false) dryRun: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val effective = when (val parsed = parseDryRun(dryRun)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        return upload.read(file)
            .flatMap { rows -> importCatalog.execute(actor, rows, effective, origin) }
            .toResponse { it.toDto() }
    }

    private companion object {
        val SORTS = setOf("productCode", "name", "createdAt")
    }
}

private fun ProductRequest.toInput() = ProductInput(productCode, name, description, unitMeasure)

/**
 * `dryRun` por defecto es **`true`**: si el cliente olvida el campo, se simula. El
 * olvido inverso —escribir el catálogo entero cuando se quería simular— no tiene
 * vuelta atrás.
 */
private fun parseDryRun(raw: String?): Outcome<Boolean> = when (raw?.trim()?.lowercase()) {
    null, "" -> true.asRight()
    "true" -> true.asRight()
    "false" -> false.asRight()
    else -> failure(
        FailureKind.INVALID_REQUEST, "INVALID_DRY_RUN",
        "dryRun debe ser true o false.",
        mapOf("dryRun" to raw),
    )
}
