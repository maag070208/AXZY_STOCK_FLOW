import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 4/13 del legacy (`InvoiceDetail.tsx`): detalle y captura.
 *
 * Reglas que la pantalla real debe respetar y que ya están en el contrato:
 * - `difference` la calcula el servidor; la UI **no** la recalcula.
 * - Aprobar es **por factura** (R-86), no línea por línea.
 * - Una línea en `DISCREPANCY` no se puede ubicar hasta aprobarse (regla B1).
 */
export default function InvoiceDetailPage() {
  return (
    <PhasePlaceholder
      title="Detalle de factura"
      description="Líneas, captura física, aprobación de diferencias y ubicación."
      breadcrumbLabel="Recepción"
      permission="invoice.capture"
      endpoints={[
        "GET /v1/invoices/{id}",
        "POST /v1/invoices/{id}/lines/{lineId}/capture",
        "POST /v1/invoices/{id}/approve",
        "POST /v1/inventory/locate",
      ]}
    />
  );
}
