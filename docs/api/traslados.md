# Transfers
> **Reference.** Transfers between warehouses: creation, dispatch, receipt,
> cancellation and goods in transit. Last verified: 2026-10-10 (phase 8 closed).

**The transfer engine lives in SQL** (`db/migrations/V13__transfer_engine.sql`) and is already
finished and tested:

| Function | What it does |
|---|---|
| `ubicacion_recepcion(warehouse_id)` | the receiving location of a warehouse |
| `despachar_traslado(transfer_id, user_id)` | subtracts from the origin, opens the transit and recalculates |
| `recibir_traslado(transfer_id, user_id)` | adds to the receiving location of the destination, closes the transit and recalculates |
| `cancelar_traslado(transfer_id, user_id)` | returns the goods of a dispatched transfer to their origin |

It lives there and not in Kotlin because a transfer **moves balance**, and the balance is moved by the
engine: an `UPDATE stock_by_location` from the API would be the second write path that
`InventoryWriteGuardTest` forbids. The functions reuse `registrar_movimiento`,
so both halves are left in the ledger with their type (`TRANSFER_OUT` and
`TRANSFER_IN`) and the total is recalculated counting the transit.

Implemented in layers, like the rest of the API:

| Layer | File |
|---|---|
| Routes | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/transfer/TransferController.kt` |
| Use cases and ports | `api/application/src/main/kotlin/dev/axzy/stock/application/transfer/` |
| Transfer rules | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/transfer/Transfer.kt` |
| Persistence | `api/adapters-persistence/.../transfer/JdbcTransfers.kt` |
| Wiring | `api/app/src/main/kotlin/dev/axzy/stock/app/config/TransferConfig.kt` |

Pagination is in [convenciones.md](convenciones.md); the codes, in
[errores.md](errores.md).

States: `DRAFT` → `DISPATCHED` → `IN_TRANSIT` → `RECEIVED`, with `CANCELLED`.

> `IN_TRANSIT` **is not written by any route**: the domain dispatches to `DISPATCHED` and the
> transit report includes both states (`TransferStatus.isInTransit`). The state
> is kept in the model because "left" and "on the way" are different facts.

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

Permission: `transfer.view`. Filters: `status` and `warehouseId` (transfers that leave or
arrive at that warehouse). Pagination by `page` and `size`.

Only the **header**, with `lineCount` and `totalUnits` counted in the database. The
detail brings the lines: the dispatch screen cannot work without them.

## `POST /v1/transfers`

Permission: `transfer.create`. **It does not move stock**: a draft is an intention; the goods leave the locations on
**dispatch**, which is an act with a responsible person.

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

`fromLocationId` is optional: `null` means the receiving location or goods not located.

**201** with the transfer in `DRAFT` and its lines. Errors: `422 SAME_WAREHOUSE` if
origin and destination are the same warehouse (the goods would leave the locations and
enter transit towards the same place, the total would not change and no report
would detect it), `422 EMPTY_TRANSFER` without lines, `422 PRODUCT_NOT_FOUND`, `422
INVALID_QUANTITY`.

## `GET /v1/transfers/{id}`

Permission: `transfer.view`. Returns the transfer **with its lines**.

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

**404** with `code = TRANSFER_NOT_FOUND`; `400 INVALID_ID`.

## `POST /v1/transfers/{id}/dispatch`

Permission: `transfer.dispatch`. **The two halves of the movement go in ONE
transaction**: subtracts from the origin locations and inserts the transit row. The
product total **does not change**, because the transit is added in the total recalculation.

**200** with the dispatched transfer. `409 INVALID_TRANSITION` if it was already
dispatched: dispatching twice would subtract twice from the origin and the total would DROP,
which looks like a sale and not an error. `422 EMPTY_TRANSFER`, `404 TRANSFER_NOT_FOUND`.

## `POST /v1/transfers/{id}/receive`

Permission: `transfer.receive`. Adds to the receiving location of the destination warehouse and **closes the transit
row**. The order matters: first it adds and then it closes; the other way round, a failure between the two would leave the goods outside
both additions and the total would drop.

**200** with the received transfer. **`409 INVALID_TRANSITION`** if it was never dispatched:
goods that never left would arrive and the destination inventory would grow without the
origin going down. `422 NO_RECEIVING_LOCATION` if the destination warehouse has no
receiving location. `409 NOTHING_IN_TRANSIT` if there are no goods in transit
to receive. `404 TRANSFER_NOT_FOUND`.

## `POST /v1/transfers/{id}/cancel`

Permission: `transfer.cancel`. Allowed from `DRAFT` and from `DISPATCHED`. From a dispatched one, **the goods return to their origin location in the same
transaction**, so as not to leave them in a limbo that no report explains.

**From `RECEIVED` it is not possible:** what was received is in the destination locations and cancelling it would leave the goods counted in the wrong place. To undo it,
a transfer back is made, which leaves a trace.

**200** with the cancelled transfer. **`409 INVALID_TRANSITION`**, `404
TRANSFER_NOT_FOUND`.

> `INVALID_TRANSITION` is **409** and not 422: it is a conflict with the current state of the
> resource, and the error catalog sets it there ([errores.md](errores.md)). This page
> said 422 on both routes and it was corrected.

## `GET /v1/transfers/in-transit`

Permission: `transit.view`. Dispatched and not received transfers, **with their responsible person
and the elapsed days**. It returns an array, without a pagination envelope.

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

`daysInTransit` is calculated by the **server**: counting it on the screen with the equipment's clock
would give a different number on each machine, and a management figure that changes
depending on who looks at it is no use for deciding.

The permission is `transit.view` and not `transfer.view`: the catalog separates them **on purpose**
("view goods in transit" is its own entry), and whoever watches the debt of the transfers does not have to be able to see or create transfers.

**A transfer never received is a visible debt, not data that gets lost.**

## Invariant

**`Σ stock_by_location + Σ stock_in_transit` is preserved on every transition.** The
goods on the truck **still count** in the inventory: if they were modeled as two instantaneous
movements, the total would drop during the trip and the reports would lie.
`TransferInvariantTest.kt` verifies it.

## Verification

| File | What it covers |
|---|---|
| `api/adapters-persistence/src/test/kotlin/dev/axzy/stock/adapters/persistence/TransferEngineTest.kt` | T1, T2, T5 and T6 against a real PostgreSQL, the invariant on every transition, the ledger with its two halves and the atomicity of a two-line transfer |
| `api/app/src/test/kotlin/dev/axzy/stock/app/TransferFlowTest.kt` | T1–T8 over real HTTP, with their permissions and the transit report |
