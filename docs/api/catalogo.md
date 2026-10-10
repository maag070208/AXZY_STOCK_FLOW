# Catálogo
> **Referencia.** Productos, ubicaciones, bodegas e importación del catálogo.
> Última verificación: 2026-10-10 (fase 4, M01).
> **Nota de reconstrucción (2026-10-10).** Contrato vigente. Las referencias de código apuntan al backend anterior. Las reglas siguen vigentes; las rutas de código que cita pertenecen al sistema anterior y se remapean al código nuevo (ver [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

Implementado en capas, como el resto de la API (docs/arquitectura/api-spring.md §1):

| Capa | Archivo |
|---|---|
| Rutas | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/catalog/` |
| Casos de uso y puertos | `api/application/src/main/kotlin/dev/axzy/stock/application/catalog/` |
| Reglas del dominio | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/catalog/CatalogModel.kt` |
| SQL | `api/adapters-persistence/src/main/kotlin/dev/axzy/stock/adapters/persistence/catalog/` |
| Cableado | `api/app/src/main/kotlin/dev/axzy/stock/app/config/CatalogConfig.kt` |

La paginación y los estados están en [convenciones.md](convenciones.md); los
códigos, en [errores.md](errores.md). Cada ruta declara su permiso con
`@RequiresPermission` y el arranque falla si alguna no lo declara (I5).

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

`stock` sale de **`product_stock`, la proyección**, no de sumar el kardex (regla
R-P3): es `Σ stock_by_location` + `Σ stock_in_transit` con `received_at IS NULL`,
el mismo número que mantiene el motor de inventario dentro de la transacción que
mueve el stock.

La condición `received_at IS NULL` es imprescindible: un traslado ya recibido
tiene sus unidades en `stock_by_location` **y** su fila de tránsito sigue
existiendo como histórico. Sin excluirla, la misma mercancía contaría dos veces y
el total sería un número inflado que nadie podría cuadrar.

El saldo viaja en la misma consulta que el listado: resolverlo con una consulta
por producto sería el N+1 que la regla R-P2 prohíbe. Un listado son **dos
sentencias** —el conteo y la página—, y está medido en `CatalogPersistenceTest`.

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

El informe real añade `created`, `updated` y `skipped` —importar dos veces el
mismo archivo dice `created: 0` y `skipped: 1398`, que es lo que hace visible que
no se hizo nada—, `persisted` (filas escritas de verdad, `0` en simulación) y la
carga de existencias: `stockPersisted`, `stockLoaded` y `stockSkipped`.

**El stock del archivo se carga en `SIN-UBICAR` con un movimiento `INITIAL`**, con el
usuario que importó y su fecha. El archivo trae el total del legacy y **no dice en qué
estantería está cada pieza**: lo que hace la carga es dejar ese total en el saldo sin ubicar
para que el conteo físico lo reparta ([ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md)).

**Solo se carga a quien no tiene existencias.** Un producto que ya tiene saldo se salta y se
cuenta en `stockSkipped`: reimportar el mismo archivo no puede duplicar el inventario (K2),
y el informe dice cuántos se saltaron en lugar de esconderlo. En una simulación no se carga
nada.

**`rejections` con el número de fila es obligatorio**: sin él, el usuario no puede
corregir el archivo. Se usa el número de fila **físico** del archivo (la cabecera
es la 1), que es el que se ve en Excel. El importador del dominio ya lo produce
(`CatalogImporter`).

Si el archivo tiene filas inválidas, **no se escribe ninguna** y responde
`422 IMPORT_INVALID_ROWS`, con un `details` por fila (`row_4`) hasta un máximo de
50. La validación ocurre **antes** de mirar `dryRun`: una simulación con filas
malas tampoco es un informe válido. `IMPORT_HAS_REJECTIONS` queda declarado en el
catálogo de errores pero **no se emite**: era la respuesta del legacy para el mismo
caso.

Otros errores: `400 FILE_REQUIRED`, `FILE_TOO_LARGE`, `EMPTY_FILE`,
`IMPORT_FILE_FORMAT`, `INVALID_DRY_RUN`; `422 INVALID_PRODUCT_CODE`,
`UNKNOWN_UNIT_MEASURE`, `IMPORT_INVALID_EXTERNAL_ID`.

**Formatos: CSV, TSV y XLSX.** El delimitador se decide por la **cabecera**, no por la
extensión: el archivo trabajado de la migración es un TSV
(`scripts/catalogo-existencias.tsv`), y renombrar un archivo no cambia su formato.

**El XLSX se lee con Apache POI y se convierte a CSV antes de parsearlo**, con el mismo
lector que los archivos de texto. Leerlo por separado sería tener dos caminos de validación
para el mismo dato, y el día que uno cambiara el Excel y el CSV darían resultados distintos
sin que nadie lo notara; hay una prueba que compara las filas de los dos. Los números se
leen **como se ven en la hoja** (`10914130`, no `1.091413E7`) y un campo con coma va
entrecomillado. Un libro corrupto responde `IMPORT_FILE_FORMAT` con el motivo, no un `500`:
es un error del archivo, y quien lo subió puede arreglarlo.

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

## `GET|POST /v1/warehouses`, `PUT /v1/warehouses/{id}`

`GET` exige `warehouse.view`; `POST`, `warehouse.create`; `PUT`,
`warehouse.edit`. Las rutas de bodega usan el permiso **de bodega** y no
`location.view`: cada recurso declara el suyo, y los cuatro roles base tienen
ambos, así que no cambia quién puede entrar.

El listado va **sin sobre de paginación y sin límite de 200**, y es deliberado: es
un catálogo cerrado y diminuto que la interfaz necesita completo para poblar un
desplegable (véase [convenciones.md §1.4](convenciones.md)). Admite `q` y `active`.

```json
{ "items": [{ "id": 1, "code": "PRINCIPAL", "name": "Bodega principal", "isDefault": true, "active": true }] }
```

`POST` responde **201** con la bodega creada. Si nace con `isDefault: true`, la
anterior deja de serlo **en la misma transacción**: el índice parcial
`warehouses_one_default_idx` no admite dos. `PUT` cambia código, nombre, dirección
y la marca de predeterminada; **la marca no se puede quitar sin poner otra** (un
`isDefault: false` sobre la predeterminada la conserva), porque el sistema necesita
saber dónde aterriza lo que llega sin destino declarado.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/CatalogFlowTest.kt` | K1–K6, I5, I8 y los listados, por HTTP real |
| `api/app/src/test/kotlin/dev/axzy/stock/app/RoutePermissionTest.kt` | I5: toda ruta declara su permiso, y el permiso existe en el catálogo |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/CatalogPersistenceTest.kt` | SQL, paginación (R-P2), proyección del saldo (R-P3) e idempotencia de la importación |
| `stock-domain/src/commonTest/kotlin/dev/axzy/stock/domain/catalog/CatalogModelTest.kt` | las reglas del catálogo, sin base de datos |
