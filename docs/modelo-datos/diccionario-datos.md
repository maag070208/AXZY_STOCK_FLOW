# Data dictionary
> **Reference.** Table, migration that creates it and its function. The columns are those of each
> `CREATE TABLE`; they **are not transcribed here** so that they do not go out of sync. Last verified: 2026-10-09.

| Table | Migration | Function |
|---|---|---|
| `users` | `V1` | People who enter the system. Password with bcrypt. |
| `roles`, `permissions`, `role_permissions`, `user_roles` | `V1` | RBAC: roles, permission catalog and their assignment. |
| `user_warehouse_scopes` | `V1` | A person's `AREA` scope: which warehouses they operate in. |
| `refresh_tokens` | `V4` | Rotated refresh tokens, with a family to detect reuse. |
| `login_attempts` | `V4` | Failed attempts, to lock accounts. |
| `audit_log` | `V2` | Immutable log of writes. |
| `audit_log_default` | `V2` | Default partition of the audit log. |
| `access_counter` | `V2` | Aggregated access counters (level 3 of the audit). |
| `config_versions` | `V2` | Versions of the access configuration. |
| `service_api_keys` | `V2` | Keys of external systems ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)). |
| `warehouses` | `V3` | Warehouses. One marked as default. |
| `locations` | `V3` (+ `V5`, `V6`) | Locations. `kind` distinguishes shelf, receiving and `UNASSIGNED`. |
| `units_of_measure` | `V3` | Unit catalog. |
| `products` | `V3` | Product catalog. Master source ([ADR-0011](../adr/0011-catalogo-fuente-maestra.md)). |
| `stock_by_location` | `V3` | Stock per product and location. Projection. |
| `product_stock` | `V3` | Total per product, derived from the locations and the transit. |
| `stock_in_transit` | `V3`, `V9` | Goods dispatched and not received. |
| `kardex` | `V3` | Movement ledger, append-only. |
| `invoices`, `invoice_lines` | `V3` | Receiving: invoices and their lines with their state. |
| `orders`, `order_lines` | `V3` | Picking: orders and their lines. |
| `transfers`, `transfer_lines` | `V3`, `V9` | Transfers and their lines. |
| `label_templates` | `V3` | Label templates. |
| `print_jobs` | `V3` | Print jobs; `PENDING` until confirmation ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)). |

The state enums are defined in `V3__inventory_domain.sql`; they are not duplicated here.
