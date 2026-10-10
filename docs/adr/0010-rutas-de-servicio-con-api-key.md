# ADR-0010 — System routes with a service key, not a user
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 (decision B8 of the original plan) |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The legacy had three routes without authentication: `GET /kardo` (imports invoices),
`PATCH /prints` (marks labels as printed) and `DELETE /invoices/detail/:id` (physical deletion).
The first two are used by systems, not by people: the Zebra printer and the ERP.

## Decision

- The print confirmation (`PATCH /v1/print-jobs/{id}`) is authenticated with a
  **service key**, not with a user token. The table `service_api_keys` stores it
  (`V2__audit_and_traceability.sql`); its use is in `label/LabelService.kt`.
- A label stays `PENDING` until the printer confirms, and it retries if the
  printer is off (R-76).
- The `DELETE` of an invoice is **not** kept as an open route: it is closed with
  authentication. It was an oversight, not a system need.
- The service key carries no user permissions and no warehouse scope.

## Consequences

- There is one more kind of credential to rotate and keep safe.
- The ERP does not have a key defined yet: the contract of the dispatch is in
  [ADR-0013](0013-ordenes-al-erp-con-reintentos.md) and its authentication remains pending.
- Each use of a service key is recorded in the audit log with the system that called.

## Discarded alternatives

- **Leave the routes open.** It is the legacy finding S1.
- **Give the printer a user account.** It mixes a human identity with a device and makes its permissions
  reviewed as if they belonged to a person.
