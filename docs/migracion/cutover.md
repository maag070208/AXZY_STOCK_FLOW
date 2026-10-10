# Cutover to the new system
> **Plan.** Steps of the cutover and of its reversal. Status: pending execution. Last verified: 2026-10-09.

## Prerequisites

1. Physical count of the 1,398 products and reconciliation against the inherited total
   ([ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md)). **Pending.**
2. Walkthrough of the web and app flows against the backend ([roadmap](../historico/ROADMAP-ANTERIOR.md)).
   **Pending.**
3. Verified database backup, with a tested restore ([operacion/respaldos.md](../operacion/respaldos.md)).
   **Pending: there is no backup mechanism in the repo.**
4. Open decisions closed: the ERP (`external_id`, D10), and the `kardo` module
   ([M09](../modulos/M09-integracion-externa/README.md)).

## Steps

1. Freeze the legacy: no new records in `BASE/` from the start of the count.
2. Import the catalog ([catalogo-excel.md](catalogo-excel.md)) in strict mode.
3. Load the initial stock to `UNASSIGNED` (the import itself does it, with the
   `INITIAL` movement of each product without stock).
4. Run the count and the adjustments with a reason to bring the stock to its counted value.
5. Verify with `comprobar_proyecciones()` that the projection balances with the ledger.
6. Open the new system to operators by role ([M07](../modulos/M07-usuarios-accesos/README.md)).

## Reversal

- Until step 6, the legacy remains in service and it is possible to go back to it without loss.
- After step 6, the reversal is **restoring the backup from before the cutover**. The writes made in the new
  system between the cutover and the reversal are lost, which is why the cutover is done with a short
  observation period.

## What this document does not decide

- The length of the observation period.
- Who signs the cutover. It is a business decision, not a technical one.
