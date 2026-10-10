import { get } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { AuditEntry, AuditFilters } from "./model";

export const auditApi = {
  /** The audit-log query is also recorded as access (M08): reading it leaves a trace. */
  log: (query: PageQuery, filters: AuditFilters): Promise<Page<AuditEntry>> =>
    get<Page<AuditEntry>>("/audit/log", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),
};
