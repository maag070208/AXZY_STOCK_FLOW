# M02 — Stock: stock, ledger, adjustments and reports
> **Normative.** Last verified: 2026-10-10 (phase 5 closed).

| | |
|---|---|
| **Code** | M02 |
| **Folder** | `docs/modulos/M02-existencias/` |
| **Documented** | Yes |
| **Finished (backend)** | Yes: movements, queries and reports |
| **Web** | `InventoryPage`, `KardexPage`, reports in `ReportsPage` |
| **App** | `features/inventory` |

## 1. Objective

Know how much there is of each product and where it is, and be able to explain each change. It is the stock
engine on which receiving, picking and transfers depend. It includes the reports, which
read from the same stock and the same ledger.

## 2. Scope

| Inside | Outside |
|---|---|
| Stock per product and location | Invoice capture (M03) |
| Ledger (movement book) | Transfers between locations (M05), although they use the same engine |
| Adjustments with a reason, locate and move | Labels (M06) |
| Reports: stock, movement summary, ledger vs. projection, export | |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | Each change in stock is a new movement in the ledger; the history is never edited | `V3__inventory_domain.sql` (`kardex` append-only); `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/` |
| R2 | Stock is a projection of the ledger and of the transit | `stock_by_location`, `product_stock` in `V3` |
| R3 | Every difference is an adjustment with a mandatory reason and origin | [ADR-0008](../../adr/0008-discrepancias-y-merma.md); `AdjustStock` in `stock-domain` |
| R4 | You cannot take out more than there is in the location | `INSUFFICIENT_STOCK` in `application/inventory/AdjustStock.kt`, with the balance read **with a lock** (Q9) |
| R5 | Origin and destination of a movement cannot be the same | `SAME_SOURCE_AND_DESTINATION` |
| R6 | A stock movement requires a reason when the rule demands it | `REASON_REQUIRED` |
| R7 | The total per product balances with the sum of its locations and the transit | `comprobar_proyecciones()` in the migrations; `kardex-vs-projection` report |
| R8 | Viewing the ledger requires `kardex.view`, not `inventory.view` | `@RequiresPermission` in `InventoryController.kt` |

## 4. Data model

| Table | Migration | What it holds |
|---|---|---|
| `kardex` | `V3`, `V6` | Movement ledger |
| `stock_by_location` | `V3`, `V6` | Stock per location |
| `product_stock` | `V3` | Total per product |
| `stock_in_transit` | `V3`, `V9` | Transit |

## 5. API

Stock, ledger and adjustments in [api/inventario.md](../../api/inventario.md). Reports in
[api/reportes.md](../../api/reportes.md). Routes in
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/inventory/` and (pending) in the
reports module.

## 6. Permissions

`inventory.view`, `inventory.move`, `inventory.adjust_*` (one per reason),
`inventory.approve_adjustment`, `kardex.view`, `kardex.export`, `report.view`, `report.export`.
`report.financial` is not part of this module. See
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Audit log

Each adjustment, location and movement writes to `audit_log`
([seguridad/auditoria.md](../../seguridad/auditoria.md)).

## 8. Tests

Conservation invariants in `stock-domain` (`InventoryInvariantsPropertyTest`,
`StockMovementMatrixTest`, `AdjustmentTest`, `LocationContentTest`). Route tests in the backend.

## 9. Acceptance criteria

- [x] The invariants I1–I10 hold in the property tests.
- [x] Reports and discrepancy alarm with a browser test (`web/e2e/flow.spec.ts`).
- [x] A1–A10 green: eight over HTTP and three (A3, A4, A9) against the database with two real transactions.
- [x] The query budget measured: stock and ledger are **two statements** each.
- [x] No adapter writes the inventory outside the engine (`InventoryWriteGuardTest`, tested by making it fail).
- [ ] The reports of [api/reportes.md](../../api/reportes.md): they exist, movement summary and `kardex-vs-projection`.
- [ ] Browser test of stock, ledger and adjustments. It arrives with the web (phase 11).
