# Vision and scope
> **Normative.** What StockFlow builds, for whom and what stays out. Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** Scope in force; the app and the web are rebuilt in phases 10 and 11. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

## Problem

Almacén Integral (Cascar) keeps the inventory of an operation with warehouses, locations and
three clients: an administrative web, a floor app with a scanner and the API that
serves them. The current system is the legacy in `BASE/` (see [migracion/legacy-inventario.md](../migracion/legacy-inventario.md)).
Its core defects are in the model: the total per product does not know where the stock is,
the movement ledger is mutated and the access control has bypasses. The rewrite fixes
that without changing the flows the operation already uses.

## Objectives

1. An inventory that **balances by construction**: each change in stock is a new
   movement in the ledger, and the balance per location is a projection
   ([ADR-0007](../adr/0007-multi-bodega-desde-el-esquema.md), [arquitectura/dominio-kmp.md](../arquitectura/dominio-kmp.md)).
2. The business rules live in a single place, `stock-domain`, and the backend and the app share them.
3. Role-based and scope-based access control, denying by default
   ([ADR-0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md)).
4. Each write is recorded in the audit log with who, when and why
   ([seguridad/auditoria.md](../seguridad/auditoria.md)).

## Scope

| Inside | Outside (for now) |
|---|---|
| Catalog of products, locations and warehouses, with import of the initial Excel | Physical count as its own project ([R-73](../migracion/cutover.md)) |
| Stock per location, ledger, adjustments, locate and move | Electronic invoicing and accounting |
| Receiving: invoices, physical count, discrepancies with approval per invoice | Production ERP integration: the contract exists, the real dispatch does not ([ADR-0013](../adr/0013-ordenes-al-erp-con-reintentos.md)) |
| Picking: orders, packing, shortages | Periodic synchronization of the catalog with other systems ([ADR-0011](../adr/0011-catalogo-fuente-maestra.md)) |
| Transfers between locations and between warehouses | Real operation with more than one warehouse (the schema supports it, [ADR-0007](../adr/0007-multi-bodega-desde-el-esquema.md)) |
| Labels: templates, printing, reprinting | iOS tested on a device ([arranque](../../ARRANQUE.md)) |
| Users, roles, permissions, sessions, audit log and reports | |

## Users

| Who | Where they work | What they do |
|---|---|---|
| Floor operator | Mobile app (`mobile-app/`) | Receives, locates, picks, scans labels |
| Supervisor | Web (`web/`) | Approves discrepancies per invoice, queries stock and the ledger |
| Administrator | Web | Users, roles, catalog, warehouses, audit log |
| External systems | API with service key | Prints labels (Zebra), queries the ERP ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)) |

The specific permissions are in [seguridad/roles-permisos.md](../seguridad/roles-permisos.md).
