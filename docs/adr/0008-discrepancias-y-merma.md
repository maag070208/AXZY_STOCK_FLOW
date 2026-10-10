# ADR-0008 — Discrepancies and shrinkage: a single adjustment rule, approved per invoice
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 (decisions B1, B2, B3, R-74, R-75, R-86 of the original plan) |
| **Replaces** | — |
| **Replaced by** | — |

## Context

In the legacy, the difference between what was invoiced and what was counted had no clear control: the
discrepancy was corrected without a record with a reason or a second signer. The rewrite plan
set three rules: block until approved, never delete, and a single adjustment rule for all the origins.

## Decision

1. **Block.** A line whose physical quantity does not match the invoiced one stays in
   `DISCREPANCY` and cannot be located. It only reaches `APPROVED` after the approval of a supervisor with
   `invoice.approve` (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/receiving/InvoiceLine.kt`).
2. **No threshold.** Every difference requires approval (R-75).
3. **Approval per invoice, not per line** (R-86). The supervisor resolves all the discrepancies of the invoice in one
   action: `POST /v1/invoices/{id}/approve` (`receiving/ReceivingRoutes.kt`).
4. **Segregation of duties.** Whoever captured a line cannot approve it
   (`SEGREGATION_OF_DUTIES`, `security/PermissionChecker.kt`; R-74).
5. **The database enforces it.** A line does not reach `APPROVED` without `approved_by`
   (`CHECK` in `V3__inventory_domain.sql`).
6. **Never delete.** Every difference is an adjustment movement with a mandatory reason and user (B2). The
   ledger is append-only: correcting means adding.
7. **A single rule.** Inventory adjustment, shortage in picking, count and damage are
   `AdjustStock` with a different `origin` (B3): `INVOICE_DISCREPANCY`, `FULFILLMENT_SHORTAGE`,
   `MANUAL_COUNT`, `DAMAGE_REPORT` (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/Adjustment.kt`).

## Consequences

- A supervisor who approves must see the full invoice; they cannot approve per line
  even though the API would allow it, because that route does not exist.
- An invoice with a discrepancy is not closed until it is resolved
  (`INVOICE_HAS_PENDING_LINES`, `application/receiving/ReceivingUseCases.kt`).
- If nothing is pending approval, the API answers `409 NO_PENDING_APPROVAL`
  (`application/receiving/ReceivingUseCases.kt`).
- The price is paid in the supervisor's time: each discrepancy goes through a person. It is the cost
  accepted so as not to have an unsigned discrepancy.

## Discarded alternatives

- **Approve per line** (R-86): it forces repeating the same judgment N times and leaves the invoice in
  an intermediate state.
- **Tolerance threshold** (R-75): any threshold is an unsigned shrinkage decision.
- **Two separate rules** (shrinkage and discrepancy): two places to make mistakes.
