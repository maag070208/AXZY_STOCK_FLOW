# Legacy inventory (`BASE/`)
> **Reference.** What was in the previous system and what problems it had. The figures come from the analysis of 2026-10-07 ([historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md)
> §0 and §1); the code of `BASE/` has not been counted again. Last verified: 2026-10-09.

## What it is

Three repos under `BASE/`, all of the same warehouse system, with clients on a single API:

| Repo | Stack | Size (plan of 2026-10-07) | Role |
|---|---|---|---|
| `BASE/backend` | Node 16, Express 4, Prisma 4, MySQL (and SQL Server for synchronization) | ~4,000 LOC TS, 9 routers, 60 routes | Single API |
| `BASE/frontend` | React 18, Vite 3, Chakra 2, Redux | 4,833 LOC TSX, 13 pages | Administrative web |
| `BASE/mobile-app` | Expo 47, React Native 0.70, pure JS | 8,754 LOC JS, 26 screens | Floor operation (scanner) |

None of the three had automated tests at that time.

## What is kept

- The business flows: invoice receiving, location, picking, labels, stock.
  The list of routes is in [mapa-legacy-nuevo.md](../historico/MAPA-LEGACY-NUEVO-ANTERIOR.md).
- The business rules that the code already fulfilled well: the invoice and order states, and
  the way boxes and units are captured.

## What is discarded

- The security findings S1 to S8: routes without authentication, `role === 1` bypass, non-revocable JWT,
  SQL by concatenation, credentials in the code, `console.log` of sensitive data, authorization errors
  with 401, and login without an attempt limit. Each one has its correction in the new system ([seguridad/owasp.md](../seguridad/owasp.md)).
- The physical deletion of invoices and locations that was called "logical".
- The ledger that was mutated when locating ([ADR-0009](../adr/0009-ubicacion-de-recepcion-real.md)).

## Model defects that the new system corrects

1. **A single number per product.** The legacy does not know in which location the stock is. The new
   system keeps it per location ([ADR-0007](../adr/0007-multi-bodega-desde-el-esquema.md)).
2. **Two stock sources without a transaction.** `Store.stock` and the ledger were updated in
   separate steps. The new system derives the total from the projection.
3. **The difference field is never calculated.** The discrepancies had no number to audit
   ([ADR-0008](../adr/0008-discrepancias-y-merma.md)).

A correction to the original analysis: the legacy total **did not** become unbalanced when locating. What failed
was the historical record, which was mutated. The detail is in
[historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md) §2.7.
