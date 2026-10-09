import { api, get } from "@shared/api";
import type {
  LabelJob,
  LabelJobPage,
  LabelPrintRequest,
  LabelPrintResponse,
  LabelPreviewRequest,
  LabelPreviewResponse,
  LabelTemplate,
  LabelTemplateCreateRequest,
  LabelTemplateUpdateRequest,
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

/**
 * El listado de plantillas.
 *
 * **Devuelve un ARRAY, no un sobre de paginación.** Estaba declarado como
 * `Page<LabelTemplate>`, así que `pagina.items` habría sido `undefined` y la
 * pantalla habría salido vacía **sin fallar** — el mismo defecto que tenía el
 * informe de reconciliación. El listado **omite `zpl`** (es grande): solo el
 * detalle lo trae.
 */
export const fetchLabelTemplates = (onlyActive = false): Promise<LabelTemplate[]> =>
  get<LabelTemplate[]>("/label-templates", { params: onlyActive ? { onlyActive: true } : {} });

export const fetchLabelTemplate = (id: number): Promise<LabelTemplate> =>
  get<LabelTemplate>(`/label-templates/${id}`);

export const createLabelTemplate = (
  body: LabelTemplateCreateRequest
): Promise<LabelTemplate> => api.post<LabelTemplate>("/label-templates", body);

export const updateLabelTemplate = (
  id: number,
  body: LabelTemplateUpdateRequest
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
): Promise<LabelJobPage> =>
  get<LabelJobPage>("/labels/jobs", { params: { size: 50, ...params } });

/** Genera un **trabajo nuevo** enlazado al original; exige motivo. */
/**
 * Reimprime: **crea un trabajo NUEVO enlazado al original.**
 *
 * **Devuelve el trabajo nuevo, no un `jobIds`.** La API responde `202` con el
 * trabajo de reimpresión; declararlo como `LabelPrintResponse` habría hecho leer
 * `jobIds` de un objeto que no lo tiene, y la pantalla no podría decir qué trabajo
 * se creó. El motivo es obligatorio: la trazabilidad exige saber quién pidió la
 * reimpresión y por qué.
 */
export const reprintLabelJob = (id: number, reason: string): Promise<LabelJob> =>
  api.post<LabelJob>(`/labels/jobs/${id}/reprint`, { reason });
