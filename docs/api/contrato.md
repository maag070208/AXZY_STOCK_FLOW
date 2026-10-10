# API contract — Almacén Integral
> **Reference.** Index of the HTTP contract by topic and by resource, the permission matrix per endpoint and what this API does not expose.
> Last verified: 2026-10-09.

This contract is the source of truth of the HTTP. It is consumed by the backend (which implements it), the web (which generates its client) and the mobile app (which declares its DTOs). If
something changes here, all three sides find out.

> **Writing the contract before parallelizing the work is what makes parallelizing it possible.** Without it, the backend, the web and the mobile app invent different shapes for
> the same response, and the problem appears at integration, which is the worst place to discover it.

## Documents

| Topic | Document | Type |
|---|---|---|
| Cross-cutting conventions | [convenciones.md](convenciones.md) | Normative |
| Error body and code catalog | [errores.md](errores.md) | Reference |
| Authentication | [autenticacion.md](autenticacion.md) | Reference |
| Catalog: products, locations, warehouses, import | [catalogo.md](catalogo.md) | Reference |
| Inventory: stock, ledger, adjustments, location, movement | [inventario.md](inventario.md) | Reference |
| Receiving: invoices, capture, approval, closing | [recepcion.md](recepcion.md) | Reference |
| Picking: orders, packing, confirmation, export | [surtido.md](surtido.md) | Reference |
| Transfers between warehouses | [traslados.md](traslados.md) | Reference |
| Labels: templates, printing, queue | [etiquetas.md](etiquetas.md) | Reference |
| Reports: stock, export, summary, reconciliation | [reportes.md](reportes.md) | Reference |
| Health | [salud.md](salud.md) | Reference |

The rules for routes, states, pagination, idempotency, headers and formats live
**only** in [convenciones.md](convenciones.md); the resource documents link to it and
do not repeat them. The same goes for the error code catalog, which lives in
[errores.md](errores.md).

## Permissions per endpoint

Reference table. The key is `resource.action` in lowercase; the scope is resolved by RBAC with its `PermissionScope` (`NONE`/`OWN`/`AREA`/`ALL`), and the scopes allowed for each permission are declared by
`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`.

| Endpoint | Permission | Minimum scope |
|---|---|---|
| `POST /v1/auth/login`, `/refresh`, `/logout` | — | public |
| `GET /v1/auth/me` | — | authenticated |
| `POST /v1/auth/change-password` | — | authenticated |
| `GET /v1/products` | `product.view` | `ALL` |
| `POST /v1/products` | `product.create` | `ALL` |
| `GET /v1/products/{id}` | `product.view` | `ALL` |
| `PUT /v1/products/{id}` | `product.edit` | `ALL` |
| `DELETE /v1/products/{id}` | `product.delete` | `ALL` |
| `POST /v1/products/import` | `product.import` | `ALL` |
| `GET /v1/locations` | `location.view` | `ALL` |
| `GET /v1/locations/{id}` | `location.view` | `ALL` |
| `POST /v1/locations` | `location.create` | `ALL` |
| `PUT /v1/locations/{id}` | `location.edit` | `ALL` |
| `DELETE /v1/locations/{id}` | `location.delete` | `ALL` |
| `GET /v1/warehouses` | `location.view` | `ALL` — [detail](catalogo.md) |
| `POST /v1/warehouses` | `location.create` | `ALL` — [detail](catalogo.md) |
| `PUT /v1/warehouses/{id}` | `location.edit` | `ALL` — [detail](catalogo.md) |
| `GET /v1/inventory/stock` | `inventory.view` | `AREA` |
| `GET /v1/inventory/kardex` | `kardex.view` | `AREA` |
| `POST /v1/inventory/adjustments` | `inventory.adjust_increase` · `inventory.adjust_theft` · `inventory.adjust_damage` · `inventory.adjust_shortage` · `inventory.adjust_count_error` | `AREA` |
| `POST /v1/inventory/locate` | `invoice.locate` | `AREA` |
| `POST /v1/inventory/move` | `inventory.move` | `AREA` |
| `GET /v1/invoices` | `invoice.view` | `AREA` |
| `POST /v1/invoices` | `invoice.create` | `AREA` |
| `GET /v1/invoices/{id}` | `invoice.view` | `AREA` |
| `POST /v1/invoices/{id}/lines/{lineId}/capture` | `invoice.capture` | `AREA` |
| `POST /v1/invoices/{id}/approve` | `invoice.approve` | `AREA` |
| `POST /v1/invoices/{id}/reject-discrepancy` | `invoice.reject` | `AREA` |
| `POST /v1/invoices/{id}/close` | `invoice.close` | `AREA` |
| `GET /v1/invoices/pending-approval` | `invoice.view` | `AREA` |
| `GET /v1/orders` | `order.view` | `AREA` |
| `POST /v1/orders` | `order.create` | `AREA` |
| `GET /v1/orders/{id}` | `order.view` | `AREA` |
| `POST /v1/orders/{id}/lines/{lineId}/package` | `order.package` | `AREA` |
| `POST /v1/orders/{id}/confirm` | `order.confirm` | `AREA` |
| `POST /v1/orders/{id}/export` | `order.convert` | `AREA` |
| `POST /v1/orders/{id}/shortage` | `order.package` | `AREA` |
| `PUT /v1/orders/{id}/lines/{lineId}/assign` | `order.assign` | `ALL` — [detail](surtido.md) |
| `PUT /v1/invoices/{id}/lines/{lineId}/assign` | `invoice.assign` | `ALL` — [detail](recepcion.md) |
| `GET /v1/transfers` | `transfer.view` | `AREA` |
| `POST /v1/transfers` | `transfer.create` | `AREA` |
| `GET /v1/transfers/{id}` | `transfer.view` | `AREA` |
| `GET /v1/transfers/in-transit` | `transit.view` | `AREA` |
| `POST /v1/transfers/{id}/dispatch` | `transfer.dispatch` | `AREA` |
| `POST /v1/transfers/{id}/receive` | `transfer.receive` | `AREA` |
| `POST /v1/transfers/{id}/cancel` | `transfer.cancel` | `ALL` |
| `GET /v1/label-templates` | `label.template_view` | `AREA` |
| `POST /v1/label-templates` | `label.template_edit` | `ALL` |
| `GET /v1/label-templates/{id}` | `label.template_view` | `AREA` |
| `PUT /v1/label-templates/{id}` | `label.template_edit` | `ALL` |
| `POST /v1/labels/preview` | `label.print` | `ALL` |
| `POST /v1/labels/print` | `label.print` | `ALL` |
| `GET /v1/labels/jobs` | `label.queue_view` | `AREA` |
| `GET /v1/labels/jobs/{id}` | `label.queue_view` | `AREA` |
| `POST /v1/labels/jobs/{id}/reprint` | `label.reprint` | `AREA` |
| `PATCH /v1/print-jobs/{id}` | — | service credential (`X-Service-Key`) |
| `GET /v1/reports/inventory` | `report.view` | `AREA` |
| `GET /v1/reports/movements-summary` | `report.view` | `AREA` |
| `GET /v1/reports/kardex/export` | `report.export` | `ALL` |
| `GET /v1/reports/kardex-vs-projection` | `report.view` | `AREA` |
| `GET /v1/users` | `user.view` | `ALL` — [detail](usuarios.md) |
| `POST /v1/users` | `user.create` | `ALL` — [detail](usuarios.md) |
| `GET /v1/users/{id}` | `user.view` | `ALL` — [detail](usuarios.md) |
| `PUT /v1/users/{id}` | `user.edit` / `role.assign` | `ALL` — [detail](usuarios.md) |
| `POST /v1/users/{id}/reset-password` | `user.reset_password` | `ALL` — [detail](usuarios.md) |
| `POST /v1/users/{id}/revoke-sessions` | `session.revoke` | `ALL` — [detail](usuarios.md) |
| `GET /v1/roles` | `role.manage` | `ALL` — [detail](usuarios.md) |
| `POST /v1/roles` | `role.manage` | `ALL` — [detail](usuarios.md) |
| `GET /v1/roles/{key}` | `role.manage` | `ALL` — [detail](usuarios.md) |
| `PUT /v1/roles/{key}` | `role.manage` | `ALL` — [detail](usuarios.md) |
| `PUT /v1/roles/{key}/permissions` | `role.manage` | `ALL` — [detail](usuarios.md) |
| `GET /v1/permissions` | `role.manage` | `ALL` — [detail](usuarios.md) |
| `GET /v1/audit/log` | `audit.view` | `ALL` — [detail](auditoria.md) |
| `GET /v1/health` | — | public |

**No endpoint is left without a permission or a credential.** The legacy had seeded permissions
that no route required: it looked like there was a restriction and there was none.

Verification notes:

- `POST /v1/inventory/adjustments` does not require a generic "adjust" permission: the
  permission depends on the direction and the reason, and `inventory.adjust_decrease` **does not
  exist** in the catalog (decreases are resolved by reason).
- `GET /v1/reports/kardex-vs-projection` requires `report.view`, the same permission as
  the guard of the `/reports` screen. **The permission of a route has to be the
  same as the guard of its screen**; with a different one, the user enters the
  screen and receives a `403` when it loads.
- `GET /v1/roles` and `GET /v1/permissions` require `role.manage`, which covers
  "manage roles and permissions": `role.view` and `permission.view` do not exist in the
  catalog, and inventing them would leave the routes answering `403` for everyone.

## What does NOT exist in this API, and why

| Legacy route | Status | Reason |
|---|---|---|
| `PUT /kardex/:id`, `DELETE /kardex/:id` | **Does not exist** | The ledger is append-only. Corrections are new movements. |
| `GET /get-location-content` | **Does not exist** | Replaced by `GET /v1/inventory/stock`, which reads the real balance instead of recalculating it from mutated movements. |
| Routes without `/v1` | **Do not exist** | The contract has to be able to evolve. |
| `POST /prints` (open, without credential) | **`PATCH /v1/print-jobs/{id}` with a service credential** | Same consumer, without an open endpoint. The `jobId` goes in the route, not in the body. |
| Direct write to `store.stock` | **Does not exist** | `product_stock` is a derived projection; it is never written through the API. |
| Discrepancy approval per line | **Does not exist** | Approval is per invoice: approving one by one leaves the document in an intermediate state and invites approving one's own. |
| `PUT`/`DELETE` on the audit log | **Do not exist** | The table is immutable through triggers: `UPDATE` and `DELETE` fail on purpose. There is only `GET /v1/audit/log`. |
