# M09 — External integration: ERP and legacy
> **Plan.** Module not built on the ERP side. Last verified: 2026-10-09.

| | |
|---|---|
| **Code** | M09 |
| **Folder** | `docs/modulos/M09-integracion-externa/` |
| **Documented** | Partial |
| **Finished (backend)** | No |
| **Web** | Not applicable |
| **App** | Not applicable |

## 1. Objective

Exchange data with systems that are not StockFlow: return the picked orders to the ERP and, if needed,
read what the previous system left in its database.

## 2. Scope

| Inside | Outside |
|---|---|
| Export of confirmed orders to the ERP | Printing (M06, confirmed by the printer with a service key) |
| Retries and idempotency of the dispatch | Product synchronization (the source is the API, [ADR-0011](../../adr/0011-catalogo-fuente-maestra.md)) |
| Reading the legacy during the migration | |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | Orders are sent to the ERP with `Idempotency-Key` (contract decided by us; the real dispatch does not exist yet) | [ADR-0013](../../adr/0013-ordenes-al-erp-con-reintentos.md); `order/OrderService.kt` (`POST /v1/orders/{id}/export`) |
| R2 | The ERP's authentication is not defined. The service key covers printing and the legacy's `kardo` today | [ADR-0010](../../adr/0010-rutas-de-servicio-con-api-key.md) |
| R3 | The ERP's `external_id` is pending a decision | Decision D10 in [migracion/cutover.md](../../migracion/cutover.md) |

## 4. Data model

The order field that records the dispatch attempt is defined by `V3__inventory_domain.sql`. There is no
integration table of its own.

## 5. API

`POST /v1/orders/{id}/export` (M04) records the attempt. `GET /v1/orders/{id}` returns its dispatch status.
The response does not include `nextRetryAt` ([api/surtido.md](../../api/surtido.md)).

## 6. Permissions

`order.convert`. The service key does not carry user permissions.

## 7. Audit log

Each dispatch attempt writes to `audit_log`.

## 8. Tests

Pending: there is no test against a real or simulated ERP.

## 9. Acceptance criteria

- [ ] The real dispatch reaches the ERP and is retried if it fails.
- [ ] Decision on the legacy's `kardo` module: keep or discard.
