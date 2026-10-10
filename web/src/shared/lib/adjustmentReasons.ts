import i18n from "@shared/i18n";
/**
 * The adjustment reasons that a decision or a shortage accepts. The API does not expose them in any
 * endpoint: this list mirrors `AdjustmentReason` from the domain (`stock-domain/.../inventory/Adjustment.kt`). If the
 * domain gains a reason, it must be added here; the API would reject it earlier with `REASON_REQUIRED`.
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
