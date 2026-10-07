import { downloadFile, get } from "@shared/api";
import type { QueryParams } from "@shared/api/lib/http";
import type {
  InventoryReport,
  KardexVsProjectionReport,
  MovementsGroupBy,
  MovementsSummary,
  OperationalDate,
  ReportFormat,
} from "@shared/api";

/**
 * API de reportes (§9 del contrato).
 *
 * Las exportaciones son la parte delicada: **los filtros del archivo son los
 * mismos que los del listado**. Si el usuario ve 200 filas filtradas y exporta,
 * tiene que recibir esas 200; por eso los parámetros se pasan tal cual al
 * endpoint de exportación en vez de reconstruirlos.
 */

export const fetchInventoryReport = (
  params: QueryParams & { warehouseId?: number; onlyWithStock?: boolean } = {}
): Promise<InventoryReport> =>
  get<InventoryReport>("/reports/inventory", { params: { size: 50, ...params } });

/**
 * Filtros de la exportación de kardex.
 *
 * **Los mismos que los del listado** (si el usuario ve 200 filas filtradas y
 * exporta, debe recibir esas 200). Se declara como interfaz que extiende
 * `QueryParams` para poder pasarlos tal cual al cliente HTTP.
 */
export interface KardexExportParams extends QueryParams {
  format: ReportFormat;
  productCode?: string;
  from?: OperationalDate;
  to?: OperationalDate;
  page?: number;
  size?: number;
}

export const exportKardex = (
  params: KardexExportParams
): Promise<{ blob: Blob; filename: string }> =>
  downloadFile("/reports/kardex/export", {
    params,
    fallbackFilename: `kardex.${params.format}`,
  });

export const fetchMovementsSummary = (params: {
  from: OperationalDate;
  to: OperationalDate;
  groupBy?: MovementsGroupBy;
}): Promise<MovementsSummary> => get<MovementsSummary>("/reports/movements-summary", { params });

/** El reporte de reconciliación: ledger contra proyección. No existía en el legacy. */
export const fetchKardexVsProjection = (): Promise<KardexVsProjectionReport> =>
  get<KardexVsProjectionReport>("/reports/kardex-vs-projection");
