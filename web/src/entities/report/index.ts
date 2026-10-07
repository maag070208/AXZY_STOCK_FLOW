/** Entidad `report`: existencias, exportación de kardex y reconciliación (§9). */
export {
  exportKardex,
  fetchInventoryReport,
  fetchKardexVsProjection,
  fetchMovementsSummary,
} from "./api/reportApi";
export type {
  InventoryReportDto,
  InventoryReportItemDto,
  KardexVsProjectionItemDto,
  KardexVsProjectionReportDto,
  MovementsGroupBy,
  MovementsSummaryDto,
  MovementsSummaryItemDto,
  ReportFormat,
} from "./model/types";
