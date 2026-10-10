# Audit
> **Reference.** The audit log: who did what, when and why. **Read-only.**
> Last verified: 2026-10-10 (phase 9, M08 closed).

Implemented in layers, like the rest of the API:

| Layer | File |
|---|---|
| Route | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/audit/AuditController.kt` |
| Use case and ports | `api/application/src/main/kotlin/dev/axzy/stock/application/audit/AuditQueries.kt` |
| Query | `api/adapters-persistence/.../audit/JdbcAuditQueries.kt` |
| Writing (since phase 3) | `api/adapters-persistence/.../audit/JdbcAuditRecorder.kt` |
| Wiring | `api/app/src/main/kotlin/dev/axzy/stock/app/config/AuditConfig.kt` |

The module's rules are in [M08](../modulos/M08-auditoria/README.md) and the flow in
[flujos/auditoria.md](../flujos/auditoria.md). This page documents **the query**.

## `GET /v1/audit/log`

Permission: `audit.view`. Pagination by `page` and `size`.

| Parameter | What it filters |
|---|---|
| `entityType`, `entityId` | what was acted on (`product`, `invoice`, `print_job`…) |
| `actorUserId` | who did it |
| `action` | the action (`PRODUCT_CREATED`, `INVENTORY_ADJUSTED`…) |
| `level` | `CRITICAL`, `RELEVANT`, `ACCESS`, `TECHNICAL` |
| `result` | `SUCCESS`, `FAILURE`… |
| `channel` | `WEB`, `MOBILE`, `SERVICE_PRINTER`… |
| `from`, `to` | range of **business dates** (`YYYY-MM-DD`), both inclusive |

```json
{
  "items": [
    {
      "id": 5012, "occurredAt": "2026-10-10T04:12:33.512Z", "businessDate": "2026-10-10",
      "actorUserId": 7, "actorUsername": "jperez", "actorName": "Juan Pérez",
      "actorRoles": ["JEFE_BODEGA"],
      "channel": "WEB", "entityType": "product", "entityId": "1032",
      "entityLabel": "ABRAA1B",
      "action": "PRODUCT_CREATED", "level": "RELEVANT",
      "previousState": null, "newState": "{\"productCode\":\"ABRAA1B\"}",
      "reason": null, "note": null, "permissionKey": "product.create",
      "decision": "ALLOW", "result": "SUCCESS",
      "errorCode": null, "errorMessage": null, "serviceKeyId": null,
      "requestId": "0f8…", "idempotencyKey": null
    }
  ],
  "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
}
```

**`previousState` and `newState` travel as JSON in text**, not as an object: they are free
documents and each action has its own. A fixed type would force inventing a common schema
that no action respects.

**The total is calculated with the same filter as the page.** If the count ignored them, the
envelope would say one thing and the rows another (U5).

**The dates are business dates, not instants.** The table already stores `business_date`: a closing
is explained by working day, and filtering by instants would force each client to decide
in which time zone the day begins. An inverted range answers `400 INVALID_FILTER_RANGE`
and a malformed date, `400 INVALID_FILTER_DATE`.

## What does not exist, on purpose

- **There is no `PUT` nor `DELETE`.** The table is immutable through triggers: an `UPDATE` or a
  `DELETE` fails with "audit_log is append-only". To correct a record, a new one is added that compensates for it,
  which is the only thing that keeps the audit log credible.
- There is no export to external systems (out of scope for M08).

## The query is also recorded

Querying the audit log **writes its own record**, with level `ACCESS`, action
`AUDIT_QUERIED` and the filters in the new state. Whoever looks at the audit log is seeing who
did what: it is sensitive information, and the module says that its query is recorded as
access.

It is written **after** reading: if the record failed, the query has already been made, and what must not
remain is the fact without a trace.

## Permissions and errors

| Code | HTTP | When |
|---|---|---|
| `PERMISSION_DENIED` | 403 | query without `audit.view` |
| `INVALID_FILTER_RANGE` | 400 | `from` later than `to` |
| `INVALID_FILTER_DATE` | 400 | a date that is not `YYYY-MM-DD` |
| `INVALID_PAGINATION` | 400 | `page` or `size` out of range |

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/AuditFlowTest.kt` | U5 (filters and pagination with the total of the same filter), the filters by action, actor and level, that the record carries who/what/when/why, that a `CRITICAL` carries its permission and its trace, that the query is recorded as access, the filter errors, the permission and the immutability of the table (U2) |
| `stock-domain/src/commonTest/.../audit/` | the rules of the record, without a database |
