# Inventario
> **Referencia.** Existencias por ubicación, kardex y las operaciones que mueven
> inventario: ajustes, ubicación y traslado entre estantes.
> Última verificación: 2026-10-10 (fase 5, M02: movimientos y consultas).

Implementado en capas, como el resto de la API (docs/arquitectura/api-spring.md §1):

| Capa | Archivo |
|---|---|
| Rutas | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/inventory/InventoryController.kt` |
| Casos de uso y puertos | `api/application/src/main/kotlin/dev/axzy/stock/application/inventory/` |
| Reglas del dominio | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/` |
| Motor y consultas | `api/adapters-persistence/src/main/kotlin/dev/axzy/stock/adapters/persistence/inventory/` |
| Cableado | `api/app/src/main/kotlin/dev/axzy/stock/app/config/InventoryConfig.kt` |

La paginación está en [convenciones.md](convenciones.md); los códigos, en
[errores.md](errores.md). **La API no escribe existencias**: toda escritura pasa por
`registrar_movimiento`, y una prueba de código (`InventoryWriteGuardTest`) impide que
un adaptador escriba `stock_by_location`, `product_stock` o `kardex` a mano.

> **Pendiente de la fase 5:** los reportes de existencias, resumen de movimientos y
> `kardex-vs-projection` ([reportes.md](reportes.md)). Las consultas y los movimientos
> de esta página están terminados y probados.

## `GET /v1/inventory/stock`

Existencias por producto y ubicación. Permiso: `inventory.view`. `sort` admitido:
`productCode`.

```
GET /v1/inventory/stock?productCode=ABRAA1B&locationCode=04-B-C&onlyWithStock=true
```

`onlyWithStock` es `true` por defecto: un listado que devuelve miles de filas a
`0.000` no lo lee nadie.

```json
{
  "items": [
    { "productId": 1, "productCode": "ABRAA1B", "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "locationId": 2, "locationDisplayName": "04-B-C",
      "warehouseId": 1, "warehouseCode": "PRINCIPAL", "quantity": "100.000" }
  ],
  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
}
```

`locationDisplayName` lo compone el servidor (`04-B-C`, `RECEPCION`, `SIN-UBICAR`),
nunca el cliente. `totalItems` **se cuenta en la base** con los mismos filtros que
el listado, no se deduce de `items` (véase
[convenciones.md §1.4](convenciones.md)).

## `GET /v1/inventory/kardex`

Libro de movimientos. Permiso: `kardex.view`. `sort` admitido: `movedAt`,
`productCode`, `moveType`.

```
GET /v1/inventory/kardex?productCode=ABRAA1B&from=2026-10-01&to=2026-10-31&moveType=RECEIPT_IN
```

```json
{
  "items": [
    {
      "id": 501, "movedAt": "2026-10-07T21:24:37.688Z",
      "productCode": "ABRAA1B", "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "moveType": "RECEIPT_IN",
      "units": "100.000", "quantityAfter": "1100.000",
      "fromLocationDisplayName": null,
      "toLocationDisplayName": "RECEPCION",
      "container": "CONT-01", "orderNumber": null,
      "adjustmentReason": null, "adjustmentOrigin": null,
      "userId": 7, "userName": "Juan Pérez",
      "note": null
    }
  ],
  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
}
```

`from` y `to` son fechas operativas (`AAAA-MM-DD`); `to` incluye el día entero.
Un rango invertido o una fecha mal formada se rechazan con `400`.

**El kardex es append-only y no se puede modificar por API.** No existe `PUT` ni
`DELETE`. Las correcciones son movimientos nuevos. El listado y el conteo
comparten los filtros: un rango sin resultados da `items: []` **y** `totalItems:
0`.

`productName` y `adjustmentOrigin` viajan en la respuesta; el detalle de origen y
destino va solo como **nombre visible** (`fromLocationDisplayName`,
`toLocationDisplayName`), no como identificadores de ubicación.

## `POST /v1/inventory/adjustments`

**La regla única `AdjustStock`.** Los cuatro orígenes del legacy se unifican aquí.
Permiso: **depende de la dirección y del motivo** (véase abajo).

```json
{
  "productCode": "ABRAA1B",
  "locationCode": "04-B-C",
  "quantity": 5.000,
  "direction": "DECREASE",
  "reason": "DAMAGE",
  "origin": "DAMAGE_REPORT",
  "note": "Caja rota en el pasillo 3",
  "approverId": null
}
```

- `productCode` y `locationCode` identifican producto y ubicación (no `productId`
  ni `locationId`).
- `quantity`: número decimal con 3 decimales.
- `direction`: `INCREASE` | `DECREASE`.
- `reason`: `SHORTAGE` | `DAMAGE` | `THEFT` | `SUPPLIER_ERROR` | `COUNT_ERROR` |
  `RETURN_TO_SUPPLIER` | `EXPIRED` | `OTHER`. **Obligatorio siempre.**
- `origin`: `INVOICE_DISCREPANCY` | `FULFILLMENT_SHORTAGE` | `MANUAL_COUNT` |
  `DAMAGE_REPORT`.
- `approverId`: quién aprueba, si el ajuste supera el umbral. Va en el **cuerpo**
  y no se deduce del token porque tiene que ser **otra persona**: el dominio
  rechaza que coincida con quien solicita.

La idempotencia va en la **cabecera `Idempotency-Key`**, no en el cuerpo.

**201** con el movimiento creado. Un ajuste que supera el umbral queda en
`PENDING_APPROVAL` y responde **`202 Accepted`** con `status = PENDING_APPROVAL`.

El permiso lo determina `InventoryRoutes.adjustmentPermission`:

| Dirección / motivo | Permiso |
|---|---|
| `INCREASE` | `inventory.adjust_increase` |
| `THEFT` | `inventory.adjust_theft` |
| `DAMAGE` | `inventory.adjust_damage` |
| `SHORTAGE` | `inventory.adjust_shortage` |
| cualquier otro motivo | `inventory.adjust_count_error` |

**No hay un permiso genérico de «ajustar»**, y es deliberado: aumentar no es lo
mismo que mermar, y un robo no es lo mismo que un error de conteo. Un permiso
único daría a cualquiera la capacidad de tapar un robo con un ajuste que él mismo
firma.

Errores: `422 REASON_REQUIRED` si falta el motivo, `422 INSUFFICIENT_STOCK` con
`details.available` si no hay existencia, **`403 SEGREGATION_OF_DUTIES`** si el
aprobador es quien solicita (pertenece a la familia de autorización, no a la de
reglas de negocio: `docs/api/errores.md`), `400 INVALID_PARAMETER` si la dirección,
el motivo o el origen no están en la lista (con `details.allowed`).

Respuesta:

```json
{ "kardexIds": [501], "status": "APPLIED", "total": "95.000", "message": null }
```

## `POST /v1/inventory/locate`

Ubicar mercancía: traslado de la ubicación de recepción al estante. Permiso:
`invoice.locate`.

```json
{ "productCode": "ABRAA1B", "fromLocationCode": "RECEPCION", "toLocationCode": "04-B-C", "quantity": 100.000 }
```

**El total NO cambia**: es un traslado. El legacy mutaba el registro de recepción
del kardex y por eso su libro dejaba de ser un libro. La respuesta es un
`MovementResponse` con `status = "APPLIED"` y `total` vacío, y un `message` que lo
explica.

El cuerpo es el mismo que el de `move`. **No existe `invoiceLineId`.**

Errores: `409 DISCREPANCY_NOT_APPROVED` (una línea con discrepancia sin aprobar no
se puede ubicar; regla B1), `422 SAME_SOURCE_AND_DESTINATION`,
`422 INSUFFICIENT_STOCK`, `400 INVALID_PRODUCT_CODE`, `400 INVALID_LOCATION_CODE`.

## `POST /v1/inventory/move`

Traslado entre estantes de la misma bodega. Permiso: `inventory.move`. El cuerpo
es el mismo que el de `locate`.

```json
{ "productCode": "ABRAA1B", "fromLocationCode": "04-B-C", "toLocationCode": "04-B-D", "quantity": 10.000 }
```

Mover entre estantes es una operación **distinta** de ubicar: ubicar saca de
recepción, mover reorganiza. El operador tiene la primera pero no necesariamente
la segunda.

`422 SAME_SOURCE_AND_DESTINATION` si origen y destino coinciden, `422
INSUFFICIENT_STOCK` si no hay existencia en el origen.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/InventoryFlowTest.kt` | A1, A2, A5, A6, A7, A8, A10 y la aprobación, por HTTP real |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/InventoryEngineConcurrencyTest.kt` | A3, A4 y A9: concurrencia y kardex inmutable, con dos transacciones reales |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/InventoryPersistenceTest.kt` | el motor, la idempotencia, el presupuesto de consultas (R-P2) y la resolución del nombre visible |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/InventoryWriteGuardTest.kt` | que ningún adaptador escribe el inventario por fuera del motor |
| `api/application/src/test/kotlin/dev/axzy/stock/application/inventory/LocationRefTest.kt` | que el nombre visible y su intérprete no se separen |
| `stock-domain/src/commonTest/kotlin/dev/axzy/stock/domain/inventory/AdjustmentTest.kt` | las reglas del ajuste, sin base de datos |
