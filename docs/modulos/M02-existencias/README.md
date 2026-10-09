# M02 — Existencias: stock, kardex, ajustes y reportes

> **Normativo.** Última verificación: 2026-10-09.

| | |
|---|---|
| **Código** | M02 |
| **Carpeta** | `docs/modulos/M02-existencias/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí |
| **Web** | `InventoryPage`, `KardexPage`, reportes en `ReportsPage` |
| **App** | `features/inventory` |

## 1. Objetivo

Saber cuánto hay de cada producto y dónde está, y poder explicar cada cambio. Es el motor
de existencias del que dependen recepción, surtido y traslados. Incluye los reportes, que
leen de las mismas existencias y del mismo kardex.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Existencias por producto y ubicación | Captura de facturas (M03) |
| Kardex (libro de movimientos) | Traslados entre ubicaciones (M05), aunque usan el mismo motor |
| Ajustes con motivo, ubicar y mover | Etiquetas (M06) |
| Reportes: existencias, resumen de movimientos, kardex vs. proyección, exportación | |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Cada cambio de existencias es un movimiento nuevo en el kardex; nunca se edita el histórico | `V3__inventory_domain.sql` (`kardex` append-only); `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/` |
| R2 | Las existencias son una proyección del kardex y del tránsito | `stock_by_location`, `product_stock` en `V3` |
| R3 | Toda diferencia es un ajuste con motivo obligatorio y origen | [ADR-0008](../../adr/0008-discrepancias-y-merma.md); `AdjustStock` en `stock-domain` |
| R4 | No se puede sacar más de lo que hay en la ubicación | `INSUFFICIENT_STOCK` en `inventory/InventoryService.kt` y `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt` |
| R5 | Origen y destino de un movimiento no pueden ser el mismo | `SAME_SOURCE_AND_DESTINATION` |
| R6 | Un movimiento de existencias requiere motivo cuando la regla lo exige | `REASON_REQUIRED` |
| R7 | El total por producto cuadra con la suma de sus ubicaciones y el tránsito | `comprobar_proyecciones()` en las migraciones; reporte `kardex-vs-projection` |
| R8 | Ver el kardex exige `kardex.view`, no `inventory.view` | `inventory/InventoryRoutes.kt` |

## 4. Modelo de datos

| Tabla | Migración | Qué guarda |
|---|---|---|
| `kardex` | `V3`, `V6` | Libro de movimientos |
| `stock_by_location` | `V3`, `V6` | Existencias por ubicación |
| `product_stock` | `V3` | Total por producto |
| `stock_in_transit` | `V3`, `V9` | Tránsito |

## 5. API

Existencias, kardex y ajustes en [api/inventario.md](../../api/inventario.md). Reportes en
[api/reportes.md](../../api/reportes.md). Rutas en `inventory/InventoryRoutes.kt` y
`reports/ReportRoutes.kt`.

## 6. Permisos

`inventory.view`, `inventory.move`, `inventory.adjust_*` (uno por motivo),
`inventory.approve_adjustment`, `kardex.view`, `kardex.export`, `report.view`, `report.export`.
`report.financial` no entra en este módulo. Ver
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Cada ajuste, ubicación y movimiento escribe en `audit_log`
([seguridad/auditoria.md](../../seguridad/auditoria.md)).

## 8. Pruebas

Invariantes de conservación en `stock-domain` (`InventoryInvariantsPropertyTest`,
`StockMovementMatrixTest`, `AdjustmentTest`, `LocationContentTest`). Pruebas de rutas en el backend.

## 9. Criterios de aceptación

- [x] Las invariantes I1–I10 se cumplen en las pruebas de propiedades.
- [ ] La web recorre existencias, kardex y ajustes contra el backend.
