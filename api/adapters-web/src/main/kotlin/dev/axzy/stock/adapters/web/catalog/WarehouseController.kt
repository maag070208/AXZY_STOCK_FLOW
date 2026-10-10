package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.catalog.CreateWarehouse
import dev.axzy.stock.application.catalog.ListWarehouses
import dev.axzy.stock.application.catalog.UpdateWarehouse
import dev.axzy.stock.application.catalog.WarehouseInput
import dev.axzy.stock.application.shared.RequestOrigin
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Las bodegas (docs/api/catalogo.md).
 *
 * El listado **no se pagina**: es un catálogo cerrado y diminuto que la interfaz
 * necesita completo para poblar un desplegable. Paginarlo obligaría a pedir todas
 * las páginas antes de poder dibujar un `select`.
 */
@RestController
@RequestMapping("/v1/warehouses")
class WarehouseController(
    private val listWarehouses: ListWarehouses,
    private val createWarehouse: CreateWarehouse,
    private val updateWarehouse: UpdateWarehouse,
) {

    @GetMapping
    @RequiresPermission("warehouse.view")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) active: Boolean?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> =
        listWarehouses.execute(actor, q, active, origin).toResponse { items -> WarehouseListDto(items.map { it.toDto() }) }

    @PostMapping
    @RequiresPermission("warehouse.create")
    fun create(@RequestBody body: WarehouseRequest, actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        createWarehouse.execute(actor, body.toInput(), origin)
            .toResponse(HttpStatus.CREATED) { it.toDto() }

    @PutMapping("/{id}")
    @RequiresPermission("warehouse.edit")
    fun update(
        @PathVariable id: Long,
        @RequestBody body: WarehouseRequest,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = updateWarehouse.execute(actor, id, body.toInput(), origin).toResponse { it.toDto() }
}

/** Sin sobre de paginación, pero con `items`: el cliente ya sabe leer esa forma. */
data class WarehouseListDto(val items: List<WarehouseDto>)

private fun WarehouseRequest.toInput() = WarehouseInput(code, name, address, isDefault)
