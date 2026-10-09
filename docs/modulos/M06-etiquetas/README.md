# M06 — Etiquetas: plantillas, impresión y reimpresión

> **Normativo.** Última verificación: 2026-10-09.

| | |
|---|---|
| **Código** | M06 |
| **Carpeta** | `docs/modulos/M06-etiquetas/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí |
| **Web** | `LabelsPage` |
| **App** | `features/labels` |

## 1. Objetivo

Imprimir etiquetas de malla y libres, saber cuáles se imprimieron de verdad y poder reimprimir.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Plantillas de etiqueta | Impresora física (la confirma un sistema externo) |
| Vista previa, impresión, historial y reimpresión | |
| Confirmación de impresión desde la impresora | |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Una etiqueta queda `PENDING` hasta que la impresora confirma; si la impresora está apagada, reintenta | [ADR-0010](../../adr/0010-rutas-de-servicio-con-api-key.md); `label/LabelRoutes.kt` (`/v1/print-jobs/{id}`) |
| R2 | La confirmación de impresión la hace un sistema de servicio, con clave de servicio y sin usuario | `service_api_keys` en `V2`; `label/LabelService.kt` |
| R3 | Las reglas de la etiqueta viven en el dominio | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/labeling/` (`LabelResolverTest`) |

## 4. Modelo de datos

| Tabla | Migración | Qué guarda |
|---|---|---|
| `label_templates` | `V3` | Plantillas |
| `print_jobs` | `V3` | Trabajos de impresión y su estado |

## 5. API

Contrato en [api/etiquetas.md](../../api/etiquetas.md). Rutas en `label/LabelRoutes.kt`.

## 6. Permisos

`label.template_view`, `label.template_edit`, `label.print`, `label.queue_view`, `label.reprint`,
`print_device.manage`. Confirmación de impresión: clave
de servicio. Ver [seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Cada impresión y reimpresión escribe en `audit_log`.

## 8. Pruebas

`LabelResolverTest` en `stock-domain`. Rutas en el backend.

## 9. Criterios de aceptación

- [x] La etiqueta queda `PENDING` hasta la confirmación (`LabelResolverTest`).
- [ ] Una impresión real confirmada desde la impresora (no verificado).
- [ ] La web recorre impresión y reimpresión contra el backend.
