# Business modules
> **Normative.** Index and status of each capability of StockFlow. Last verified: 2026-10-09.
> The status comes from the code, not from old progress tables.

## What each column means

- **Documented**: the module has its README with the template.
- **Backend**: the rules, the routes and the tests exist in the repo.
- **Web / App**: the screen exists. It does not mean that the flow has been walked through against the backend.

## Status

| Module | Capability | Documented | Backend | Web | App |
|---|---|---|---|---|---|
| [M01](M01-catalogo/README.md) | Catalog: products, locations, warehouses | Yes | Yes | `ProductsPage`, `LocationsPage` | `locations` |
| [M02](M02-existencias/README.md) | Stock, ledger, adjustments and reports | Yes | Yes | `InventoryPage`, `KardexPage`, `ReportsPage` | `inventory` |
| [M03](M03-recepcion/README.md) | Receiving: invoices and discrepancies | Yes | Yes | `InvoicesPage`, `InvoiceDetailPage`, `PendingApprovalPage` | `invoices` |
| [M04](M04-surtido/README.md) | Picking: orders and packing | Yes | Yes, except the dispatch to the ERP | `OrdersPage`, `OrderDetailPage` | `orders` |
| [M05](M05-traslados/README.md) | Transfers | Yes | Yes | `TransfersPage` | None |
| [M06](M06-etiquetas/README.md) | Labels and printing | Yes | Yes | `LabelsPage` | `labels` |
| [M07](M07-usuarios-accesos/README.md) | Users and access | Yes | Partial: sessions and permissions yes; users, read-only | `UsersPage` | `auth` |
| [M08](M08-auditoria/README.md) | Audit | Yes | Yes | `AuditPage` | None |
| [M09](M09-integracion-externa/README.md) | External integration: ERP and `kardo` | Partial | No | Not applicable | Not applicable |

## Browser coverage

`web/e2e/flow.spec.ts` has browser tests against the real backend, distributed by module in the
table below. They are not in CI and were not run when this was written.

| Module | Tests | What they cover |
|---|---|---|
| M02 | 2 | Reports with server data; discrepancy alarm |
| M04 | 4 | Listing, order creation, packing without touching the inventory |
| M05 | 2 | Visible debt in transit; dispatched transfer |
| M06 | 3 | Label queue; template validation; preview |
| M07 | 1 | Users in read-only mode |
| M08 | 2 | Log with channel and permission; filter |
| Access | 4 | Session, credentials, reload |

M01 (catalog) and M03 (receiving) have no browser test.

## What is not in this table

- **System health** (`GET /v1/health`): infrastructure, not a module. See [api/salud.md](../api/salud.md).
- **Reports** (`/v1/reports/*`): they live in M02 because they read from its stock and its ledger.
- **Access configuration** (roles, matrix, policies): [seguridad/roles-permisos.md](../seguridad/roles-permisos.md).

## Template

Each module follows [plantillas/plantilla-modulo.md](../plantillas/plantilla-modulo.md): objective,
scope, rules with their file, data model, API, permissions, audit log, tests and acceptance criteria.
