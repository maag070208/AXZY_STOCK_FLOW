# ADR-0009 — The receiving location is a real location
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 (decision B7 and R-65 of the original plan) |
| **Replaces** | — |
| **Replaced by** | — |

## Context

In the legacy, what was received and not located was kept in a pseudo-location `ADMIN` that was not
a real location: it did not have an id in the same sense as the others, and the stock stayed
there without a physical place. Moreover, locating was a mutation of the ledger, not a movement.

## Decision

- Receiving has a real location with its id, marked `kind = RECEIVING`
  (`V5__unassigned_location_kind.sql`, `V6__unassigned_location_and_inventory_engine.sql`).
- Locating is a new movement: the product's total does not change and the historical record
  is kept (R-65). This is a consequence of [ADR-0008](0008-discrepancias-y-merma.md)
  and of the append-only ledger.
- The receiving location is protected: it cannot be deactivated
  (`RECEIVING_LOCATION_PROTECTED`, `catalog/location/LocationRoutes.kt`).
- The initial stock from the import goes to its own location `UNASSIGNED`, not to receiving
  (see [migracion/catalogo-excel.md](../migracion/catalogo-excel.md)).

## Consequences

- A product received and not located has a visible balance in `RECEIVING`. It does not disappear.
- The rule "do not locate a line in discrepancy" (ADR-0008) relies on receiving being a location
  with its own rules.

## Discarded alternatives

- **Keep `ADMIN` as a pseudo-location.** It hides the stock outside the location model.
- **Locate by mutating the receiving record.** It makes it impossible to reconstruct what was received (B6 of the
  original plan).
