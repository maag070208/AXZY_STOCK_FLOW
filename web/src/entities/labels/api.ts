import { get } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { PrintJob } from "./model";

export const labelsApi = {
  printJobs: (query: PageQuery, filters: { status?: string }): Promise<Page<PrintJob>> =>
    get<Page<PrintJob>>("/labels/jobs", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),
};
