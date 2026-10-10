# Traslados
> **Referencia.** Traslados entre bodegas: creación, despacho, recepción,
> cancelación y mercancía en tránsito. Última verificación: 2026-10-09.

Implementado en
`backend/src/main/kotlin/dev/axzy/stock/backend/infrastructure/inbound/http/transfer/TransferRoutes.kt` y
`TransferUseCases.kt`, con los DTO en `TransferDtos.kt`. La paginación está en
[convenciones.md](convenciones.md); los códigos, en [errores.md](errores.md).

Estados: `DRAFT` → `DISPATCHED` → `IN_TRANSIT` → `RECEIVED`, con `CANCELLED`.

```json
{
  "id": 9, "code": "TR-2026-0001",
  "fromWarehouseId": 1, "fromWarehouseCode": "PRINCIPAL",
  "toWarehouseId": 2, "toWarehouseCode": "SUCURSAL",
  "status": "IN_TRANSIT",
  "lineCount": 3, "totalUnits": "150.000",
  "note": null,
  "dispatchedBy": 7, "dispatchedByName": "Juan Pérez", "dispatchedAt": "...",
  "receivedBy": null, "receivedByName": null, "receivedAt": null
}
```

## `GET /v1/transfers`

Permiso: `transfer.view`. Filtros: `status` y `warehouseId` (traslados que salen o
llegan de esa bodega). Paginación por `page` y `size`.

Solo la **cabecera**, con `lineCount` y `totalUnits` contados en la base. El
detalle trae las líneas: la pantalla de despacho no puede funcionar sin ellas.

## `POST /v1/transfers`

Permiso: `transfer.create`. **No mueve stock**: un borrador es una intención; la
mercancía sale de las ubicaciones al **despachar**, que es un acto con responsable.

```json
{
  "fromWarehouseId": 1,
  "toWarehouseId": 2,
  "note": null,
  "lines": [
    { "productCode": "ABRAA1B", "quantity": "100.000", "fromLocationId": null, "note": null }
  ]
}
```

`fromLocationId` es opcional: `null` significa la ubicación de recepción o la
mercancía sin ubicar.

**201** con el traslado en `DRAFT` y sus líneas. Errores: `422 SAME_WAREHOUSE` si
origen y destino son la misma bodega (la mercancía saldría de las ubicaciones y
entraría en tránsito hacia el mismo sitio, el total no cambiaría y ningún reporte
lo detectaría), `422 EMPTY_TRANSFER` sin líneas, `422 PRODUCT_NOT_FOUND`, `422
INVALID_QUANTITY`.

## `GET /v1/transfers/{id}`

Permiso: `transfer.view`. Devuelve el traslado **con sus líneas**.

```json
{
  "transfer": { "id": 9, "code": "TR-2026-0001", "status": "DRAFT", "...": "..." },
  "lines": [
    {
      "id": 21, "productId": 1, "productCode": "ABRAA1B",
      "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "quantity": "100.000",
      "fromLocationId": null, "fromLocationDisplayName": null, "note": null
    }
  ]
}
```

**404** con `code = TRANSFER_NOT_FOUND`; `400 INVALID_ID`.

## `POST /v1/transfers/{id}/dispatch`

Permiso: `transfer.dispatch`. **Las dos mitades del movimiento van en UNA
transacción**: resta de las ubicaciones de origen e inserta la fila de tránsito. El
total del producto **no cambia**, porque el tránsito se suma en el recálculo del
total.

**200** con el traslado despachado. `409 INVALID_TRANSITION` si ya estaba
despachado: despachar dos veces restaría dos veces del origen y el total BAJARÍA,
que parece una venta y no un error. `422 EMPTY_TRANSFER`, `404 TRANSFER_NOT_FOUND`.

## `POST /v1/transfers/{id}/receive`

Permiso: `transfer.receive`. Suma en la ubicación de recepción de la bodega de
destino y **cierra la fila de tránsito**. El orden importa: primero se suma y
después se cierra; al revés, un fallo entre las dos dejaría la mercancía fuera de
las dos sumas y el total bajaría.

**200** con el traslado recibido. `422 INVALID_TRANSITION` si nunca se despachó:
llegaría mercancía que nunca salió y el inventario de destino crecería sin que el
de origen bajara. `422 NO_RECEIVING_LOCATION` si la bodega de destino no tiene
ubicación de recepción. `409 NOTHING_IN_TRANSIT` si no hay mercancía en tránsito
que recibir. `404 TRANSFER_NOT_FOUND`.

## `POST /v1/transfers/{id}/cancel`

Permiso: `transfer.cancel`. Se permite desde `DRAFT` y desde `DISPATCHED`. Desde un
despachado, **la mercancía vuelve a su ubicación de origen en la misma
transacción**, para no dejarla en un limbo que ningún reporte explica.

**Desde `RECEIVED` no se puede:** lo recibido está en las ubicaciones de destino y
cancelarlo dejaría la mercancía contada en el sitio equivocado. Para deshacerlo se
hace un traslado de vuelta, que deja rastro.

**200** con el traslado cancelado. `422 INVALID_TRANSITION`, `404
TRANSFER_NOT_FOUND`.

## `GET /v1/transfers/in-transit`

Permiso: `transit.view`. Traslados despachados y no recibidos, **con su responsable
y los días transcurridos**. Devuelve un arreglo, sin sobre de paginación.

```json
[
  {
    "id": 9, "code": "TR-2026-0001",
    "fromWarehouseCode": "PRINCIPAL", "toWarehouseCode": "SUCURSAL",
    "status": "IN_TRANSIT",
    "lineCount": 3, "totalUnits": "150.000",
    "dispatchedAt": "2026-10-05T10:00:00Z",
    "daysInTransit": 4,
    "dispatchedBy": 7, "dispatchedByName": "Juan Pérez"
  }
]
```

`daysInTransit` lo calcula el **servidor**: contarlo en la pantalla con el reloj del
equipo daría un número distinto en cada máquina, y un dato de gestión que cambia
según quién mire no sirve para decidir.

El permiso es `transit.view` y no `transfer.view`: el catálogo los separa **a
propósito** («ver mercancía en tránsito» es su propia entrada), y quien vigila la
deuda de los traslados no tiene por qué poder ver ni crear traslados.

**Un traslado nunca recibido es una deuda visible, no un dato que se pierde.**

## Invariante

**`Σ stock_by_location + Σ stock_in_transit` se conserva en cada transición.** La
mercancía en el camión **sigue contando** en el inventario: si se modelara como dos
movimientos instantáneos, el total bajaría durante el viaje y los reportes
mentirían. Lo verifica `TransferInvariantTest.kt`.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `backend/src/test/kotlin/dev/axzy/stock/backend/transfer/TransferInvariantTest.kt` | la conservación del total en cada transición |
| `backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt` | las rutas de traslados contra la API montada |
