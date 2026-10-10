import { get, post } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { PrintJob } from "./model";

export const labelsApi = {
  printJobs: (query: PageQuery, filters: { status?: string }): Promise<Page<PrintJob>> =>
    get<Page<PrintJob>>("/labels/jobs", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),

  /**
   * Reimprimir gasta una etiqueta más, por eso exige motivo y tiene su propio permiso. Responde el
   * trabajo nuevo (202), enlazado al original: el registro original no se reutiliza.
   */
  reprint: (id: number, reason: string): Promise<PrintJob> =>
    post<PrintJob>(`/labels/jobs/${id}/reprint`, { reason }),
};
