import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla **nueva**: el legacy no tenía bandeja de pendientes de aprobación.
 *
 * Sin ella, una discrepancia quedaba bloqueada sin que nadie supiera que había
 * que aprobarla. Es una de las pantallas que el plan marca como obligatorias
 * para que la regla B1 no se convierta en un callejón sin salida.
 */
export default function PendingApprovalPage() {
  return (
    <PhasePlaceholder
      title="Pendientes de aprobación"
      description="Facturas con diferencias esperando aprobación. Pantalla nueva respecto al legacy."
      breadcrumbLabel="Recepción"
      permission="invoice.approve"
      endpoints={["GET /v1/invoices/pending-approval", "POST /v1/invoices/{id}/approve"]}
    />
  );
}
