# Picking
> **Reference.** Picking orders, packing, confirmation, export to the ERP and shortages.
> Last verified: 2026-10-10 (phase 7, M04 and M09 closed).

Implemented in layers, like the rest of the API (docs/arquitectura/api-spring.md §1):

| Layer | File |
|---|---|
| Routes | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/fulfillment/OrderController.kt` |
| Use cases and ports | `api/application/src/main/kotlin/dev/axzy/stock/application/fulfillment/` |
| Order rules | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/fulfillment/Order.kt` |
| Persistence | `api/adapters-persistence/.../fulfillment/JdbcOrders.kt` |
| Wiring | `api/app/src/main/kotlin/dev/axzy/stock/app/config/FulfillmentConfig.kt` |

Pagination is in [convenciones.md](convenciones.md); the codes, in
[errores.md](errores.md).

**The confirmation is claimed with a conditional statement**: `UPDATE … WHERE status IN
(states that allow packing)`. Only one transaction takes the row, so two
simultaneous confirmations do not deduct twice (S3); the one that loses gets
`409 ORDER_ALREADY_CONFIRMED`. The list of states is **composed from the domain**
(`OrderStatus.allowsPackaging`).

## `GET|POST /v1/orders`

### Listing (`GET`)

Permission: `order.view`. Filters: `status` (one of `PENDING`, `IN_PROGRESS`,
`PACKAGED`, `PENDING_TO_OUT`, `COMPLETED`, `CANCELLED`) and `q`. Pagination by
`page` and `size`.

It returns **only the header**. The detail brings the lines: the packing screen
cannot work without them, and forcing a second request per line would be
absurd with orders of dozens of lines.

```json
{
  "id": 30, "orderNumber": "ORD-2024-0042", "packagingDate": "2026-10-07",
  "status": "IN_PROGRESS", "customerCode": null, "concept": null,
  "converted": false, "convertedAt": null,
  "exportStatus": null, "exportAttempts": 0, "exportLastError": null,
  "totalLines": 5, "packagedLines": 2
}
```

**Order** `status`: `PENDING` | `IN_PROGRESS` | `PACKAGED` | `PENDING_TO_OUT`
| `COMPLETED` | `CANCELLED`.

**Line** `status` (`order_lines.status`), and it is a union **different** from that
of the invoice: `PENDING` | `ASSIGNED` | `SUPPLIED` | `VERIFIED` | `CANCELLED`.

**Reusing the states of `InvoiceLineStatus` was a mistake**: `LOCATED` and
`DISCREPANCY` belong to the receiving flow and mean nothing in picking.
Sharing the union forced the interface to contemplate impossible states, and an
exhaustive `when` to have branches that never run.

### Creation (`POST`)

Permission: `order.create`.

```json
{
  "orderNumber": "ORD-2024-0042",
  "packagingDate": "2026-10-07",
  "customerCode": null,
  "concept": null,
  "lines": [ { "productCode": "ABRAA1B", "quantity": "10.000" } ]
}
```

`orderNumber` and `packagingDate` are optional; if they are not sent, the server generates
the number as `ORD-YYYY-NNNN` and uses today's date. **201** with the order and its
lines. The creation **does not touch the inventory**: the stock leaves when packing and
confirming, which are acts with a responsible person.

Errors: `422 EMPTY_ORDER`, `422 PRODUCT_NOT_FOUND`, `422 INVALID_QUANTITY`.

## `GET /v1/orders/{id}`

Permission: `order.view`. **Returns the order WITH its lines.** It is the only endpoint
of picking that brings them.

```json
{
  "id": 30, "orderNumber": "ORD-2024-0042", "packagingDate": "2026-10-07",
  "status": "IN_PROGRESS", "customerCode": null, "concept": null,
  "converted": false, "convertedAt": null,
  "exportStatus": null, "exportAttempts": 0, "exportLastError": null,
  "totalLines": 5, "packagedLines": 2,
  "lines": [
    {
      "id": 55, "productId": 1, "productCode": "ABRAA1B",
      "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "unitMeasure": "PZA",
      "quantity": "10.000", "suppliedQuantity": "7.000",
      "supplied": false, "status": "ASSIGNED",
      "assignedUserId": 7, "assignedUserName": "Juan Pérez",
      "locationId": 2, "locationDisplayName": "04-B-C"
    }
  ]
}
```

`suppliedQuantity` is **what has already been deducted from the inventory**, not "what was packed": it is the
accumulated amount that the inventory already reflects. Confirming deducts `quantity -
suppliedQuantity`.

**404** with `code = ORDER_NOT_FOUND`; `400 INVALID_ID`.

## `POST /v1/orders/{id}/lines/{lineId}/package`

Permission: `order.package`.

```json
{ "quantity": "10.000", "locationId": 2 }
```

Deducts stock and writes `FULFILLMENT_OUT` to the ledger, in a single transaction.
The quantity is validated against the **pending** amount of the line, not against `quantity`:
packing 7 of 10 twice would add up to 14. `locationId` is optional; without it, the
line's location is used.

**200** with the updated order. If it is not enough, `422 INSUFFICIENT_STOCK` with
`details.available` — and the shortage option is offered, which is an adjustment with
origin `FULFILLMENT_SHORTAGE`, not a second flow. Other errors: `422
EXCEEDS_PENDING_QUANTITY`, `INVALID_QUANTITY`, `LOCATION_REQUIRED`,
`INVALID_TRANSITION`; `404 ORDER_LINE_NOT_FOUND`.

Idempotency is **not** read on this route: it goes by header in the inventory operations
(see [convenciones.md §1.5](convenciones.md)).

## `POST /v1/orders/{id}/confirm`

Permission: `order.confirm`. **Deducts `quantity - suppliedQuantity` from each line,
NOT `quantity`**, and then triggers the export to the ERP.

What was packed was already deducted when packing; deducting the whole amount again would leave the
inventory short and the imbalance would be confused with a shrinkage. A line already complete
has pending zero and deducts nothing. If the ERP does not respond, **the confirmation
is NOT reverted**: the stock has already left and the attempt is recorded for the manual retry.

**200** with the order. `409` with `code = ORDER_ALREADY_CONFIRMED` if it was already
confirmed: confirming twice would subtract twice and the total would drop by double.
`422 INSUFFICIENT_STOCK` or `INVALID_TRANSITION`.

## `POST /v1/orders/{id}/export`

Manual retry of the export. Permission: `order.convert`. **Idempotent**: the key
is the converted `orderNumber`; if the order was already exported, this does not create a
second outflow and only counts the attempt.

```json
{ "attempts": 3, "status": "FAILED", "lastError": "The ERP does not respond" }
```

`status`: `SENT` | `FAILED` | `PENDING`. **200** with the result of the attempt. `409`
with `code = ORDER_NOT_CONVERTED` if it has not been confirmed yet: exporting something that has not been
picked would send the ERP an outflow that does not exist. `404 ORDER_NOT_FOUND`.

> **Integration status.** The real connection to the ERP **does not exist**: the attempt
> fails with a reason that starts with `ERP_NOT_CONNECTED`, the counter goes up and the error
> is kept for the retry (S5 and S6). It is not recorded as sent —that would be lying
> in the log and in the order's state—; what is missing is the adapter
> ([lo-no-verificado.md](../historico/LO-NO-VERIFICADO-ANTERIOR.md) and
> [ADR-0013](../adr/0013-ordenes-al-erp-con-reintentos.md)). The response does not include
> `nextRetryAt`.

## `PUT /v1/orders/{id}/lines/{lineId}/assign`

Assigns the line to whoever is going to pick it. Permission: `order.assign`.

```json
{ "userId": 7, "locationId": 2 }
```

**`userId` is optional**: without it, the caller **assigns themselves**, which is the daily case
of the operator. With it, a supervisor distributes the work. Assigning to someone else is not a separate
privilege: the permission is the same.

- **`locationId` is optional** and, if it does not come, the one it had is kept: what the
  `ASSIGNED` state says is that **there is someone in charge**, not where the goods are.
- **Only from `PENDING` or `ASSIGNED`.** An already picked or voided line answers
  `409 INVALID_TRANSITION`: assigning work on goods that already left would mean saying that
  someone is going to take them out twice. Reassigning while it is in progress **is** allowed —the
  shift changes, someone falls ill—.
- If packing picked the line between the read and the write, it answers
  `409 LINE_CHANGED` instead of assigning work on something that is no longer there.
- `422 ASSIGNEE_NOT_FOUND` and `422 LOCATION_NOT_FOUND` if the user or the location do not exist.

> **It did not exist before.** The table and the response had `assigned_user_id` and `location_id`
> since the beginning and **no route wrote them**: the operator had no way to assign
> a line to themselves. Corrected on 2026-10-10.

## `POST /v1/orders/{id}/shortage`

Shortage during picking: it delegates to the single adjustment rule, with origin
`FULFILLMENT_SHORTAGE`. Permission: `order.package`.

```json
{ "lineId": 55, "quantity": "3.000", "reason": "SHORTAGE", "note": "..." }
```

`reason` **has no default value**: a shortage is an adjustment and the reason is
mandatory, the same as in any shrinkage (`422 REASON_REQUIRED` without it, S8). This
page said "`SHORTAGE` by default", and that made the acceptance test impossible:
the delegated adjustment would never have seen a missing reason. **200** with the updated order.
Errors: `422 REASON_REQUIRED`, `422 INVALID_QUANTITY`, `422
LOCATION_REQUIRED`, `404 ORDER_LINE_NOT_FOUND`, `403 PERMISSION_DENIED` without
`inventory.adjust_shortage`.

Duplicating the adjustment rule here would create a second way to move inventory, and
over time one would require approval and the other would not.

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/FulfillmentFlowTest.kt` | S1–S8 over real HTTP: packing, confirmation that deducts only what is missing, double confirmation, export without ERP, retry and shortage |
| `api/adapters-persistence/src/test/.../FulfillmentPersistenceTest.kt` | the query budget and the **two simultaneous confirmations** against the database |
| `stock-domain/src/commonTest/kotlin/dev/axzy/stock/domain/fulfillment/` | the transitions of the order and the line, without a database (includes S9: an exported order is not cancelled) |
