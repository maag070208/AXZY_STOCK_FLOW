# Entidad-relación
> **Referencia.** Qué tablas hay, en qué migración nacen y cómo se relacionan. El detalle de
> columnas está en las propias migraciones: `backend/src/main/resources/db/migration/`.
> Última verificación: 2026-10-09.

## Tablas por dominio

| Dominio | Tablas | Migración |
|---|---|---|
| Identidad | `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `user_warehouse_scopes` | `V1__identity.sql` |
| Sesiones | `refresh_tokens`, `login_attempts` | `V4__sessions.sql` |
| Auditoría | `audit_log`, `audit_log_default`, `access_counter`, `config_versions`, `service_api_keys` | `V2__audit_and_traceability.sql` |
| Catálogo y existencias | `warehouses`, `locations`, `units_of_measure`, `products`, `stock_by_location`, `product_stock`, `stock_in_transit`, `kardex` | `V3__inventory_domain.sql` |
| Recepción | `invoices`, `invoice_lines` | `V3__inventory_domain.sql` |
| Surtido | `orders`, `order_lines` | `V3__inventory_domain.sql` |
| Traslados | `transfers`, `transfer_lines` | `V3__inventory_domain.sql`, `V9__transfer_lines.sql` |
| Etiquetas | `label_templates`, `print_jobs` | `V3__inventory_domain.sql` |

`V5`, `V6`, `V7` y `V8` no crean tablas nuevas: añaden la ubicación de recepción, el motor de
existencias, la escritura de auditoría con verificación y el renombrado de columnas al inglés.

## Relaciones clave

- `locations` → `warehouses`: toda ubicación pertenece a una bodega.
- `stock_by_location` → `products`, `locations`, `warehouses`: la proyección de existencias.
  `warehouse_id` va desnormalizado para los reportes por bodega ([ADR-0007](../adr/0007-multi-bodega-desde-el-esquema.md)).
- `kardex` → `products`, `locations`: el libro append-only. No tiene `UPDATE` ni `DELETE` de negocio.
- `invoice_lines` → `invoices`, `products`: una línea nunca cambia de producto.
- `transfers` → `warehouses` (origen y destino distintos), `transfer_lines`, `stock_in_transit`.
- `audit_log`: inmutable por disparadores. `UPDATE` y `DELETE` fallan a propósito
  ([seguridad/auditoria.md](../seguridad/auditoria.md)).

## Reglas que la base impone

- Una sola bodega por defecto (índice único parcial `warehouses_one_default_idx`, `V3`).
- Un traslado no puede tener el mismo origen y destino (`transfers_different_warehouses`, `V3`; `transit_warehouses_differ`, `V9`).
- Una línea no llega a `APPROVED` sin `approved_by` (`CHECK`, `V3`; [ADR-0008](../adr/0008-discrepancias-y-merma.md)).
- Los enums de estado son tipos nativos de PostgreSQL (`invoice_status`, `order_status`, `transfer_status`, `kardex_move_type`, …), definidos en `V3`.
