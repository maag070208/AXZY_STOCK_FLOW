# ADR-0007 — Multi-warehouse from the schema, operating with one
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The legacy model had a single total per product (`Store.stock`) and deduced the location
after the fact by compensating with subtractions; hence the discrepancy
(`backend/src/main/resources/db/migration/V3__inventory_domain.sql`). The new system is born
with the per-location projection as the source of truth and with the warehouse as a first-class
entity.

The business confirmed there will be more than one warehouse. The decision, therefore, is not whether there will be
several, but when the cost of modeling them is paid. It is paid in the schema from the start:
changing the inventory schema later forces migrating data already in use.

## Decision

- The schema supports N warehouses from `V3__inventory_domain.sql`: the table `warehouses`,
  `locations.warehouse_id`, `stock_by_location.warehouse_id`, `transfers` with origin
  and destination warehouse, and `stock_in_transit`.
- The operation starts with a single warehouse: the seed of V3 creates one with
  `is_default = true`, and the partial unique index `warehouses_one_default_idx` prevents there being
  more than one default (`V3__inventory_domain.sql`).
- Adding the second warehouse is an `INSERT`. **It requires no migration.**
- A transfer within the same warehouse is forbidden in the header
  (`transfers_different_warehouses`) and in transit (`transit_warehouses_differ`,
  `V9__transfer_lines.sql`). The domain checks it before touching the database so it can
  return an error that explains the field instead of a constraint error
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`).
- The transfer lines and the link of transit to its transfer live in
  `V9__transfer_lines.sql`: the table `transfer_lines` and `stock_in_transit.transfer_id`,
  so that the debt of a transfer not received can be attributed.
- Every new operation that touches stock declares `warehouse_id` and respects the constraints of
  distinct warehouses.
- The `AREA` scope of permissions means "their warehouse" and is resolved against the person's warehouses,
  not against a role per warehouse
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessModel.kt`,
  `RoleDefinitions.kt`).

## Consequences

When the second warehouse arrives there is no data migration and no schema change. Reports
per warehouse are resolved with the denormalized `warehouse_id` in `stock_by_location`
(`V3__inventory_domain.sql`).

The price is that no query can assume a single warehouse from today, and that each new stock
table must carry its warehouse and its constraint. The total per product is still a
projection of the locations plus transit, not a number maintained by hand
(`V3__inventory_domain.sql`, `V9__transfer_lines.sql`).

Operational rules that follow:

- The default warehouse is not hardcoded: neither its `id` nor its code `PRINCIPAL`. `is_default` is
  a capture convenience, not an identity (`V3__inventory_domain.sql`).
- Goods in transit **still count** in the total inventory. Modeling them as two instantaneous
  movements would make the total drop during the trip and the reports would lie
  (`V3__inventory_domain.sql`).
- A transfer never received is a visible debt and it has to be possible to attribute it to its transfer
  (`V9__transfer_lines.sql`).
- Debt already fixed, worth remembering: V3 created `transfers` without lines and
  `stock_in_transit` without `transfer_id`, so a transfer could not say what it carried nor
  attribute a transit row to itself (`V9__transfer_lines.sql`).

## Alternatives considered

- **Model a single warehouse and migrate when the second arrives.** The migration would touch
  the stock tables with data inside, which is the cost this decision avoids
  (`V3__inventory_domain.sql`).
- **Treat the warehouse as a constant or an enum.** It cannot grow and does not allow adding the
  second without touching code.
- **Filter by warehouse only in the reports.** The total would still be one and a discrepancy
  could not be attributed to any warehouse (`V3__inventory_domain.sql`).

## References

- `backend/src/main/resources/db/migration/V3__inventory_domain.sql`
- `backend/src/main/resources/db/migration/V9__transfer_lines.sql`
- `backend/src/main/kotlin/dev/axzy/stock/backend/catalog/warehouse/WarehouseRoutes.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/application/transfer/TransferUseCases.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessModel.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/RoleDefinitions.kt`
- [roles-permisos.md](../seguridad/roles-permisos.md)
