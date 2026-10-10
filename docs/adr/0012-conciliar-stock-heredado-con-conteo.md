# ADR-0012 — Reconcile the legacy stock with a physical count before the cutover
> **Normative.** Accepted decision; execution pending. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted. Execution pending |
| **Date** | 2026-10-09 (decision R-73 and R-81 of the original plan) |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The inventory Excel has a total of 10,914,130 units for 1,398 products, but it does not
carry the breakdown by location. The total is considered reliable; the breakdown does not exist in the
source. Loading it into a single location would misstate the inventory from day one.

## Decision

- **Before cutting over**, a physical count of the 1,398 products is done and reconciled against
  the inherited total. It is a separate project, with its own count screen.
- The initial stock enters `SIN-UBICAR` ([ADR-0009](0009-ubicacion-de-recepcion-real.md)) and
  leaves that location when it is located with a count, not with an unsigned adjustment.
- The cutover is not done until the reconciliation balances or the differences have an
  adjustment with a reason ([ADR-0008](0008-discrepancias-y-merma.md)).

## Consequences

- The cutover depends on an operational task outside the code. Without it, the migration does not close.
- The cutover plan and its reversal are in [migracion/cutover.md](../migracion/cutover.md).

## Discarded alternatives

- **Trust the Excel total without counting.** It is what leaves the breakdown invented.
- **Adjust in bulk when importing.** A mass adjustment without a count has no verifiable reason.
