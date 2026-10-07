import { api, get } from "@shared/api";
import type {
  LabelJob,
  LabelPrintRequest,
  LabelPrintResponse,
  LabelPreviewRequest,
  LabelPreviewResponse,
  LabelTemplate,
  Page,
  PageParams,
} from "@shared/api";

/**
 * API de etiquetas (§8 del contrato).
 *
 * La máquina de estados es la parte que importa: `PENDING → SENT → PRINTED |
 * FAILED`. **Una etiqueta no se marca impresa hasta que la impresora confirma**;
 * el legacy la marcaba sin esperar respuesta y si la Zebra estaba apagada la
 * etiqueta se perdía para siempre.
 */

/** El listado **omite `zpl`** (es grande): sólo el detalle lo trae. */
export const fetchLabelTemplates = (): Promise<Page<LabelTemplate>> =>
  get<Page<LabelTemplate>>("/label-templates", { params: { size: 200 } });

export const fetchLabelTemplate = (id: number): Promise<LabelTemplate> =>
  get<LabelTemplate>(`/label-templates/${id}`);

export const createLabelTemplate = (
  body: Partial<LabelTemplate>
): Promise<LabelTemplate> => api.post<LabelTemplate>("/label-templates", body);

export const updateLabelTemplate = (
  id: number,
  body: Partial<LabelTemplate>
): Promise<LabelTemplate> => api.put<LabelTemplate>(`/label-templates/${id}`, body);

/** ZPL resuelto **sin imprimir**. Lo usan web y móvil para el preview. */
export const previewLabel = (body: LabelPreviewRequest): Promise<LabelPreviewResponse> =>
  api.post<LabelPreviewResponse>("/labels/preview", body);

/** `202 Accepted` con `jobIds`: el trabajo queda `PENDING` hasta que la Zebra confirma. */
export const printLabels = (body: LabelPrintRequest): Promise<LabelPrintResponse> =>
  api.post<LabelPrintResponse>("/labels/print", body);

export const fetchLabelJob = (id: number): Promise<LabelJob> =>
  get<LabelJob>(`/labels/jobs/${id}`);

export const fetchLabelJobs = (
  params: PageParams & { status?: string } = {}
): Promise<Page<LabelJob>> =>
  get<Page<LabelJob>>("/labels/jobs", { params: { size: 50, ...params } });

/** Genera un **trabajo nuevo** enlazado al original; exige motivo. */
export const reprintLabelJob = (id: number, reason: string): Promise<LabelPrintResponse> =>
  api.post<LabelPrintResponse>(`/labels/jobs/${id}/reprint`, { reason });
