import i18n from "@shared/i18n";
/**
 * Los motivos de ajuste que acepta una decisión o un faltante. La API no los expone en ningún
 * endpoint: esta lista espeja `AdjustmentReason` del dominio (`stock-domain/.../inventory/Adjustment.kt`). Si el
 * dominio gana un motivo, hay que añadirlo aquí; la API lo rechazaría antes con `REASON_REQUIRED`.
 */
export const ADJUSTMENT_REASONS: readonly { value: string; label: string }[] = [
  { value: "SUPPLIER_ERROR", label: i18n.t("reasons.supplierError") },
  { value: "COUNT_ERROR", label: i18n.t("reasons.countError") },
  { value: "SHORTAGE", label: i18n.t("reasons.shortage") },
  { value: "DAMAGE", label: i18n.t("reasons.damage") },
  { value: "RETURN_TO_SUPPLIER", label: i18n.t("reasons.returnToSupplier") },
  { value: "EXPIRED", label: i18n.t("reasons.expired") },
  { value: "THEFT", label: i18n.t("reasons.theft") },
  { value: "OTHER", label: i18n.t("reasons.other") },
];
