# M09 — Integración externa: ERP y legacy
> **Plan.** Módulo sin construir en la parte de ERP. Última verificación: 2026-10-09.

| | |
|---|---|
| **Código** | M09 |
| **Carpeta** | `docs/modulos/M09-integracion-externa/` |
| **Documentado** | Parcial |
| **Terminado (backend)** | No |
| **Web** | No aplica |
| **App** | No aplica |

## 1. Objetivo

Intercambiar datos con sistemas que no son STOCK: devolver las órdenes surtidas al ERP y, si
hace falta, leer lo que el sistema anterior dejó en su base.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Exportación de órdenes confirmadas al ERP | Impresión (M06, la confirma la impresora con clave de servicio) |
| Reintentos e idempotencia del envío | Sincronización de productos (la fuente es la API, [ADR-0011](../../adr/0011-catalogo-fuente-maestra.md)) |
| Lectura del legacy durante la migración | |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Las órdenes se envían al ERP con `Idempotency-Key` (contrato decidido por nosotros; el envío real no existe todavía) | [ADR-0013](../../adr/0013-ordenes-al-erp-con-reintentos.md); `order/OrderService.kt` (`POST /v1/orders/{id}/export`) |
| R2 | La autenticación del ERP no está definida. La clave de servicio cubre hoy impresión y el `kardo` del legacy | [ADR-0010](../../adr/0010-rutas-de-servicio-con-api-key.md) |
| R3 | `external_id` del ERP está pendiente de decisión | Decisión D10 en [migracion/cutover.md](../../migracion/cutover.md) |

## 4. Modelo de datos

El campo de la orden que registra el intento de envío lo define `V3__inventory_domain.sql`. No hay
tabla propia de integración.

## 5. API

`POST /v1/orders/{id}/export` (M04) registra el intento. `GET /v1/orders/{id}` devuelve su estado de envío.
La respuesta no incluye `nextRetryAt` ([api/surtido.md](../../api/surtido.md)).

## 6. Permisos

`order.convert`. La clave de servicio no lleva permisos de usuario.

## 7. Bitácora

Cada intento de envío escribe en `audit_log`.

## 8. Pruebas

Pendientes: no hay prueba contra un ERP real ni simulado.

## 9. Criterios de aceptación

- [ ] El envío real llega al ERP y se reintenta si falla.
- [ ] Decisión sobre el módulo `kardo` del legacy: conservar o descartar.
