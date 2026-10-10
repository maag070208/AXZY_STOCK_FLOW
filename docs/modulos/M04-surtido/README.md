# M04 — Picking: orders and packing
> **Normative.** Last verified: 2026-10-10 (phase 7 closed).

| | |
|---|---|
| **Code** | M04 |
| **Folder** | `docs/modulos/M04-surtido/` |
| **Documented** | Yes |
| **Finished (backend)** | Yes, except the real dispatch to the ERP |
| **Web** | `OrdersPage`, `OrderDetailPage` |
| **App** | `features/orders` |

## 1. Objective

Pick an order: assign stock to each line, pack it and confirm it. What cannot be picked is recorded as a shortage with its adjustment.

## 2. Scope

| Inside | Outside |
|---|---|
| Creation and query of orders | Transfers (M05) |
| Packing per line and confirmation | Real dispatch to the ERP ([ADR-0013](../../adr/0013-ordenes-al-erp-con-reintentos.md)) |
| Shortages with an adjustment of origin `FULFILLMENT_SHORTAGE` | |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | A shortage is an adjustment with a reason, not a line deletion | [ADR-0008](../../adr/0008-discrepancias-y-merma.md); `order/OrderRoutes.kt` (`/shortage`) |
| R2 | An order that is not confirmed is not exported to the ERP | `ORDER_NOT_CONVERTED` in `order/OrderService.kt` |
| R3 | The order's state machines do not allow jumps | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/fulfillment/` (`OrderStateMachineTest`) |

## 4. Data model

| Table | Migration | What it holds |
|---|---|---|
| `orders` | `V3` | Orders, with state `PENDING` → `COMPLETED` |
| `order_lines` | `V3` | Lines, with state `PENDING`, `ASSIGNED`, `SUPPLIED`, `CANCELLED` |

## 5. API

Contract in [api/surtido.md](../../api/surtido.md). Routes in `order/OrderRoutes.kt`.

## 6. Permissions

`order.view`, `order.create`, `order.package`, `order.confirm`, `order.convert` (the export to the
ERP uses `order.convert`). See
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Audit log

Packing, confirmation, export and shortage write to `audit_log`.

## 8. Tests

`OrderStateMachineTest` in `stock-domain`. Routes in the backend.

## 9. Acceptance criteria

- [x] The state machine rejects illegal transitions (`OrderStateMachineTest`).
- [ ] The export reaches the ERP and is retried if it fails ([ADR-0013](../../adr/0013-ordenes-al-erp-con-reintentos.md)).
- [x] Listing, creation and packing with a browser test (`web/e2e/flow.spec.ts`). Confirmation and dispatch are not covered.
