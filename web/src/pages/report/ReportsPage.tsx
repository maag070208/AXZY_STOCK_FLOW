import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 10/13 del legacy (`Reports.tsx`): reportes.
 *
 * Aquí entra el reporte que el legacy no tenía y que es su mayor hueco:
 * `kardex-vs-projection` compara el ledger con la proyección derivada. Sin él,
 * el descuadre sólo se descubría con un conteo físico.
 */
export default function ReportsPage() {
  return (
    <PhasePlaceholder
      title="Reportes"
      description="Existencias con totales, movimientos agrupados y reconciliación ledger/proyección."
      breadcrumbLabel="Reportes"
      permission="report.view"
      endpoints={[
        "GET /v1/reports/inventory",
        "GET /v1/reports/movements-summary?from=&to=&groupBy=",
        "GET /v1/reports/kardex-vs-projection",
        "GET /v1/reports/kardex/export?format=xlsx|csv",
      ]}
    />
  );
}
