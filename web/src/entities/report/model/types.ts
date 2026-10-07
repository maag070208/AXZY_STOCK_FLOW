import type {
  InventoryReport,
  InventoryReportItem,
  KardexVsProjectionItem,
  KardexVsProjectionReport,
  MovementsGroupBy,
  MovementsSummary,
  MovementsSummaryItem,
  ReportFormat,
} from "@shared/api";

/** Alias de los DTOs del contrato que expone esta entidad. */
export type InventoryReportDto = InventoryReport;
export type InventoryReportItemDto = InventoryReportItem;
export type KardexVsProjectionReportDto = KardexVsProjectionReport;
export type KardexVsProjectionItemDto = KardexVsProjectionItem;
export type MovementsSummaryDto = MovementsSummary;
export type MovementsSummaryItemDto = MovementsSummaryItem;

export type { MovementsGroupBy, ReportFormat };
