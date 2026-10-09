# Catálogo
> **Referencia.** Productos, ubicaciones, bodegas e importación del catálogo.
> Última verificación: 2026-10-09.

Implementado en
`backend/src/main/kotlin/dev/axzy/stock/backend/catalog/` (el módulo se parte por
recurso: `product/ProductRoutes.kt`, `location/LocationRoutes.kt`,
`warehouse/WarehouseRoutes.kt`, con el cableado en `CatalogRoutes.kt` y los
helpers de validación en `CatalogValidation.kt`). La paginación y los estados
están en [convenciones.md](convenciones.md); los códigos, en
[errores.md](errores.md).

## `GET /v1/products`

Lista paginada. `sort` admitido: `productCode`, `name`, `createdAt`. Filtros: `q`
(búsqueda libre) y `active`. Permiso: `product.view`.

```json
{
  "items": [
    {
      "id": 1, "productCode": "ABRAA1B",
      "name": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "description": null,
      "unitMeasure": "PZA",
      "externalId": 1,
      "active": true,
      "stock": 5193.000
    }
  ],
  "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
}
```

`stock` es el **total derivado**: `Σ stock_by_location` + `Σ stock_in_transit` **con
`received_at IS NULL`**, calculado en `ProductRepository.totalStockOf`.

La condición `received_at IS NULL` es imprescindible: un traslado ya recibido
tiene sus unidades en `stock_by_location` **y** su fila de tránsito sigue
existiendo como histórico. Sin excluirla, la misma mercancía contaría dos veces y
el total sería un número inflado que nadie podría cuadrar.

En `stock` la cantidad viaja como **número JSON con 3 decimales** (no como
cadena); el motivo está en [convenciones.md §1.7](convenciones.md).

> **No implementado.** El contrato preveía un campo `stockInTransit` **por
> separado**, para mostrar «100 en existencia, 20 en camino». La respuesta actual
> (`ProductResponse`, en `catalog/CatalogDtos.kt`) devuelve solo `stock`, que ya
> incluye el tránsito. La distinción no está expuesta todavía.

## `POST /v1/products`

```json
{ "productCode": "ABRAA1B", "name": "...", "description": null, "unitMeasure": "PZA" }
```

Permiso: `product.create`. **201** con el producto creado.

- `productCode`: alfabeto `0-9A-Z`, longitud 6–14. Si no cumple: `422
  INVALID_PRODUCT_CODE`.
- `name`: obligatorio, máximo 120 caracteres (`422 FIELD_REQUIRED` /
  `FIELD_TOO_LONG`).
- `unitMeasure`: debe existir en el catálogo de unidades (`422
  UNKNOWN_UNIT_MEASURE`).

`409` con `code = DUPLICATE_PRODUCT_CODE` si ya existe. La garantía última es el
índice único de la base; el prechequeo da el error legible en el caso normal.

## `GET|PUT|DELETE /v1/products/{id}`

`GET` exige `product.view`; `PUT`, `product.edit`; `DELETE`, `product.delete`.

El `DELETE` es **lógico** (`active = false`) y exige `reason`, en el cuerpo
(`{"reason": "..."}`) **o** en la query (`?reason=`): hay clientes y proxies que
descartan el cuerpo de un `DELETE`. Un producto con stock no se puede desactivar
sin ajustarlo o trasladarlo primero: `409` con `code = PRODUCT_HAS_STOCK` y
`details.stock`.

En el `PUT` el cuerpo **reemplaza** al recurso: omitir `description` la borra.
Tolerar el `productCode` igual permite el ida y vuelta de un cliente que recibe el
objeto del `GET` y lo devuelve modificado; cambiarlo se rechaza con
`PRODUCT_CODE_IMMUTABLE` (la implementación lo devuelve como `422`): está impreso
en las etiquetas y en los estantes.

Un producto ya desactivado responde `409 PRODUCT_ALREADY_INACTIVE`: volver a
desactivarlo sobrescribiría el motivo del primer borrado.

## `POST /v1/products/import`

`multipart/form-data` con `file` (CSV o XLSX) y `dryRun=true|false`. Permiso:
`product.import`.

**Siempre `200`, nunca `201`, incluso con `dryRun=false`.** No es una creación de
recurso: es una **operación sobre una colección** que produce un informe. El `201`
implica que el cuerpo es el recurso creado y que su `Location` apunta a él; aquí no
hay un recurso único que devolver, así que el `200` es el estado correcto.

`dryRun` por defecto es **`true`**: si el cliente olvida el campo, se simula. El
olvido inverso —escribir el catálogo entero cuando se quería simular— no tiene
vuelta atrás.

**200 con `dryRun=true`:** el informe, **sin escribir nada**.

```json
{
  "dryRun": true,
  "totalRows": 1398,
  "accepted": 1398,
  "rejected": 0,
  "totalStock": 10914130.000,
  "zeroStockCount": 334,
  "unitMeasures": ["PZA"],
  "rejections": [
    { "rowNumber": 42, "productCode": "mal", "reason": "Código de producto inválido" }
  ]
}
```

El informe real añade `persisted` (filas escritas de verdad, `0` en simulación) y
`stockPersisted` (siempre `false` en esta fase: el stock del archivo es el total
del legacy y no dice en qué estantería está cada pieza; la carga inicial de
existencias es una decisión de negocio con su propio movimiento `INITIAL` en el
kardex).

**`rejections` con el número de fila es obligatorio**: sin él, el usuario no puede
corregir el archivo. Se usa el número de fila **físico** del archivo (la cabecera
es la 1), que es el que se ve en Excel. El importador del dominio ya lo produce
(`CatalogImporter`).

Si el import real encuentra filas inválidas, **no escribe ninguna** y responde
`409 IMPORT_HAS_REJECTIONS`. Otros errores: `400 FILE_REQUIRED`, `FILE_TOO_LARGE`,
`EMPTY_FILE`, `IMPORT_FILE_FORMAT`, `INVALID_DRY_RUN`; `422 INVALID_PRODUCT_CODE`,
`UNKNOWN_UNIT_MEASURE`, `IMPORT_INVALID_EXTERNAL_ID`.

## `GET|POST /v1/locations`, `GET|PUT|DELETE /v1/locations/{id}`

`GET` (listado y detalle) exige `location.view`; `POST`, `location.create`;
`PUT`, `location.edit`; `DELETE`, `location.delete`. El listado admite `q`,
`warehouseId`, `kind` y `active`; `sort`: `hall`, `code`, `position`, `createdAt`.

```json
{
  "id": 2, "warehouseId": 1, "warehouseCode": "PRINCIPAL",
  "hall": "04", "code": "B", "position": "C", "levels": 3,
  "kind": "SHELF", "active": true, "displayName": "04-B-C"
}
```

`kind` es `SHELF` o `RECEIVING`. **La ubicación de recepción es real, con `id`
real** — no la pseudo-ubicación `ADMIN` con identificador ficticio que el legacy
devolvía hardcodeada y que podía colisionar con la ubicación 1.

**`displayName` es DERIVADO, no un campo libre.** El servidor lo compone con
`hall-code-position` para las ubicaciones `SHELF` (`"04-B-C"`). Para la de recepción
devuelve **`"RECEPCION"`**, que es su `code`, no
`"RECEPCION-RECEPCION-RECEPCION"`; y para la mercancía sin ubicar, `"SIN-UBICAR"`.
La regla vive en `catalog/location/LocationRecordMapper.kt` y en
`inventory/InventoryMappers.kt`.

La regla: **el cliente nunca compone el nombre visible, lo recibe.** Así, si cambia
el formato de las etiquetas de estante, no hay que tocar el backend, la web y el
móvil a la vez. En el kardex, `fromLocationDisplayName` y `toLocationDisplayName`
usan la misma regla; es lo que explica que el ejemplo muestre `"RECEPCION"`.

Errores de alta y edición: `422 INVALID_LEVELS`, `INVALID_LOCATION_KIND`,
`INVALID_LOCATION_PART`, `UNKNOWN_WAREHOUSE`; `409 DUPLICATE_LOCATION`. Mover una
ubicación de bodega se rechaza con `WAREHOUSE_IMMUTABLE` (devuelto como `422`): el
stock que contiene cambiaría de bodega sin ningún movimiento de inventario. La
ubicación de `RECEIVING` no se puede desactivar: `409 RECEIVING_LOCATION_PROTECTED`.
Una ubicación con existencias responde `409 LOCATION_HAS_STOCK` con
`details.stock`.

## `GET /v1/warehouses`

Permiso: `location.view`. **Sin sobre de paginación y sin límite de 200**, y es
deliberado: es un catálogo cerrado y diminuto que la interfaz necesita completo
para poblar un desplegable (véase [convenciones.md §1.4](convenciones.md)). Admite
`q` y `active`.

```json
{ "items": [{ "id": 1, "code": "PRINCIPAL", "name": "Bodega principal", "isDefault": true, "active": true }] }
```

## Verificación

| Archivo | Qué cubre |
|---|---|
| `backend/src/test/kotlin/dev/axzy/stock/backend/catalog/CatalogRoutesTest.kt` | rutas de productos, ubicaciones y bodegas |
| `backend/src/test/kotlin/dev/axzy/stock/backend/catalog/CatalogImportTest.kt` | importación (`POST /v1/products/import`) |
| `backend/src/test/kotlin/dev/axzy/stock/backend/catalog/CatalogValidationTest.kt` | validaciones del catálogo |
| `backend/src/test/kotlin/dev/axzy/stock/backend/catalog/PaginationTest.kt` | paginación y tope de `size` |
