# API conventions
> **Normative.** How routes, statuses, pagination, idempotency, headers and formats of this API are built. It overrides the contract
> of each resource. Last verified: 2026-10-10.
> **Reconstruction note (2026-10-10).** Contract in force. The code references point to the previous backend. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

This document defines the cross-cutting rules. The contract per resource is in
[contrato.md](contrato.md); the error body and its catalog, in
[errores.md](errores.md).

## 1.1 Routes

```
/v1/<resource>                listing and creation
/v1/<resource>/{id}           detail, update and deletion
/v1/<resource>/{id}/<action>  actions that are not CRUD
```

**The `/v1` prefix is mandatory.** The legacy did not version its routes, so a contract could not be
changed without breaking the deployed clients.

The only route that does not hang from `/v1/<resource>` in that form is
`PATCH /v1/print-jobs/{id}`, and even so it hangs from a resource: it is the update of the
state of a print job (see [etiquetas.md](etiquetas.md)).

## 1.2 Status codes

| Code | When | Body |
|---|---|---|
| `200` | Success with a body | the resource or the listing |
| `201` | Resource created | the resource, with its `id` |
| `202` | Accepted, pending confirmation | the job or the adjustment with `status` |
| `204` | Success without a body | empty (deletion, sign-out) |
| `400` | Malformed request | `ErrorResponse` |
| `401` | No valid identity | `ErrorResponse`, `code = UNAUTHENTICATED` |
| `403` | Identity without authorization | `ErrorResponse` with the reason |
| `404` | The resource does not exist | `ErrorResponse` |
| `409` | The state prevents the operation | `ErrorResponse` |
| `422` | Violates a business rule | `ErrorResponse` with `details` per field |
| `429` | Too many attempts | `ErrorResponse`, `code = RATE_LIMITED` |
| `503` | Dependency down | `ErrorResponse` (health) |

The translation of exception to status lives in
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/http/ErrorResponses.kt`, and the
handler that applies it, in `.../http/GlobalErrorHandler.kt`.
An unexpected error is answered as `500` with `code = INTERNAL_ERROR` without leaking
the internal detail.

**Telling `401` from `403` is not cosmetic.** On a `401` the client sends the
user to sign in; on a `403`, to request a permission. Returning `403` for a missing token sends someone who already has a session open to sign in.

## 1.4 Pagination

Every list is paginated. The legacy returned **all** the records, and with a
catalog of thousands of products and months of ledger, that meant the listing
loaded the entire table.

```
GET /v1/products?page=0&size=50&sort=productCode,asc&q=abra
```

```json
{
  "items": [ ... ],
  "page": 0,
  "size": 50,
  "totalItems": 1398,
  "totalPages": 28
}
```

- `page` starts at **0**, as in Spring and in most clients.
- `size` defaults to **50**, maximum **200**. A larger `size` is rejected with `400`,
  it is not trimmed silently: trimming makes the client believe it has all the
  data.
- `sort` is `field,direction`. The supported fields are documented per endpoint.
- `q` is the free-text search.
- **`from` and `to` are operational dates (`YYYY-MM-DD`) and are interpreted in the business time zone**
  (`stock.business-zone`), not in UTC: `to` includes the whole warehouse day.
  A malformed date answers `400 INVALID_FILTER_DATE` and an inverted range,
  `400 INVALID_FILTER_RANGE`.

A negative `page` or a `size` outside `1..200` answers `400 INVALID_PAGINATION`;
an unsupported `sort`, `400 INVALID_SORT`. Validation is single across the whole
API: `PageRequest.analyze` in
`api/application/src/main/kotlin/dev/axzy/stock/application/shared/Pagination.kt`.

**Deliberate exceptions.** They return **all** their rows, without a pagination envelope and without the 200 limit:

| Endpoint | Permission | Reason |
|---|---|---|
| `GET /v1/warehouses` | `warehouse.view` | closed and tiny catalog that the interface needs in full |
| `GET /v1/label-templates` | `label.template_view` | set of templates that grows only when an administrator decides so |
| `GET /v1/permissions` | `role.manage` | it is the matrix of the roles screen; paginating it would force requesting all the pages to draw a selector |

The reason is always the same: they are **closed and small catalogs**. Paginating them
would force the client to request all the pages before being able to draw a
`select`, which is slower and worse work than returning the rows at once. The
limit that makes it safe is that all three require administration permission
over a set that does not grow with the operation.

Also, `GET /v1/invoices/pending-approval` returns `{ "items": [...] }` and
`GET /v1/transfers/in-transit` returns an array: they are bounded inboxes, not
paginable listings.

### The total is counted, not guessed

```kotlin
Page(items, page.page, page.size, total)   // total from a SELECT count(*)
```

**Never `items.size`.** There have already been two bugs because of this:

- In inventory: `totalItems` returned the size of the page → "50 results"
  always.
- In the ledger: the count **ignored the filters** → a range with no results gave
  `items: []` but `totalItems: 1064`.

> **If the count ignores the filters, it is worse than not having it.**

The listing and the count share the filters in each endpoint, so that the envelope cannot say one
thing and the `items` another. Examples in the code:
`InventoryRoutes.kt` (`service.countStock` / `service.countKardex`),
`AuditRoutes.kt` and `ReportRoutes.kt`.

**A `size` greater than 200 is REJECTED with `400`, not trimmed**: trimming makes the
client believe it has all the data.

## 1.5 Idempotency

The operations that **move inventory** or **create documents** accept the
`Idempotency-Key` header. Repeating the same key returns the **same
response** and does not repeat the effect. The phone with no signal retries; without this, a
retry duplicates a movement.

The key travels in the **header**, not in the body. If it comes and is not a UUID, it is
rejected with `400 INVALID_IDEMPOTENCY_KEY`, it is not left to PostgreSQL's `CAST`
(which would come out as a `500` because of client data).

Status in the implementation:

| Endpoint | How it is guaranteed |
|---|---|
| `POST /v1/inventory/adjustments` | `Idempotency-Key` header (`InventoryRoutes.kt`, `InventoryUseCases.kt`) |
| `POST /v1/inventory/locate` | `Idempotency-Key` header |
| `POST /v1/inventory/move` | `Idempotency-Key` header |
| `POST /v1/orders/{id}/export` | idempotent by the converted `orderNumber`, not by header (`OrderService.kt`) |
| `POST /v1/invoices/{id}/lines/{lineId}/capture` | **does not read the header** in the current implementation |
| `POST /v1/orders/{id}/lines/{lineId}/package` | **does not read the header** in the current implementation |
| `POST /v1/orders/{id}/confirm` | **does not read the header** in the current implementation |
| `POST /v1/products/import` | idempotent by `product_code`: importing the same file twice does not duplicate (K2) |

`POST /v1/auth/logout` is also idempotent by design: it does not require a valid token and
revoking twice does not fail.

## 1.6 Headers

| Header | Direction | What for |
|---|---|---|
| `Authorization: Bearer <jwt>` | request | identity |
| `Idempotency-Key: <uuid>` | request | deduplicating retries |
| `X-Request-Id: <uuid>` | both | correlation with the audit log |
| `X-Client-Channel` | request | `WEB`, `MOBILE`, `MOBILE_OFFLINE` |
| `X-Client-Timezone` | request | IANA, e.g. `America/Mexico_City` |
| `X-Service-Key` | request | credential of an integration. **Only on `PATCH /v1/print-jobs/{id}`** |

**`X-Client-Channel` is not decorative**: the audit log records the channel, and
`MOBILE_OFFLINE` triggers the deferred synchronization path. The channel is read in
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/http/RequestOrigins.kt` and is
written in `api/adapters-persistence/src/main/kotlin/dev/axzy/stock/adapters/persistence/audit/JdbcAuditRecorder.kt`.

`X-Service-Key` and not `Authorization` for the printer: that way the audit log distinguishes
a job marked by a machine (`SERVICE_PRINTER`) from one done by a
person. The key is stored hashed in the `service_api_keys` table and its secret
is shown only once when it is created (`backend/src/main/kotlin/dev/axzy/stock/backend/label/LabelService.kt`).

## 1.7 Dates and numbers

- **Instants**: ISO 8601 in UTC with `Z` → `"2026-10-07T21:24:37.688Z"`.
- **Operational dates** (no time): `"2026-10-07"`. For the night shift, the
  business date, not the clock's date.
- **Quantities**: decimal with **3 decimals**. Most DTOs serialize them as a **string** (`"100.000"`, `"0.500"`): `KardexDto`,
  `StockByLocationDto`, `InvoiceLineDto`, `OrderLineResponse`,
  `TransferResponse`, `InventoryReportItem`, `MovementsSummaryItem`,
  `DivergenceDto` and `MovementResponse` declare their quantities as `String` and write them
  with `BigDecimal.toPlainString()`.
- **Transport exception in the catalog**: `ProductDto.stock` and
  `ImportReportDto.totalStock` are emitted as a **JSON number without quotes with 3
  decimals** (`5193.000`), with `Quantity.toJson()` in
  `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/catalog/CatalogDtos.kt`.

  ```json
  "stock": 5193.000
  ```

  **Why text and not a number in the rest, and it is a decision, not an oversight:**
  JSON has no arbitrary-precision decimals. A JavaScript `number` is a 64-bit
  `double`, and `0.1 + 0.2` is not `0.3`. Sending `5193.000` as a number
  invites the client to sum it in floating point and phantom cents appear. As a **string**,
  the precision travels intact and the client decides how to parse it.

  **Consequence to keep in mind:** the shared types of `web/`
  (`StockItem`, `KardexEntry`) declare these fields as `number`, and **they are
  wrong**. The screens that were connected had to declare local interfaces
  with the real shape. It is a pending debt: align
  `web/src/shared/api/types.ts`.
- **Identifiers**: integer. Business keys (`productCode`) are
  strings.
