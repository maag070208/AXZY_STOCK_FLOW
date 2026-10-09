# Diccionario de datos

> **Referencia.** Tabla, migración que la crea y su función. Las columnas son las de cada
> `CREATE TABLE`; **no se transcriben aquí** para que no se desfasen. Última verificación: 2026-10-09.

| Tabla | Migración | Función |
|---|---|---|
| `users` | `V1` | Personas que entran al sistema. Contraseña con bcrypt. |
| `roles`, `permissions`, `role_permissions`, `user_roles` | `V1` | RBAC: roles, catálogo de permisos y su asignación. |
| `user_warehouse_scopes` | `V1` | Alcance `AREA` de una persona: en qué bodegas opera. |
| `refresh_tokens` | `V4` | Refresh tokens rotados, con familia para detectar reúso. |
| `login_attempts` | `V4` | Intentos fallidos, para bloquear cuentas. |
| `audit_log` | `V2` | Bitácora inmutable de escrituras. |
| `audit_log_default` | `V2` | Partición por defecto de la bitácora. |
| `access_counter` | `V2` | Contadores de acceso agregados (nivel 3 de auditoría). |
| `config_versions` | `V2` | Versiones de la configuración de acceso. |
| `service_api_keys` | `V2` | Claves de sistemas externos ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)). |
| `warehouses` | `V3` | Bodegas. Una marcada por defecto. |
| `locations` | `V3` (+ `V5`, `V6`) | Ubicaciones. `kind` distingue estantería, recepción y `SIN-UBICAR`. |
| `units_of_measure` | `V3` | Catálogo de unidades. |
| `products` | `V3` | Catálogo de productos. Fuente maestra ([ADR-0011](../adr/0011-catalogo-fuente-maestra.md)). |
| `stock_by_location` | `V3` | Existencias por producto y ubicación. Proyección. |
| `product_stock` | `V3` | Total por producto, derivado de las ubicaciones y el tránsito. |
| `stock_in_transit` | `V3`, `V9` | Mercancía despachada y no recibida. |
| `kardex` | `V3` | Libro de movimientos, append-only. |
| `invoices`, `invoice_lines` | `V3` | Recepción: facturas y sus líneas con su estado. |
| `orders`, `order_lines` | `V3` | Surtido: órdenes y sus líneas. |
| `transfers`, `transfer_lines` | `V3`, `V9` | Traslados y sus líneas. |
| `label_templates` | `V3` | Plantillas de etiqueta. |
| `print_jobs` | `V3` | Trabajos de impresión; `PENDING` hasta confirmación ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)). |

Los enums de estado se definen en `V3__inventory_domain.sql`; no se duplican aquí.
