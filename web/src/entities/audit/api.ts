import { get } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { AuditEntry, AuditFilters } from "./model";

export const auditApi = {
  /** La consulta de la bitácora también se registra como acceso (M08): leerla deja huella. */
  log: (query: PageQuery, filters: AuditFilters): Promise<Page<AuditEntry>> =>
    get<Page<AuditEntry>>("/audit/log", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),
};
