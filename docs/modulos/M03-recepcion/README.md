# M03 — Receiving: invoices, physical count and discrepancies
> **Normative.** Last verified: 2026-10-10 (phase 6 closed).

| | |
|---|---|
| **Code** | M03 |
| **Folder** | `docs/modulos/M03-recepcion/` |
| **Documented** | Yes |
| **Finished (backend)** | Yes |
| **Web** | `InvoicesPage`, `InvoiceDetailPage`, `PendingApprovalPage` |
| **App** | `features/invoices` |

## 1. Objective

Record what arrives on an invoice, physically count each line, resolve the
differences with a supervisor and leave the goods located.

## 2. Scope

| Inside | Outside |
|---|---|
| Creation of invoices and lines | Electronic invoicing |
| Physical count of each line | Shelf location (M02, with `invoice.locate`) |
| Discrepancy inbox and approval per invoice | Picking (M04) |
| Invoice closing | |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | A line with a difference from what was invoiced stays in `DISCREPANCY` and cannot be located (the locate route receives `invoiceLineId` and applies the domain rule **before moving anything**) | [ADR-0008](../../adr/0008-discrepancias-y-merma.md); `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/receiving/InvoiceLine.kt` |
| R2 | Every difference requires a supervisor's approval, with no threshold | [ADR-0008](../../adr/0008-discrepancias-y-merma.md) |
| R3 | Approval is per invoice, not per line | `ApproveInvoice` in `api/application/.../receiving/InvoiceUseCases.kt`, with a single write statement |
| R4 | Whoever captures a line cannot approve its discrepancy | `SEGREGATION_OF_DUTIES` (403) in `ApproveInvoice`, with `details.lines` |
| R5 | An invoice is not closed with lines in discrepancy | `INVOICE_HAS_PENDING_LINES` in `CloseInvoice`, with the criterion of `InvoiceLineStatus.blocksInvoiceClosing` |
| R6 | If nothing is pending approval, it answers `NO_PENDING_APPROVAL` (409) | `application/receiving/ReceivingUseCases.kt` |
| R7 | The invoice listing rejects a negative `page` instead of trimming it | `PageRequest.analyze` (cross-cutting rule), checked in `ReceivingFlowTest` |

## 4. Data model

| Table | Migration | What it holds |
|---|---|---|
| `invoices` | `V3` | Invoices, with their state |
| `invoice_lines` | `V3` | Lines, with their state and the approval `CHECK` |

## 5. API

Contract in [api/recepcion.md](../../api/recepcion.md). Routes in
`infrastructure/inbound/http/receiving/ReceivingRoutes.kt`.

## 6. Permissions

`invoice.view`, `invoice.create`, `invoice.capture`, `invoice.approve`, `invoice.reject`,
`invoice.close`, `invoice.locate`. The operator locates with scope `OWN` (decision R-77, in
`docs/historico/PLAN_ALMACEN_INTEGRAL.md`). See
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Audit log

Capture, approval, rejection and closing write to `audit_log`.

## 8. Tests

`InvoiceLineStateMachineTest` in `stock-domain`, and route tests in the backend.

## 9. Acceptance criteria

- [x] A line in discrepancy cannot be located (`InvoiceLineStateMachineTest`).
- [ ] Browser test of capture, approval and closing. It does not exist.
