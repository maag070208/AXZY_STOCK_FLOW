# Catalog
> **Reference.** Products, locations, warehouses and catalog import.
> Last verified: 2026-10-10 (phase 4, M01).
> **Reconstruction note (2026-10-10).** Contract in force. The code references point to the previous backend. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

Implemented in layers, like the rest of the API (docs/arquitectura/api-spring.md §1):

| Layer | File |
|---|---|
| Routes | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/catalog/` |
| Use cases and ports | `api/application/src/main/kotlin/dev/axzy/stock/application/catalog/` |
| Domain rules | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/catalog/CatalogModel.kt` |
| SQL | `api/adapters-persistence/src/main/kotlin/dev/axzy/stock/adapters/persistence/catalog/` |
| Wiring | `api/app/src/main/kotlin/dev/axzy/stock/app/config/CatalogConfig.kt` |

Pagination and states are in [convenciones.md](convenciones.md); the
codes, in [errores.md](errores.md). Each route declares its permission with
`@RequiresPermission` and startup fails if any does not declare it (I5).

## `GET /v1/products`

Paginated list. Supported `sort`: `productCode`, `name`, `createdAt`. Filters: `q`
(free-text search) and `active`. Permission: `product.view`.

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

`stock` comes from **`product_stock`, the projection**, not from summing the ledger (rule
R-P3): it is `Σ stock_by_location` + `Σ stock_in_transit` with `received_at IS NULL`,
the same number the inventory engine maintains inside the transaction that
moves the stock.

The condition `received_at IS NULL` is essential: a transfer already received
has its units in `stock_by_location` **and** its transit row still
exists as history. Without excluding it, the same goods would count twice and the
total would be an inflated number that nobody could reconcile.

The balance travels in the same query as the listing: resolving it with a query
per product would be the N+1 that rule R-P2 forbids. A listing is **two
statements** —the count and the page—, and it is measured in `CatalogPersistenceTest`.

In `stock` the quantity travels as a **JSON number with 3 decimals** (not as a
string); the reason is in [convenciones.md §1.7](convenciones.md).

> **Not implemented.** The contract foresaw a **separate** `stockInTransit` field,
> to show "100 on hand, 20 on the way". The current response
> (`ProductResponse`, in `catalog/CatalogDtos.kt`) returns only `stock`, which already
> includes transit. The distinction is not exposed yet.

## `POST /v1/products`

```json
{ "productCode": "ABRAA1B", "name": "...", "description": null, "unitMeasure": "PZA" }
```

Permission: `product.create`. **201** with the created product.

- `productCode`: alphabet `0-9A-Z`, length 6–14. If it does not meet it: `422
  INVALID_PRODUCT_CODE`.
- `name`: required, maximum 120 characters (`422 FIELD_REQUIRED` /
  `FIELD_TOO_LONG`).
- `unitMeasure`: must exist in the unit catalog (`422
  UNKNOWN_UNIT_MEASURE`).

`409` with `code = DUPLICATE_PRODUCT_CODE` if it already exists. The last guarantee is the
database's unique index; the precheck gives the readable error in the normal case.

## `GET|PUT|DELETE /v1/products/{id}`

`GET` requires `product.view`; `PUT`, `product.edit`; `DELETE`, `product.delete`.

`DELETE` is **logical** (`active = false`) and requires `reason`, in the body
(`{"reason": "..."}`) **or** in the query (`?reason=`): there are clients and proxies that
discard the body of a `DELETE`. A product with stock cannot be deactivated
without adjusting or transferring it first: `409` with `code = PRODUCT_HAS_STOCK` and
`details.stock`.

In the `PUT` the body **replaces** the resource: omitting `description` deletes it.
Tolerating the `productCode` equally allows the round trip of a client that receives the
object from the `GET` and returns it modified; changing it is rejected with
`PRODUCT_CODE_IMMUTABLE` (the implementation returns it as `422`): it is printed
on the labels and on the shelves.

A product already deactivated responds `409 PRODUCT_ALREADY_INACTIVE`: deactivating it again would overwrite the reason of the first deletion.

## `POST /v1/products/import`

`multipart/form-data` with `file` (CSV or XLSX) and `dryRun=true|false`. Permission:
`product.import`.

**Always `200`, never `201`, even with `dryRun=false`.** It is not a resource creation:
it is an **operation over a collection** that produces a report. The `201`
implies that the body is the created resource and that its `Location` points to it; here
there is no single resource to return, so the `200` is the correct status.

`dryRun` defaults to **`true`**: if the client forgets the field, it is simulated. The
reverse mistake —writing the whole catalog when a simulation was wanted— has no
way back.

**200 with `dryRun=true`:** the report, **without writing anything**.

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
    { "rowNumber": 42, "productCode": "mal", "reason": "Invalid product code" }
  ]
}
```

The real report adds `created`, `updated` and `skipped` —importing the same file twice says `created: 0` and `skipped: 1398`, which is what makes visible that
nothing was done—, `persisted` (rows actually written, `0` in simulation) and the
stock load: `stockPersisted`, `stockLoaded` and `stockSkipped`.

**The stock in the file is loaded to `SIN-UBICAR` with an `INITIAL` movement**, with the
user who imported it and its date. The file carries the legacy total and **does not say in which shelf each piece
is**: what the load does is leave that total in the unlocated balance
so that the physical count distributes it ([ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md)).

**It is only loaded to products that have no stock.** A product that already has a balance is skipped and
counted in `stockSkipped`: reimporting the same file cannot duplicate the inventory (K2),
and the report says how many were skipped instead of hiding it. In a simulation nothing
is loaded.

**`rejections` with the row number is mandatory**: without it, the user cannot
correct the file. The **physical** row number of the file is used (the header
is 1), which is the one seen in Excel. The domain importer already produces it
(`CatalogImporter`).

If the file has invalid rows, **nothing is written** and it answers
`422 IMPORT_INVALID_ROWS`, with a `details` entry per row (`row_4`) up to a maximum of
50. Validation happens **before** looking at `dryRun`: a simulation with bad rows is not a valid
report either. `IMPORT_HAS_REJECTIONS` is declared in the error catalog but **is not emitted**: it was the legacy's response for the same
case.

Other errors: `400 FILE_REQUIRED`, `FILE_TOO_LARGE`, `EMPTY_FILE`,
`IMPORT_FILE_FORMAT`, `INVALID_DRY_RUN`; `422 INVALID_PRODUCT_CODE`,
`UNKNOWN_UNIT_MEASURE`, `IMPORT_INVALID_EXTERNAL_ID`.

**Formats: CSV, TSV and XLSX.** The delimiter is decided by the **header**, not by the
extension: the migration's working file is a TSV
(`scripts/catalogo-existencias.tsv`), and renaming a file does not change its format.

**The XLSX is read with Apache POI and converted to CSV before parsing**, with the same
reader as the text files. Reading it separately would mean two validation paths for the
same data, and the day one changed the Excel and the CSV would give different results
without anyone noticing; there is a test that compares the rows of the two. Numbers are
read **as they look on the sheet** (`10914130`, not `1.091413E7`) and a field with a comma
is quoted. A corrupt workbook answers `IMPORT_FILE_FORMAT` with the reason, not a `500`:
it is an error of the file, and whoever uploaded it can fix it.

## `GET|POST /v1/locations`, `GET|PUT|DELETE /v1/locations/{id}`

`GET` (listing and detail) requires `location.view`; `POST`, `location.create`;
`PUT`, `location.edit`; `DELETE`, `location.delete`. The listing accepts `q`,
`warehouseId`, `kind` and `active`; `sort`: `hall`, `code`, `position`, `createdAt`.

```json
{
  "id": 2, "warehouseId": 1, "warehouseCode": "PRINCIPAL",
  "hall": "04", "code": "B", "position": "C", "levels": 3,
  "kind": "SHELF", "active": true, "displayName": "04-B-C"
}
```

`kind` is `SHELF` or `RECEIVING`. **The receiving location is real, with a real `id`**
— not the `ADMIN` pseudo-location with a fictitious identifier that the legacy
returned hardcoded and that could collide with location 1.

**`displayName` is DERIVED, not a free field.** The server composes it with
`hall-code-position` for `SHELF` locations (`"04-B-C"`). For the receiving one it
returns **`"RECEPCION"`**, which is its `code`, not
`"RECEPCION-RECEPCION-RECEPCION"`; and for goods not located, `"SIN-UBICAR"`.
The rule lives in `catalog/location/LocationRecordMapper.kt` and in
`inventory/InventoryMappers.kt`.

The rule: **the client never composes the visible name, it receives it.** That way, if
the format of the shelf labels changes, the backend, the web and the mobile app do not
all have to change at once. In the ledger, `fromLocationDisplayName` and `toLocationDisplayName`
use the same rule; that is what explains why the example shows `"RECEPCION"`.

Creation and edit errors: `422 INVALID_LEVELS`, `INVALID_LOCATION_KIND`,
`INVALID_LOCATION_PART`, `UNKNOWN_WAREHOUSE`; `409 DUPLICATE_LOCATION`. Moving a
warehouse location is rejected with `WAREHOUSE_IMMUTABLE` (returned as `422`): the
stock it contains would change warehouse without any inventory movement. The
`RECEIVING` location cannot be deactivated: `409 RECEIVING_LOCATION_PROTECTED`.
A location with stock responds `409 LOCATION_HAS_STOCK` with
`details.stock`.

## `GET|POST /v1/warehouses`, `PUT /v1/warehouses/{id}`

`GET` requires `warehouse.view`; `POST`, `warehouse.create`; `PUT`,
`warehouse.edit`. The warehouse routes use the **warehouse** permission and not
`location.view`: each resource declares its own, and the four base roles have
both, so who can enter does not change.

The listing goes **without a pagination envelope and without the 200 limit**, and it is deliberate: it is
a closed and tiny catalog that the interface needs in full to populate a
dropdown (see [convenciones.md §1.4](convenciones.md)). It accepts `q` and `active`.

```json
{ "items": [{ "id": 1, "code": "PRINCIPAL", "name": "Bodega principal", "isDefault": true, "active": true }] }
```

`POST` answers **201** with the created warehouse. If it is born with `isDefault: true`, the
previous one stops being default **in the same transaction**: the partial index
`warehouses_one_default_idx` does not allow two. `PUT` changes code, name, address
and the default flag; **the flag cannot be removed without setting another** (an
`isDefault: false` on the default one keeps it), because the system needs to
know where what arrives without a declared destination lands.

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/CatalogFlowTest.kt` | K1–K6, I5, I8 and the listings, over real HTTP |
| `api/app/src/test/kotlin/dev/axzy/stock/app/RoutePermissionTest.kt` | I5: every route declares its permission, and the permission exists in the catalog |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/CatalogPersistenceTest.kt` | SQL, pagination (R-P2), balance projection (R-P3) and import idempotency |
| `stock-domain/src/commonTest/kotlin/dev/axzy/stock/domain/catalog/CatalogModelTest.kt` | the catalog rules, without a database |
