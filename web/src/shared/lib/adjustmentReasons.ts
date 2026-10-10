/**
 * Los motivos de ajuste que acepta una decisión o un faltante. La API no los expone en ningún
 * endpoint: esta lista espeja `AdjustmentReason` del dominio (`stock-domain/.../inventory/Adjustment.kt`). Si el
 * dominio gana un motivo, hay que añadirlo aquí; la API lo rechazaría antes con `REASON_REQUIRED`.
 */
export const ADJUSTMENT_REASONS: readonly { value: string; label: string }[] = [
  { value: "SUPPLIER_ERROR", label: "Error del proveedor" },
  { value: "COUNT_ERROR", label: "Error de conteo" },
  { value: "SHORTAGE", label: "Faltante" },
  { value: "DAMAGE", label: "Daño" },
  { value: "RETURN_TO_SUPPLIER", label: "Devolución a proveedor" },
  { value: "EXPIRED", label: "Caducado" },
  { value: "THEFT", label: "Robo" },
  { value: "OTHER", label: "Otro" },
];
