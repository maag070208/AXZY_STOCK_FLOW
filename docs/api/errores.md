# API errors
> **Reference.** The common error body for all endpoints and the closed catalog
> of codes. Last verified: 2026-10-09.

The status and header rules are in [convenciones.md](convenciones.md).
The error body is defined by `ErrorBody` in
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/http/ErrorResponses.kt`; the same
file translates each failure class to its status code. What is not a failure of a use case
(unreadable JSON, nonexistent route, unexpected error) is answered by `GlobalErrorHandler.kt`, in the same
folder, with the same body.

## 1.3 The error contract — it is the same in all endpoints

```json
{
  "code": "POLICY_DENIED",
  "message": "You cannot approve your own count (segregation of duties)",
  "details": { "field": "reason" }
}
```

**The client decides with `code`, never with `message`.** The message is translated and rewritten;
a client that compares text breaks silently. The codes are
stable and in `SCREAMING_SNAKE_CASE`. `details` is optional and its shape depends on the
code (`available`, `until`, `stock`, `pendingLines`, `allowed`…).

The grouping by status in the following tables is indicative: the same `code`
can accompany different statuses depending on the route (for example
`INVALID_PRODUCT_CODE` is `422` when creating a product and `400` when adjusting
inventory). **The client decision is made by the `code`; the status indicates the
immediate action.**

Authorization codes, which **the client must tell apart** because the user's action
is different:

| `code` | Meaning | What the user must do |
|---|---|---|
| `UNAUTHENTICATED` | token missing or expired | sign in |
| `PERMISSION_DENIED` | does not have the permission | request it from an administrator |
| `SCOPE_INSUFFICIENT` | has the permission, but it does not reach this resource | request access to that warehouse |
| `POLICY_DENIED` | a policy forbids it | change **when** or **how** it is done |
| `SEGREGATION_OF_DUTIES` | cannot approve what they themselves captured | have another person approve it |
| `APPROVER_NOT_AUTHORIZED` | 403 | whoever approves the adjustment does not have `inventory.approve_adjustment` in that warehouse |
| `APPROVER_NOT_FOUND` | 422 | the `approverId` does not correspond to any user |

Collapsing the five into a generic `403` is the legacy defect: the user receives
"not authorized" and does not know whether to request a permission, wait, or call another person.

## 1.3-bis Error code catalog

**Closed list.** A new code is added here before it is used, and the client only
needs to contemplate these. Writing them in the contract avoids the usual
problem: the backend invents a code, the client does not recognize it and falls back to the
generic message.

Request errors (§1.4):

| `code` | Situation |
|---|---|
| `INVALID_PAGINATION` | `page` or `size` out of range. **A `size` > 200 is rejected, not trimmed** |
| `INVALID_SORT` | the field or direction of `sort` is not supported on that endpoint |
| `INVALID_ID` | the identifier in the route is not a number |
| `INVALID_PARAMETER` | a query parameter has an unacceptable value |
| `INVALID_IDEMPOTENCY_KEY` | the `Idempotency-Key` header is not a UUID |
| `FILE_REQUIRED` | the file is missing from a `multipart` request |
| `FILE_TOO_LARGE` | the file exceeds the limit |
| `EMPTY_FILE` | the file has no rows |
| `IMPORT_FILE_FORMAT` | the format is neither canonical CSV nor XLSX |
| `INVALID_DRY_RUN` | `dryRun` is not a boolean |

Field validation errors (§422, with `details` per field):

| `code` | Situation |
|---|---|
| `FIELD_REQUIRED` | a required field is missing |
| `FIELD_TOO_LONG` | exceeds the column length |
| `INVALID_PRODUCT_CODE` | does not meet the alphabet `0-9A-Z` or the length 6–14 |
| `UNKNOWN_UNIT_MEASURE` | the unit is not in the catalog |
| `INVALID_LEVELS` | the number of levels is not positive |
| `INVALID_LOCATION_KIND` | `kind` is neither `SHELF` nor `RECEIVING` |
| `INVALID_LOCATION_PART` | `hall`/`code`/`position` do not meet the format |
| `UNKNOWN_WAREHOUSE` | the warehouse does not exist |
| `IMPORT_INVALID_EXTERNAL_ID` | `external_id` is not numeric. **It is rejected instead of being stored as null**: it would silently lose the cross-reference with the ERP |

Nonexistent resource errors (§404):

| `code` | Situation |
|---|---|
| `NOT_FOUND` | the route does not exist. Emitted by `GlobalErrorHandler`, not by a use case |
| `PRODUCT_NOT_FOUND` | the product `{id}` does not correspond to any product |
| `LOCATION_NOT_FOUND` | the location `{id}` does not correspond to any location |

`PRODUCT_NOT_FOUND` and `LOCATION_NOT_FOUND` are emitted by the domain
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`)
and belong to the closed list: a client that decides by `code` distinguishes "the
product does not exist" from "the route does not exist", which for the user are two different
messages. A test (`RoutePermissionTest`) checks against this catalog that every declared
permission exists; the error codes are checked with the exhaustive
`when` of `Failure.toFailure()`: a new error in the domain does not compile until
it is decided what it means for the client.

Conflict errors with the current state (§409):

| `code` | Situation |
|---|---|
| `DUPLICATE_PRODUCT_CODE` | that code already exists |
| `DUPLICATE_EXTERNAL_ID` | that ERP identifier already exists |
| `DUPLICATE_LOCATION` | that location already exists in the warehouse |
| `PRODUCT_CODE_IMMUTABLE` | an attempt was made to change a product's code. **The implementation returns it as `422`** |
| `WAREHOUSE_IMMUTABLE` | an attempt was made to move a warehouse location. **The implementation returns it as `422`** |
| `PRODUCT_ALREADY_INACTIVE` | it was already deactivated |
| `LOCATION_ALREADY_INACTIVE` | it was already deactivated |
| `RECEIVING_LOCATION_PROTECTED` | the receiving location cannot be deactivated: the location flow needs it |
| `LOCATION_HAS_STOCK` | the location has stock: `details.quantity` |
| `PRODUCT_HAS_STOCK` | the product has stock: `details.stock` |
| `IMPORT_INVALID_ROWS` | the file has invalid rows and **nothing was written**; `details` carries one `row_N` entry per row, up to 50 |
| `IMPORT_HAS_REJECTIONS` | **declared and not emitted**: it was the legacy's response for the same case that today responds `IMPORT_INVALID_ROWS` |

### Codes emitted by the implementation that the closed list did not enumerate

The implementation emits the following codes; they are added to the catalog so that
the client can contemplate them. They come from the routes and services of each module
(`application/receiving/ReceivingUseCases.kt`, `order/OrderService.kt`,
`application/transfer/TransferUseCases.kt`, `label/LabelService.kt`,
`audit/AuditRoutes.kt`) and from the domain errors
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`).

| `code` | Status | Situation |
|---|---|---|
| `INVALID_FILTER` | 400 | a query filter has an unacceptable value; **it is rejected, not ignored** |
| `INVALID_FILTER_RANGE` | 400 | `from` is later than `to`; an inverted range returns nothing |
| `INVALID_FILTER_DATE` | 400 | the date is neither ISO (`YYYY-MM-DD`) nor an ISO-8601 instant |
| `INVALID_LOCATION_CODE` | 400 | the location code is not valid |
| `DUPLICATE_KEY` | 409 | uniqueness violation detected by the database |
| `DUPLICATE_WAREHOUSE_CODE` | 409 | a warehouse with that code already exists |
| `DUPLICATE_INVOICE_NUMBER` | 409 | an invoice with that number already exists |
| `INVOICE_ALREADY_CLOSED` | 409 | the invoice was already closed |
| `INVOICE_HAS_PENDING_LINES` | 422 | there are lines that prevent closing: `details.pendingLines` |
| `LINE_ALREADY_RESOLVED` | 409 | the line is already approved or located and cannot be captured again |
| `NO_PENDING_APPROVAL` | 409 | the invoice has no discrepancy lines to approve or reject |
| `NOT_PENDING_APPROVAL` | — | **Only in `stock-domain`**: a line that is not in `PENDING_APPROVAL` cannot be approved. The receiving API does not emit it; the invoice case with no pending items is `NO_PENDING_APPROVAL` |
| `INVALID_TRANSITION` | 409 | the current state does not allow the requested transition |
| `INVALID_QUANTITY` | 422 | the quantity is not valid or is not positive |
| `INCONSISTENT_BOX_COUNT` | 422 | boxes and units do not match `unitsPerBox` |
| `BOXES_DO_NOT_DIVIDE_EVENLY` | 422 | the boxes do not divide the quantity exactly |
| `EXCEEDS_LINE_QUANTITY` | 422 | what was captured exceeds what was invoiced |
| `CANNOT_DELETE_WITH_INVENTORY_EFFECT` | 422 | the deletion would affect inventory. **The domain error declares it; no route of this API emits it yet** |
| `EMPTY_ORDER` | 422 | the order has no lines |
| `EXCEEDS_PENDING_QUANTITY` | 422 | more is packed than is pending on the line |
| `LOCATION_REQUIRED` | 422 | the line has no location to leave from |
| `ORDER_LINE_NOT_FOUND` | 404 | the line does not exist or does not belong to that order |
| `ORDER_NOT_FOUND` | 404 | the order does not exist |
| `ORDER_ALREADY_CONFIRMED` | 409 | the order was already confirmed |
| `ORDER_NOT_CONVERTED` | 409 | an attempt was made to export an unconfirmed order |
| `EMPTY_TRANSFER` | 422 | the transfer has no lines |
| `SAME_WAREHOUSE` | 422 | origin and destination are the same warehouse |
| `TRANSFER_NOT_FOUND` | 404 | the transfer does not exist |
| `NO_RECEIVING_LOCATION` | 422 | the destination warehouse has no receiving location |
| `NOTHING_IN_TRANSIT` | 409 | the transfer has no goods in transit to receive |
| `INVOICE_NOT_FOUND` | 404 | the invoice does not exist |
| `LINE_NOT_FOUND` | 404 | the invoice line does not exist |
| `LABEL_TEMPLATE_NOT_FOUND` | 404 | the template does not exist |
| `LABEL_TEMPLATE_INACTIVE` | 422 | an attempt was made to print with a deactivated template |
| `LABEL_ALREADY_PRINTED` | 409 | the job is already printed: a retry does not print twice |
| `PRINT_JOB_NOT_FOUND` | 404 | the print job does not exist |
| `MISSING_LABEL_FIELD` | 422 | required label data is missing |
| `INVALID_ZPL` | 422 | the ZPL does not contain `^XA` and `^XZ` |
| `INVALID_SERVICE_KEY` | 401 | the `X-Service-Key` header is missing or the key is not valid |
| `INTERNAL_ERROR` | 500 | uncontrolled error; the detail is **not** leaked to the client |
| `RATE_LIMITED` | 429 | too many attempts. **Declared in the closed list; the implementation does not yet have a rate limiter** |

### Session

Emitted by the use cases in `api/application/src/main/kotlin/dev/axzy/stock/application/identity/`.
The detail of each route is in [autenticacion.md](autenticacion.md).

| `code` | Status | Situation |
|---|---|---|
| `INVALID_CREDENTIALS` | 401 | wrong user or password; unknown, revoked or expired refresh token; wrong current password |
| `ACCOUNT_LOCKED` | 401 | five credential failures with that user name in 15 minutes. **It applies the same way to names that do not exist** |
| `ACCOUNT_DISABLED` | 401 | the account is deactivated. On login it is only returned if the password is correct |
| `TOKEN_REUSED` | 401 | an already rotated refresh token was reused: the entire family was revoked |
| `UNAUTHENTICATED` | 401 | token missing, invalid, expired, or the password changed after it was issued |
| `PASSWORD_CHANGE_REQUIRED` | 403 | the account must change its password before using any other route |
| `WEAK_PASSWORD` | 422 | the new password does not meet the policy |
| `SAME_PASSWORD` | 422 | the new password is the same as the current one |

### Transport

Emitted by `GlobalErrorHandler.kt`: the request did not reach a use case.

| `code` | Status | Situation |
|---|---|---|
| `MALFORMED_REQUEST` | 400 | the body is not valid JSON, or a field has a type that cannot be read |
| `METHOD_NOT_ALLOWED` | 405 | the HTTP method is not allowed on that route |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | the `Content-Type` is not `application/json` |
| `NOT_FOUND` | 404 | the route does not exist. **Without identity, a nonexistent route answers `401`**, not `404` |

`INTERNAL_ERROR` carries an identifier in `details.incident`. With it, the complete error is
looked up in the log, which is never sent to the client.

## 1.3-ter The catalog is closed

> **Normative.** The codes are in this list and are **not invented**. A new error is
> added to the catalog **before** it is used.

**A `500` is a lie.** It says "the server failed" when the server is
fine. A client that retries on a `500` **retries forever**; on a
`400`, it corrects.

A value the client wrote wrong (a date, a UUID, a PostgreSQL enum value) is validated in Kotlin and
answered with `400`; it is not allowed to reach the database's `CAST`,
which would throw a `500` for an incorrect request.
