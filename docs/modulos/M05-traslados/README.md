# M05 — Transfers: between locations and between warehouses
> **Normative.** Last verified: 2026-10-10 (phase 8 closed).

| | |
|---|---|
| **Code** | M05 |
| **Folder** | `docs/modulos/M05-traslados/` |
| **Documented** | Yes |
| **Finished (backend)** | Yes |
| **Web** | `TransfersPage` |
| **App** | No screen |

## 1. Objective

Move stock from one location to another, or from one warehouse to another, with the goods in transit
counted until they arrive.

## 2. Scope

| Inside | Outside |
|---|---|
| Transfer with lines: draft, dispatch, receipt, cancellation | Locating a received invoice (M03) |
| Transit between warehouses | Operation with more than one warehouse in production ([ADR-0007](../../adr/0007-multi-bodega-desde-el-esquema.md)) |
| Query of goods in transit | |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | Origin and destination of a transfer between warehouses are different | `transfers_different_warehouses` (`V3`); `transit_warehouses_differ` (`V9`) |
| R2 | The domain checks the rule before touching the database, to give an error that explains the field | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/transfer/` |
| R3 | State transitions follow the transfer's state machine | `TransferStateMachineTest` in `stock-domain` |
| R4 | Viewing the goods in transit requires `transit.view`, different from `transfer.view` | `transfer/TransferRoutes.kt` (`/in-transit`) |

## 4. Data model

| Table | Migration | What it holds |
|---|---|---|
| `transfers` | `V3` | Header and state (`DRAFT`, `DISPATCHED`, `IN_TRANSIT`, `RECEIVED`, `CANCELLED`) |
| `transfer_lines` | `V9` | Transfer lines |
| `stock_in_transit` | `V3`, `V9` | Debt of each transfer not received |

## 5. API

Contract in [api/traslados.md](../../api/traslados.md). Routes in `transfer/TransferRoutes.kt`.

## 6. Permissions

`transfer.view`, `transfer.create`, `transfer.dispatch`, `transfer.receive`, `transfer.cancel`,
`transit.view`. See
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Audit log

Each state change writes to `audit_log`.

## 8. Tests

`TransferStateMachineTest` in `stock-domain`. Routes in the backend.

## 9. Acceptance criteria

- [x] A transfer between the same warehouse is rejected (`TransferStateMachineTest`).
- [x] Debt in transit and dispatch with a browser test (`web/e2e/flow.spec.ts`). Receipt is not covered.
