# Inventory
> **Reference.** Stock per location, ledger and the operations that move inventory:
> adjustments, location and transfer between shelves.
> Last verified: 2026-10-10 (phase 5, M02: movements and queries).

Implemented in layers, like the rest of the API (docs/arquitectura/api-spring.md §1):

| Layer | File |
|---|---|
| Routes | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/inventory/InventoryController.kt` |
| Use cases and ports | `api/application/src/main/kotlin/dev/axzy/stock/application/inventory/` |
| Domain rules | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/` |
| Engine and queries | `api/adapters-persistence/src/main/kotlin/dev/axzy/stock/adapters/persistence/inventory/` |
| Wiring | `api/app/src/main/kotlin/dev/axzy/stock/app/config/InventoryConfig.kt` |

Pagination is in [convenciones.md](convenciones.md); the codes, in
[errores.md](errores.md). **The API does not write stock**: every write goes through
`registrar_movimiento`, and a code test (`InventoryWriteGuardTest`) prevents
an adapter from writing `stock_by_location`, `product_stock` or `kardex` by hand.

> **Pending from phase 5:** the stock reports, the movement summary and
> `kardex-vs-projection` ([reportes.md](reportes.md)). The queries and the movements
> on this page are finished and tested.

## `GET /v1/inventory/stock`

Stock per product and location. Permission: `inventory.view`. Supported `sort`:
`productCode`.

```
GET /v1/inventory/stock?productCode=ABRAA1B&locationCode=04-B-C&onlyWithStock=true
```

`onlyWithStock` is `true` by default: a listing that returns thousands of rows at
`0.000` is not read by anyone.

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

`locationDisplayName` is composed by the server (`04-B-C`, `RECEPCION`, `SIN-UBICAR`),
never by the client. `totalItems` **is counted in the database** with the same filters as
the listing; it is not deduced from `items` (see
[convenciones.md §1.4](convenciones.md)).

## `GET /v1/inventory/kardex`

Movement ledger. Permission: `kardex.view`. Supported `sort`: `movedAt`,
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

`from` and `to` are operational dates (`YYYY-MM-DD`); `to` includes the whole day.
An inverted range or a malformed date is rejected with `400`.

**The ledger is append-only and cannot be modified through the API.** There is no `PUT` nor
`DELETE`. Corrections are new movements. The listing and the count
share the filters: a range with no results gives `items: []` **and** `totalItems:
0`.

`productName` and `adjustmentOrigin` travel in the response; the origin and destination
detail goes only as **visible name** (`fromLocationDisplayName`,
`toLocationDisplayName`), not as location identifiers.

## `POST /v1/inventory/adjustments`

**The single rule `AdjustStock`.** The four legacy origins are unified here.
Permission: **depends on the direction and the reason** (see below).

```json
{
  "productCode": "ABRAA1B",
  "locationCode": "04-B-C",
  "quantity": 5.000,
  "direction": "DECREASE",
  "reason": "DAMAGE",
  "origin": "DAMAGE_REPORT",
  "note": "Broken box in aisle 3",
  "approverId": null
}
```

- `productCode` and `locationCode` identify the product and the location (not `productId`
  nor `locationId`).
- `quantity`: decimal number with 3 decimals.
- `direction`: `INCREASE` | `DECREASE`.
- `reason`: `SHORTAGE` | `DAMAGE` | `THEFT` | `SUPPLIER_ERROR` | `COUNT_ERROR` |
  `RETURN_TO_SUPPLIER` | `EXPIRED` | `OTHER`. **Always required.**
- `origin`: `INVOICE_DISCREPANCY` | `FULFILLMENT_SHORTAGE` | `MANUAL_COUNT` |
  `DAMAGE_REPORT`.
- `approverId`: who approves, if the adjustment exceeds the threshold. It goes in the **body**
  and is not deduced from the token because it has to be **another person**: the domain
  rejects it if it matches whoever requests.

Idempotency goes in the **`Idempotency-Key` header**, not in the body.

**201** with the created movement. An adjustment that exceeds the threshold stays in
`PENDING_APPROVAL` and answers **`202 Accepted`** with `status = PENDING_APPROVAL`.

The permission is determined by `InventoryRoutes.adjustmentPermission`:

| Direction / reason | Permission |
|---|---|
| `INCREASE` | `inventory.adjust_increase` |
| `THEFT` | `inventory.adjust_theft` |
| `DAMAGE` | `inventory.adjust_damage` |
| `SHORTAGE` | `inventory.adjust_shortage` |
| any other reason | `inventory.adjust_count_error` |

**There is no generic "adjust" permission**, and it is deliberate: increasing is not the
same as shrinking, and a theft is not the same as a count error. A single permission
would give anyone the ability to cover up a theft with an adjustment that they themselves
sign.

Errors: `422 REASON_REQUIRED` if the reason is missing, `422 INSUFFICIENT_STOCK` with
`details.available` if there is no stock, **`403 SEGREGATION_OF_DUTIES`** if the
approver is the requester (it belongs to the authorization family, not to the business rules family: `docs/api/errores.md`), `400 INVALID_PARAMETER` if the direction,
the reason or the origin are not in the list (with `details.allowed`).

Response:

```json
{ "kardexIds": [501], "status": "APPLIED", "total": "95.000", "message": null }
```

## `POST /v1/inventory/locate`

Locate goods: transfer from the receiving location to the shelf. Permission:
`invoice.locate`.

```json
{ "productCode": "ABRAA1B", "fromLocationCode": "RECEPCION", "toLocationCode": "04-B-C", "quantity": 100.000 }
```

**The total does NOT change**: it is a transfer. The legacy mutated the receiving record
in the ledger, and that is why its book stopped being a book. The response is a
`MovementResponse` with `status = "APPLIED"` and an empty `total`, and a `message` that
explains it.

The body is the same as that of `move`. **There is no `invoiceLineId`.**

Errors: `409 DISCREPANCY_NOT_APPROVED` (a line with an unapproved discrepancy cannot be
located; rule B1), `422 SAME_SOURCE_AND_DESTINATION`,
`422 INSUFFICIENT_STOCK`, `400 INVALID_PRODUCT_CODE`, `400 INVALID_LOCATION_CODE`.

## `POST /v1/inventory/move`

Transfer between shelves of the same warehouse. Permission: `inventory.move`. The body
is the same as that of `locate`.

```json
{ "productCode": "ABRAA1B", "fromLocationCode": "04-B-C", "toLocationCode": "04-B-D", "quantity": 10.000 }
```

Moving between shelves is a **different** operation from locating: locating takes goods out of
receiving, moving reorganizes. The operator has the first but not necessarily
the second.

`422 SAME_SOURCE_AND_DESTINATION` if origin and destination match, `422
INSUFFICIENT_STOCK` if there is no stock at the origin.

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/InventoryFlowTest.kt` | A1, A2, A5, A6, A7, A8, A10 and approval, over real HTTP |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/InventoryEngineConcurrencyTest.kt` | A3, A4 and A9: concurrency and immutable ledger, with two real transactions |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/InventoryPersistenceTest.kt` | the engine, idempotency, query budget (R-P2) and visible name resolution |
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/InventoryWriteGuardTest.kt` | that no adapter writes the inventory outside the engine |
| `api/application/src/test/kotlin/dev/axzy/stock/application/inventory/LocationRefTest.kt` | that the visible name and its interpreter do not separate |
| `stock-domain/src/commonTest/kotlin/dev/axzy/stock/domain/inventory/AdjustmentTest.kt` | the adjustment rules, without a database |
