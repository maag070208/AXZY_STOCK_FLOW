import { get, post } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { PrintJob } from "./model";

export const labelsApi = {
  printJobs: (query: PageQuery, filters: { status?: string }): Promise<Page<PrintJob>> =>
    get<Page<PrintJob>>("/labels/jobs", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),

  /**
   * Reprinting uses up one more label, which is why it requires a reason and has its own permission. It responds
   * new job (202), linked to the original: the original record is not reused.
   */
  reprint: (id: number, reason: string): Promise<PrintJob> =>
    post<PrintJob>(`/labels/jobs/${id}/reprint`, { reason }),
};
