import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 6/13 del legacy (`OrderDetail.tsx`): detalle y empaque.
 *
 * Reglas del contrato que la pantalla real debe respetar:
 * - Empaquetar descuenta stock y escribe `FULFILLMENT_OUT` en el kardex.
 * - Si no alcanza: `422 INSUFFICIENT_STOCK` con `details.available`, y el
 *   faltante se registra con un **ajuste** de origen `FULFILLMENT_SHORTAGE`.
 * - Confirmar descuenta las líneas pendientes y dispara la exportación al ERP.
 */
export default function OrderDetailPage() {
  return (
    <PhasePlaceholder
      title="Detalle de orden"
      description="Empaque por línea, faltantes, confirmación y reintento de exportación."
      breadcrumbLabel="Surtido"
      permission="order.package"
      endpoints={[
        "GET /v1/orders/{id}",
        "POST /v1/orders/{id}/lines/{lineId}/package",
        "POST /v1/orders/{id}/shortage",
        "POST /v1/orders/{id}/confirm",
        "POST /v1/orders/{id}/export",
      ]}
    />
  );
}
