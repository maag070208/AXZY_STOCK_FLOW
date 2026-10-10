# M04 — Surtido: órdenes y empaquetado
> **Normativo.** Última verificación: 2026-10-10 (fase 7 cerrada).

| | |
|---|---|
| **Código** | M04 |
| **Carpeta** | `docs/modulos/M04-surtido/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí, salvo el envío real al ERP |
| **Web** | `OrdersPage`, `OrderDetailPage` |
| **App** | `features/orders` |

## 1. Objetivo

Surtir una orden: asignar existencias a cada línea, empaquetarla y confirmarla. Lo que no
se puede surtir se registra como faltante con su ajuste.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Alta y consulta de órdenes | Traslados (M05) |
| Empaquetado por línea y confirmación | Envío efectivo al ERP ([ADR-0013](../../adr/0013-ordenes-al-erp-con-reintentos.md)) |
| Faltantes con ajuste de origen `FULFILLMENT_SHORTAGE` | |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Un faltante es un ajuste con motivo, no un borrado de línea | [ADR-0008](../../adr/0008-discrepancias-y-merma.md); `order/OrderRoutes.kt` (`/shortage`) |
| R2 | No se exporta al ERP una orden que no está confirmada | `ORDER_NOT_CONVERTED` en `order/OrderService.kt` |
| R3 | Las máquinas de estado de la orden no admiten saltos | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/fulfillment/` (`OrderStateMachineTest`) |

## 4. Modelo de datos

| Tabla | Migración | Qué guarda |
|---|---|---|
| `orders` | `V3` | Órdenes, con estado `PENDING` → `COMPLETED` |
| `order_lines` | `V3` | Líneas, con estado `PENDING`, `ASSIGNED`, `SUPPLIED`, `CANCELLED` |

## 5. API

Contrato en [api/surtido.md](../../api/surtido.md). Rutas en `order/OrderRoutes.kt`.

## 6. Permisos

`order.view`, `order.create`, `order.package`, `order.confirm`, `order.convert` (la exportación al
ERP usa `order.convert`). Ver
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Empaquetado, confirmación, exportación y faltante escriben en `audit_log`.

## 8. Pruebas

`OrderStateMachineTest` en `stock-domain`. Rutas en el backend.

## 9. Criterios de aceptación

- [x] La máquina de estados rechaza transiciones ilegales (`OrderStateMachineTest`).
- [ ] La exportación llega al ERP y se reintenta si falla ([ADR-0013](../../adr/0013-ordenes-al-erp-con-reintentos.md)).
- [x] Listado, alta y empaque con prueba de navegador (`web/e2e/flow.spec.ts`). La confirmación y el envío no están cubiertos.
