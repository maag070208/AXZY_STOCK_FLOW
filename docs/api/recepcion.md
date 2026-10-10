# Receiving
> **Reference.** Receiving invoices, capture of the physical count, approval and
> rejection of discrepancies, closing and the pending inbox.
> Last verified: 2026-10-10 (phase 6, M03: invoices, capture and decisions).

Implemented in layers, like the rest of the API (docs/arquitectura/api-spring.md §1):

| Layer | File |
|---|---|
| Routes | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/receiving/InvoiceController.kt` |
| Use cases and ports | `api/application/src/main/kotlin/dev/axzy/stock/application/receiving/` |
| Line rules | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/receiving/InvoiceLine.kt` |
| Persistence | `api/adapters-persistence/.../receiving/JdbcInvoices.kt` |
| Wiring | `api/app/src/main/kotlin/dev/axzy/stock/app/config/ReceivingConfig.kt` |

Pagination is in [convenciones.md](convenciones.md); the codes, in
[errores.md](errores.md).

**Locating** is `POST /v1/inventory/locate` with `invoiceLineId`
([inventario.md](inventario.md#ubicar-mercancía-de-una-recepción)): it is what completes
rule B1 —a line with an unapproved difference cannot be located— and what takes the
line to `LOCATED` with its `locationDisplayName`.

## `GET|POST /v1/invoices`

### Listing (`GET`)

Permission: `invoice.view`. Filters: `status` (one of `PENDING`, `IN_PROGRESS`,
`PENDING_TO_LOCATE`, `COMPLETED`, `CANCELLED`) and `q`. Pagination by `page` and
`size`.

It returns **only the header**, with the counters calculated in the database:

```json
{
  "items": [
    {
      "id": 15, "invoiceNumber": "F-2024-0158", "receiptDate": "2026-10-07",
      "invoiceType": "NATIONAL", "status": "IN_PROGRESS",
      "customerCode": null, "concept": null,
      "totalLines": 12, "pendingApprovalLines": 4,
      "createdAt": "2026-10-07T21:24:37.688Z"
    }
  ],
  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
}
```

`pendingApprovalLines` counts the lines that **prevent closing** —uncaptured, with an
unapproved difference or half-assigned—, which is the same criterion the closing applies:
this way the list and the action cannot disagree.

Invoice `status`: `PENDING` | `IN_PROGRESS` | `PENDING_TO_LOCATE` |
`COMPLETED` | `CANCELLED`.

### Creation (`POST`)

Permission: `invoice.create`.

```json
{
  "invoiceNumber": "F-2024-0158",
  "receiptDate": "2026-10-07",
  "invoiceType": "NATIONAL",
  "lines": [
    {
      "productCode": "ABRAA1B", "container": "CONT-01", "unitMeasure": "PZA",
      "quantity": 100.000, "boxQuantity": 2.000, "unitsPerBox": 50.000
    }
  ]
}
```

`unitsPerBox` is mandatory: without it, it cannot be verified that the packing of the invoice
balances (`BOXES_DO_NOT_DIVIDE_EVENLY` if 90 units are not whole boxes of 50) nor that
a count is consistent. `unitMeasure` is optional: if it does not come, the one from the product
in the catalog is used.

**201** with the reference, not with the full invoice: for the detail,
`GET /v1/invoices/{id}` must be requested.

```json
{ "id": 15, "invoiceNumber": "F-2024-0158" }
```

An invoice needs **at least one line**: `400 FIELD_REQUIRED` if it comes empty,
because an invoice without lines cannot be captured or located and blocks the flow.
The number is mandatory and the date must be `YYYY-MM-DD` (`400 INVALID_PARAMETER`).
Errors: `422 UNKNOWN_UNIT_MEASURE`, `422 INVALID_PRODUCT_CODE`, `409
DUPLICATE_INVOICE_NUMBER`.

## `GET /v1/invoices/{id}`

Permission: `invoice.view`. Includes the lines:

```json
{
  "id": 15, "invoiceNumber": "F-2024-0158", "receiptDate": "2026-10-07",
  "invoiceType": "NATIONAL", "status": "IN_PROGRESS",
  "customerCode": null, "concept": null,
  "totalLines": 1, "pendingApprovalLines": 1,
  "lines": [
    {
      "id": 88, "productId": 1, "productCode": "ABRAA1B",
      "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "container": "CONT-01",
      "quantity": "100.000", "boxQuantity": "2.000", "unitsPerBox": "50.000",
      "physicalQuantity": "80.000", "physicalBoxQuantity": "1.600",
      "locatedQuantity": "0.000",
      "difference": "-20.000",
      "status": "DISCREPANCY",
      "capturedBy": 7, "capturedByName": "Juan Pérez",
      "capturedAt": "2026-10-07T21:00:00Z",
      "approvedBy": null, "approvedByName": null, "approvedAt": null,
      "adjustmentReason": null,
      "locationDisplayName": null
    }
  ]
}
```

**`difference` is calculated by the server** and is `physicalQuantity - quantity`. In the
legacy it was declared in the model, the DTO, the mapper and the response, and **it was always
0**. Here it is a generated column of the database: it is impossible for it to
fall out of sync.

Line `status`: `PENDING` | `CAPTURED` | `DISCREPANCY` | `PENDING_APPROVAL` |
`APPROVED` | `ASSIGNED` | `LOCATED` | `CANCELLED`.

**404** with `code = INVOICE_NOT_FOUND` if the invoice does not exist; `400 INVALID_ID` if
the identifier is not a number.

## `POST /v1/invoices/{id}/lines/{lineId}/capture`

**Counting puts the goods into `RECEPCION`.** The count is the moment when the warehouse takes
possession of what actually arrived —what was counted, not what was invoiced—, and the movement is a
`RECEIPT_IN` for the **difference** between what was counted and what has already been received for that line. If what was counted is **less** than what was already received, the difference leaves with an adjustment for a **count error**: the previous count was high, and saying so with the correct reason makes it possible to audit why inventory went down.

What has already been received lives in the line (`received_quantity`, V15). It is what keeps a recount
from adding twice: a line with a discrepancy that the supervisor discards goes back to `PENDING`
and is counted again.

**And the capture is claimed before moving.** Since the entry is the *difference* against what was already
received, two simultaneous captures of the same line would read the same balance and receive
the same difference twice: the stock would be duplicated. The claim is a conditional statement
over `received_quantity` —the first one wins and the second changes zero rows— and the one that loses
answers `409 LINE_CHANGED` without writing any movement.

Permission: `invoice.capture`.

```json
{ "physicalQuantity": 80.000, "physicalBoxQuantity": 1.600 }
```

It calculates `difference` and decides the state: `CAPTURED` if it matches, `DISCREPANCY` if
not. **200** with the captured **line** (not the invoice).

`physicalBoxQuantity` is an **integer count of boxes**, not the decimal equivalent of the
legacy: counting 80 pieces with boxes of 50 is "1 box and 30 loose", and `80/50 = 1.6` was
a derived number, not a count. A value with a fraction answers `422 INVALID_QUANTITY`
instead of being rounded behind the client's back.

Errors: `422 INVALID_QUANTITY`, `422 INCONSISTENT_BOX_COUNT`, `422 FIELD_REQUIRED` if
the quantity or the counted boxes are missing, `409 LINE_ALREADY_RESOLVED` if the line is already
approved or located, `404 LINE_NOT_FOUND`, `400 INVALID_ID`.

## `PUT /v1/invoices/{id}/lines/{lineId}/assign`

Assigns the line to whoever is going to count it. Permission: `invoice.assign`.

```json
{ "userId": 7 }
```

**`userId` is optional**: without it, the caller **assigns themselves**, which is the daily case of the
capturer. With it, a supervisor distributes the count of a truck among several people.

- **Only from `PENDING`**: an already counted line answers `409 INVALID_TRANSITION`. The count
  has a person responsible (`capturedBy`) and changing it afterwards would be rewriting who did the work;
  a line in `DISCREPANCY` awaits a decision, not a capturer.
- If another person counted the line between the read and the write, it answers `409 LINE_CHANGED` instead
  of assigning work already done.
- `422 ASSIGNEE_NOT_FOUND` if the user does not exist; `404 INVOICE_NOT_FOUND` or
  `404 INVOICE_LINE_NOT_FOUND` if the invoice or the line do not exist.

> **It did not exist before.** The column `assigned_user_id` has been in the database since the first migration
> —and the query read it— but **no route wrote it**: the `ASSIGNED` state said "someone
> has it" without saying who. Corrected on 2026-10-10.

## `POST /v1/invoices/{id}/approve`

**The ONLY approval route. There is no per-line variant.** Permission:
`invoice.approve`.

```json
{ "adjustmentReason": "SUPPLIER_ERROR", "note": "..." }
```

**Approval is per invoice, not per line.** An invoice arrives with several lines
with a discrepancy, and approving them one by one forces the supervisor to repeat the
same judgment N times and leaves the invoice in an intermediate state where some lines
are approved and others not. The supervisor reviews the complete invoice and decides
once. Approving per line was also what invited **approving one's own without noticing**:
with a single action on the document, segregation of duties is
evident.

**200 response:** the complete invoice with its lines already in `APPROVED`.

**Errors:**
- `409` `NO_PENDING_APPROVAL` if there is no line to approve.
- `422` `REASON_REQUIRED` if `adjustmentReason` is missing.
- **`403`** `SEGREGATION_OF_DUTIES` if whoever approves is the one who captured **any** of the
  pending lines. The message and `details.lines` (the identifiers, separated by
  commas) indicate **which ones**, so that the supervisor knows what to reassign. It is `403` and not
  `422` because it belongs to the authorization family ([errores.md](errores.md)).

The database enforces the rule with a `CHECK`: it is impossible for a line to reach
`APPROVED` without `approved_by`. It does not depend on the code remembering to validate it.

## `POST /v1/invoices/{id}/reject-discrepancy`

**The other exit of rule B1.** Permission: `invoice.reject`. The body shares its shape
with approval —mandatory reason and optional note— because it is the same
decision taken the other way round.

```json
{ "adjustmentReason": "SUPPLIER_ERROR", "note": "..." }
```

Approving **accepts** the difference and the goods stay; rejecting **does not accept it**.
Without this exit, the only option against a discrepancy was to accept it, and a supervisor
who cannot reject ends up approving to unblock the invoice.

**Rejecting returns the line to `PENDING` and clears the count**, it does not cancel it: the
number the supervisor saw may be the correct one and what is missing is a recount.
Cancelling would leave goods in the warehouse that nobody counted and a dead line that can no longer
be captured. It is the decision implemented by
`InvoiceLine.rejectDiscrepancy` in the domain, and this page said "cancels the
lines": it was corrected here.

**200** with the invoice and its lines back in `PENDING`. Errors: `409
NO_PENDING_APPROVAL`, `422 REASON_REQUIRED`, `403 SEGREGATION_OF_DUTIES`,
`400 INVALID_ID`.

## `POST /v1/invoices/{id}/close`

Permission: `invoice.close`. **200** with the closed invoice and its lines.

**An invoice with blocking lines is not closed**: an uncaptured line, with an
unapproved difference or with work half-assigned. The criterion is defined by the
domain (`InvoiceLineStatus.blocksInvoiceClosing`) and not by a list written in the
route.

- `422` with `code = INVOICE_HAS_PENDING_LINES` and `details.pendingLines` if blocking
  lines remain.
- `409` `INVOICE_ALREADY_CLOSED` if it was already closed; `409 INVALID_TRANSITION` if
  it is cancelled.

## `GET /v1/invoices/pending-approval`

Pending inbox. Permission: `invoice.view`. **New screen: the legacy did not have it**, and without it a
discrepancy stayed blocked without anyone knowing it had to be approved. The response **does not** use the pagination envelope: it is
`{ "items": [...] }`.

```json
{
  "items": [
    {
      "invoiceId": 15, "invoiceNumber": "F-2024-0158", "receiptDate": "2026-10-07",
      "status": "IN_PROGRESS", "pendingLines": 4, "totalDifference": "20.000"
    }
  ]
}
```

`totalDifference` is the sum of the pending **absolute differences**: it is positive even if the
discrepancy is a shortage. Do not confuse it with the line's `difference`, which does carry a sign.

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/ReceivingFlowTest.kt` | R2–R6 and R8 over real HTTP: creation, capture with and without difference, per-invoice approval, rejection, closing, inbox and per-route permissions |
| `stock-domain/src/commonTest/kotlin/dev/axzy/stock/domain/receiving/InvoiceLineStateMachineTest.kt` | the line transitions, without a database |
| `api/app/src/test/kotlin/dev/axzy/stock/app/ReceivingFlowTest.kt` (R1 and R7) | that an unapproved discrepancy blocks locating without touching the inventory, and that locating leaves the line `LOCATED` with the total intact |

The eight tests of the flow are green: R1 and R7 cross with the inventory module
—the route that locates is its own— and the other six live in the invoice routes.
