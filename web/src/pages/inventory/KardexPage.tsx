import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 9/13 del legacy (`Kardex.tsx`): movimientos por producto y fechas.
 *
 * Dos cambios de fondo respecto al legacy:
 * - El ledger es **append-only**: no existe `PUT`/`DELETE` de kardex. Un ajuste
 *   es un movimiento nuevo, no una corrección del anterior.
 * - La tabla se pagina en el servidor; el legacy traía meses de movimientos.
 */
export default function KardexPage() {
  return (
    <PhasePlaceholder
      title="Kardex"
      description="Libro de movimientos: append-only, paginado y filtrable por fecha y tipo."
      breadcrumbLabel="Almacén"
      permission="kardex.view"
      endpoints={[
        "GET /v1/inventory/kardex?productCode=&from=&to=&moveType=",
        "GET /v1/reports/kardex/export?format=xlsx|csv",
      ]}
    />
  );
}
