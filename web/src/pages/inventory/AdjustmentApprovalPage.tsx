import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla **nueva**: ajustes por encima del umbral, esperando aprobación.
 *
 * Existe porque el contrato devuelve `201` con `"status": "PENDING_APPROVAL"`:
 * sin una bandeja, ese ajuste queda invisible y el inventario no cuadra sin que
 * nadie sepa por qué.
 */
export default function AdjustmentApprovalPage() {
  return (
    <PhasePlaceholder
      title="Ajustes por aprobar"
      description="Ajustes que superaron el umbral y esperan autorización."
      breadcrumbLabel="Almacén"
      permission="inventory.approve_adjustment"
      endpoints={["GET /v1/inventory/adjustments/pending", "POST /v1/inventory/adjustments/{id}/approve"]}
    />
  );
}
