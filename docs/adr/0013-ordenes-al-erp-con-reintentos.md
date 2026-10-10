# ADR-0013 — Fulfilled orders are returned to the ERP with retries and idempotency
> **Normative.** Accepted decision; real dispatch pending. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted. Real dispatch pending |
| **Date** | 2026-10-09 (decisions R-72 and D10 of the original plan) |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The ERP is still in use and needs the orders already converted. In the legacy that conversion
never existed: it is a new capability, not a correction. There is no prior contract to respect,
so this system defines it.

## Decision

- The export is triggered when the packing is confirmed: `POST /v1/orders/{id}/export`
  (`order/OrderRoutes.kt`, with `order.convert`).
- An order that is not confirmed is not exported (`ORDER_NOT_CONVERTED`,
  `order/OrderService.kt`).
- Each dispatch carries an `Idempotency-Key`: a retry does not duplicate the order in the ERP.
- Failures are kept with a status (`PENDING`, `SENT`, `FAILED`) and are retried with backoff.
- The ERP's `external_id` (decision D10) is **pending**: it is not defined until the
  identifier the ERP accepts is known.

## Implementation status

The contract and the recording of the attempt exist; the actual dispatch does not. The response of
`export` records the attempt and does not include `nextRetryAt`
([api/surtido.md](../api/surtido.md)). Until the dispatch exists, this ADR describes the
design, not the behavior.

## Consequences

- The ERP client and its authentication must be implemented, and today they do not exist
  ([ADR-0010](0010-rutas-de-servicio-con-api-key.md)).
- An ERP failure does not block picking: the order is left `FAILED` and retried.

## Discarded alternatives

- **Synchronous dispatch inside the confirmation.** An ERP that is down would block the packing.
- **Send without idempotency.** A retry would duplicate orders in the ERP.
