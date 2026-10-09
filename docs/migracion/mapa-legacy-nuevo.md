# Mapa legacy → sistema nuevo

> **Referencia.** Cada ruta del legacy con su destino en el sistema nuevo, o con el motivo de
> descarte. Las rutas del legacy vienen de [historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md) §1.1;
> las del sistema nuevo, de `backend/src/main/kotlin/dev/axzy/stock/backend/`. Última verificación: 2026-10-09.

## Usuarios, roles y acceso

| Legacy | Destino | Estado |
|---|---|---|
| `POST /users/login` | `POST /v1/auth/login` ([M07](../modulos/M07-usuarios-accesos/README.md)) | Migrada, con refresh rotado |
| `GET /users`, `GET /users/list` | `GET /v1/users` | Solo lectura |
| `POST /users`, `PATCH /users` (alta y edición) | Sin ruta: la API nueva no crea ni edita usuarios | **Pendiente** |
| `PATCH /users/password` (admin cambia la de otro) | Sin ruta. `POST /v1/auth/change-password` es solo para la propia contraseña | **Pendiente** |
| `GET /users/roles`, `GET /roles` | `GET /v1/roles`, `GET /v1/permissions` | Solo lectura; asignar roles, pendiente |

## Catálogo

| Legacy | Destino | Estado |
|---|---|---|
| `GET /products` | `GET /v1/products` ([M01](../modulos/M01-catalogo/README.md)) | Migrada |
| `GET /products/:code` (por código de barras) | Por confirmar en la API nueva | **Pendiente de verificar** |
| `GET /locations`, `GET /locations/:code` | `GET /v1/locations`, `GET /v1/locations/{id}` | Migrada |
| `POST /locations`, `PATCH /locations` | `POST /v1/locations`, `PUT /v1/locations/{id}` | Migrada |
| `PATCH /locations/delete/:id` | `DELETE /v1/locations/{id}`, baja lógica | Migrada |

## Recepción

| Legacy | Destino | Estado |
|---|---|---|
| `POST /invoices`, `GET /invoices`, `GET /invoices/:id` | `POST /v1/invoices`, `GET /v1/invoices`, `GET /v1/invoices/{id}` ([M03](../modulos/M03-recepcion/README.md)) | Migrada |
| `PATCH /invoices/detail/:id`, `PATCH /invoices/detail/capture` | `POST /v1/invoices/{id}/lines/{lineId}/capture` | Migrada; la captura en lote no existe todavía |
| `PATCH /invoices/assign`, `POST /invoices/detail` | Pendiente de decisión: la asignación de líneas a personas no tiene ruta | **Pendiente** |
| `PATCH /invoices/:id` (aprobar o cerrar) | `POST /v1/invoices/{id}/approve`, `POST /v1/invoices/{id}/close` | Migrada; aprobación por factura ([ADR-0008](../adr/0008-discrepancias-y-merma.md)) |
| `DELETE /invoices/detail/:id` (sin permiso) | **Descartada.** No existe borrado de líneas en la API nueva | Descartada: era un descuido de seguridad (B8) |
| `PATCH /invoices/delete/:id` | Baja lógica de factura, sin ruta todavía | **Pendiente** |
| `PATCH /invoices/assign` (línea a ubicación) | `POST /v1/invoices/{id}/lines/{lineId}/capture` y `POST /v1/inventory/locate` | Migrada |

## Surtido

| Legacy | Destino | Estado |
|---|---|---|
| `POST /orders`, `GET /orders`, `GET /orders/detail/:id` | `POST /v1/orders`, `GET /v1/orders`, `GET /v1/orders/{id}` ([M04](../modulos/M04-surtido/README.md)) | Migrada |
| `POST /orders/detail`, `DELETE /orders/detail/:id` | `POST /v1/orders/{id}/lines/{lineId}/package`; sin borrado de línea | Parcial |
| Asignación y confirmación de orden | `POST /v1/orders/{id}/confirm` | Migrada |
| (no existía) | `POST /v1/orders/{id}/export` al ERP | Nueva ([ADR-0013](../adr/0013-ordenes-al-erp-con-reintentos.md)); envío pendiente |

## Existencias

| Legacy | Destino | Estado |
|---|---|---|
| `GET /store/:productCode`, `GET /store/detail/:productCode` | `GET /v1/inventory/stock`, `GET /v1/inventory/kardex` ([M02](../modulos/M02-existencias/README.md)) | Migrada |
| `POST /store`, `POST /store/many` (alta de existencias) | Importación del catálogo ([ADR-0011](../adr/0011-catalogo-fuente-maestra.md)) y ajustes con motivo | Sustituida |
| `PATCH /store`, `DELETE /store/:productCode` | `POST /v1/inventory/adjustments` con motivo. No hay edición ni borrado directo | Sustituida: las existencias no se editan |
| `PATCH /locations/inventory` (mover) | `POST /v1/inventory/move` | Migrada |
| `POST /locations/inventory` (ajustar) | `POST /v1/inventory/adjustments` | Migrada |
| `GET /locations/inventory/:code` | `GET /v1/inventory/stock?locationCode=…` | Migrada |
| `GET /store/report/*` (por producto, ubicación y fechas) | `GET /v1/reports/inventory`, `GET /v1/reports/movements-summary`, `GET /v1/reports/kardex/export` ([M02](../modulos/M02-existencias/README.md)) | Migrada |

## Etiquetas

| Legacy | Destino | Estado |
|---|---|---|
| `GET /prints`, `POST /prints` | `GET /v1/labels/jobs`, `POST /v1/labels/print` ([M06](../modulos/M06-etiquetas/README.md)) | Migrada |
| `PATCH /prints` (sin autenticación) | `PATCH /v1/print-jobs/{id}` con clave de servicio ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)) | Migrada con autenticación |

## Integración

| Legacy | Destino | Estado |
|---|---|---|
| `GET /kardo` (sin autenticación; lee facturas de SQL Server) | Decisión pendiente: conservar o descartar ([M09](../modulos/M09-integracion-externa/README.md)) | **Pendiente** |

## Rutas del legacy sin destino

Ninguna ruta del legacy queda sin decisión: las que no tienen destino aparecen arriba como
**Pendiente** o **Descartada** con su motivo.
