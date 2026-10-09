# Contrato de API — Almacén Integral
> **Referencia.** Índice del contrato HTTP por tema y por recurso, la matriz de
> permisos por endpoint y lo que esta API no expone.
> Última verificación: 2026-10-09.

Este contrato es la fuente de verdad del HTTP. Lo consumen el backend (que lo
implementa), la web (que genera su cliente) y el móvil (que declara sus DTOs). Si
algo cambia aquí, los tres lados se enteran.

> **Escribir el contrato antes de paralelizar el trabajo es lo que hace posible
> paralelizarlo.** Sin él, el backend, la web y el móvil inventan formas distintas
> de la misma respuesta, y el problema aparece en la integración, que es el peor
> sitio para descubrirlo.

## Documentos

| Tema | Documento | Tipo |
|---|---|---|
| Convenciones transversales | [convenciones.md](convenciones.md) | Normativo |
| Cuerpo de error y catálogo de códigos | [errores.md](errores.md) | Referencia |
| Autenticación | [autenticacion.md](autenticacion.md) | Referencia |
| Catálogo: productos, ubicaciones, bodegas, importación | [catalogo.md](catalogo.md) | Referencia |
| Inventario: stock, kardex, ajustes, ubicación, movimiento | [inventario.md](inventario.md) | Referencia |
| Recepción: facturas, captura, aprobación, cierre | [recepcion.md](recepcion.md) | Referencia |
| Surtido: órdenes, empaque, confirmación, exportación | [surtido.md](surtido.md) | Referencia |
| Traslados entre bodegas | [traslados.md](traslados.md) | Referencia |
| Etiquetas: plantillas, impresión, cola | [etiquetas.md](etiquetas.md) | Referencia |
| Reportes: existencias, exportación, resumen, reconciliación | [reportes.md](reportes.md) | Referencia |
| Salud | [salud.md](salud.md) | Referencia |

Las reglas de rutas, estados, paginación, idempotencia, cabeceras y formatos viven
**solo** en [convenciones.md](convenciones.md); los documentos por recurso enlazan,
no las repiten. Lo mismo con el catálogo de códigos de error, que vive en
[errores.md](errores.md).

## 11. Permisos por endpoint

Tabla de referencia. La clave es `recurso.accion` en minúsculas; el alcance se
resuelve por RBAC con su `PermissionScope` (`NONE`/`OWN`/`AREA`/`ALL`), y los
alcances admitidos por cada permiso los declara
`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`.

| Endpoint | Permiso | Alcance mínimo |
|---|---|---|
| `POST /v1/auth/login`, `/refresh`, `/logout` | — | público |
| `GET /v1/auth/me` | — | autenticado |
| `POST /v1/auth/change-password` | — | autenticado |
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
| `GET /v1/warehouses` | `location.view` | `ALL` |
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
| `PATCH /v1/print-jobs/{id}` | — | credencial de servicio (`X-Service-Key`) |
| `GET /v1/reports/inventory` | `report.view` | `AREA` |
| `GET /v1/reports/movements-summary` | `report.view` | `AREA` |
| `GET /v1/reports/kardex/export` | `report.export` | `ALL` |
| `GET /v1/reports/kardex-vs-projection` | `report.view` | `AREA` |
| `GET /v1/users` | `user.view` | `ALL` |
| `GET /v1/roles` | `role.manage` | `ALL` |
| `GET /v1/permissions` | `role.manage` | `ALL` |
| `GET /v1/audit/log` | `audit.view` | `ALL` |
| `GET /v1/health` | — | público |

**Ningún endpoint queda sin permiso o sin credencial.** El legacy tenía permisos
sembrados que ninguna ruta exigía: parecía que había una restricción y no la había.

Notas de verificación:

- `POST /v1/inventory/adjustments` no exige un permiso genérico de «ajustar»: el
  permiso depende de la dirección y del motivo, y `inventory.adjust_decrease` **no
  existe** en el catálogo (los ajustes a la baja se resuelven por motivo).
- `GET /v1/reports/kardex-vs-projection` exige `report.view`, el mismo permiso que
  el guard de la pantalla `/reports`. **El permiso de una ruta tiene que ser el
  mismo que el del guard de su pantalla**; con uno distinto, el usuario entra a la
  pantalla y recibe un `403` al cargar.
- `GET /v1/roles` y `GET /v1/permissions` exigen `role.manage`, que cubre
  «administrar roles y permisos»: `role.view` y `permission.view` no existen en el
  catálogo, e inventarlos dejaría las rutas respondiendo `403` para todo el mundo.

## 12. Lo que NO existe en esta API, y por qué

| Ruta del legacy | Estado | Motivo |
|---|---|---|
| `PUT /kardex/:id`, `DELETE /kardex/:id` | **No existe** | El ledger es append-only. Las correcciones son movimientos nuevos. |
| `GET /get-location-content` | **No existe** | Se reemplaza por `GET /v1/inventory/stock`, que lee el saldo real en lugar de recalcularlo desde movimientos mutados. |
| Rutas sin `/v1` | **No existen** | El contrato debe poder evolucionar. |
| `POST /prints` (abierto, sin credencial) | **`PATCH /v1/print-jobs/{id}` con credencial de servicio** | Mismo consumidor, sin endpoint abierto. El `jobId` va en la ruta, no en el cuerpo. |
| Escritura directa en `store.stock` | **No existe** | `product_stock` es una proyección derivada; nunca se escribe por API. |
| Aprobación de discrepancia por línea | **No existe** | La aprobación es por factura: aprobar de una en una deja el documento en un estado intermedio e invita a aprobar lo propio. |
| `PUT`/`DELETE` sobre la auditoría | **No existen** | La tabla es inmutable por disparadores: `UPDATE` y `DELETE` fallan a propósito. Solo hay `GET /v1/audit/log`. |
