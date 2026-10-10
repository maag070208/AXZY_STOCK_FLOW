# M06 — Labels: templates, printing and reprinting
> **Normative.** Last verified: 2026-10-10 (M06 closed).

| | |
|---|---|
| **Code** | M06 |
| **Folder** | `docs/modulos/M06-etiquetas/` |
| **Documented** | Yes |
| **Finished (backend)** | Yes |
| **Web** | `LabelsPage` |
| **App** | `features/labels` |

## 1. Objective

Print mesh and free labels, know which ones were really printed and be able to reprint.

## 2. Scope

| Inside | Outside |
|---|---|
| Label templates | Physical printer (confirmed by an external system) |
| Preview, printing, history and reprint | |
| Print confirmation from the printer | |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | A label stays `PENDING` until the printer confirms; if the printer is off, it retries | [ADR-0010](../../adr/0010-rutas-de-servicio-con-api-key.md); `label/LabelRoutes.kt` (`/v1/print-jobs/{id}`) |
| R2 | Print confirmation is done by a service system, with a service key and without a user | `service_api_keys` in `V2`; `label/LabelService.kt` |
| R3 | The label rules live in the domain | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/labeling/` (`LabelResolverTest`) |

## 4. Data model

| Table | Migration | What it holds |
|---|---|---|
| `label_templates` | `V3` | Templates |
| `print_jobs` | `V3` | Print jobs and their state |

## 5. API

Contract in [api/etiquetas.md](../../api/etiquetas.md). Routes in `label/LabelRoutes.kt`.

## 6. Permissions

`label.template_view`, `label.template_edit`, `label.print`, `label.queue_view`, `label.reprint`,
`print_device.manage`. Print confirmation: service key.
See [seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Audit log

Each print and reprint writes to `audit_log`.

## 8. Tests

`LabelResolverTest` in `stock-domain`. Routes in the backend.

## 9. Acceptance criteria

- [x] The label stays `PENDING` until confirmation (`LabelResolverTest`).
- [ ] A real print confirmed from the printer (not verified).
- [x] Queue, templates and preview with a browser test (`web/e2e/flow.spec.ts`). Reprint is not covered.
