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
 * The warehouses (docs/api/catalogo.md).
 *
 * The list **is not paginated**: it is a closed and tiny catalog that the interface
 * needs whole to fill a dropdown. Paginating it would force requesting all
 * the pages before a `select` can be drawn.
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

/** No pagination envelope, but with `items`: the client already knows how to read that shape. */
data class WarehouseListDto(val items: List<WarehouseDto>)

private fun WarehouseRequest.toInput() = WarehouseInput(code, name, address, isDefault)
