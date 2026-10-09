# M05 — Traslados: entre ubicaciones y entre bodegas

> **Normativo.** Última verificación: 2026-10-09.

| | |
|---|---|
| **Código** | M05 |
| **Carpeta** | `docs/modulos/M05-traslados/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí |
| **Web** | `TransfersPage` |
| **App** | No hay pantalla |

## 1. Objetivo

Mover existencias de una ubicación a otra, o de una bodega a otra, con la mercancía en tránsito
contabilizada hasta que llega.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Traslado con líneas: borrador, despacho, recepción, cancelación | Ubicar una factura recibida (M03) |
| Tránsito entre bodegas | Operación con más de una bodega en producción ([ADR-0007](../../adr/0007-multi-bodega-desde-el-esquema.md)) |
| Consulta de la mercancía en tránsito | |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Origen y destino de un traslado entre bodegas son distintos | `transfers_different_warehouses` (`V3`); `transit_warehouses_differ` (`V9`) |
| R2 | El dominio comprueba la regla antes de tocar la base, para dar un error que explique el campo | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/transfer/` |
| R3 | Las transiciones de estado siguen la máquina del traslado | `TransferStateMachineTest` en `stock-domain` |
| R4 | Ver la mercancía en tránsito exige `transit.view`, distinto de `transfer.view` | `transfer/TransferRoutes.kt` (`/in-transit`) |

## 4. Modelo de datos

| Tabla | Migración | Qué guarda |
|---|---|---|
| `transfers` | `V3` | Cabecera y estado (`DRAFT`, `DISPATCHED`, `IN_TRANSIT`, `RECEIVED`, `CANCELLED`) |
| `transfer_lines` | `V9` | Líneas del traslado |
| `stock_in_transit` | `V3`, `V9` | Deuda de cada traslado no recibido |

## 5. API

Contrato en [api/traslados.md](../../api/traslados.md). Rutas en `transfer/TransferRoutes.kt`.

## 6. Permisos

`transfer.view`, `transfer.create`, `transfer.dispatch`, `transfer.receive`, `transfer.cancel`,
`transit.view`. Ver
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Cada cambio de estado escribe en `audit_log`.

## 8. Pruebas

`TransferStateMachineTest` en `stock-domain`. Rutas en el backend.

## 9. Criterios de aceptación

- [x] Un traslado entre la misma bodega se rechaza (`TransferStateMachineTest`).
- [x] Deuda en tránsito y despacho con prueba de navegador (`web/e2e/flow.spec.ts`). La recepción no está cubierta.
