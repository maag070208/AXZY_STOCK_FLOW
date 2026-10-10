# Entity-relationship
> **Reference.** Which tables there are, in which migration they are born and how they relate. The column detail
> is in the migrations themselves: `backend/src/main/resources/db/migration/`.
> Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** The schema in force is the migrations in `db/migrations/` (V1 to V15). The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

## Tables by domain

| Domain | Tables | Migration |
|---|---|---|
| Identity | `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `user_warehouse_scopes` | `V1__identity.sql` |
| Sessions | `refresh_tokens`, `login_attempts` | `V4__sessions.sql` |
| Audit | `audit_log`, `audit_log_default`, `access_counter`, `config_versions`, `service_api_keys` | `V2__audit_and_traceability.sql` |
| Catalog and stock | `warehouses`, `locations`, `units_of_measure`, `products`, `stock_by_location`, `product_stock`, `stock_in_transit`, `kardex` | `V3__inventory_domain.sql` |
| Receiving | `invoices`, `invoice_lines` | `V3__inventory_domain.sql` |
| Picking | `orders`, `order_lines` | `V3__inventory_domain.sql` |
| Transfers | `transfers`, `transfer_lines` | `V3__inventory_domain.sql`, `V9__transfer_lines.sql` |
| Labels | `label_templates`, `print_jobs` | `V3__inventory_domain.sql` |

`V5`, `V6`, `V7` and `V8` do not create new tables: they add the receiving location, the stock engine, the audit write with verification, and the renaming of columns to English.

## Key relationships

- `locations` → `warehouses`: every location belongs to a warehouse.
- `stock_by_location` → `products`, `locations`, `warehouses`: the stock projection.
  `warehouse_id` is denormalized for the reports per warehouse ([ADR-0007](../adr/0007-multi-bodega-desde-el-esquema.md)).
- `kardex` → `products`, `locations`: the append-only ledger. It has no business `UPDATE` nor `DELETE`.
- `invoice_lines` → `invoices`, `products`: a line never changes product.
- `transfers` → `warehouses` (distinct origin and destination), `transfer_lines`, `stock_in_transit`.
- `audit_log`: immutable through triggers. `UPDATE` and `DELETE` fail on purpose
  ([seguridad/auditoria.md](../seguridad/auditoria.md)).

## Rules the database enforces

- A single default warehouse (partial unique index `warehouses_one_default_idx`, `V3`).
- A transfer cannot have the same origin and destination (`transfers_different_warehouses`, `V3`; `transit_warehouses_differ`, `V9`).
- A line does not reach `APPROVED` without `approved_by` (`CHECK`, `V3`; [ADR-0008](../adr/0008-discrepancias-y-merma.md)).
- The state enums are native PostgreSQL types (`invoice_status`, `order_status`, `transfer_status`, `kardex_move_type`, …), defined in `V3`.
