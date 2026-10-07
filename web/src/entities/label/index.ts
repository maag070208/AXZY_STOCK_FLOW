/** Entidad `label`: plantillas ZPL, preview, impresión y cola de trabajos (§8). */
export {
  createLabelTemplate,
  fetchLabelJob,
  fetchLabelJobs,
  fetchLabelTemplate,
  fetchLabelTemplates,
  previewLabel,
  printLabels,
  reprintLabelJob,
  updateLabelTemplate,
} from "./api/labelApi";
export type {
  LabelFieldSchema,
  LabelFieldSpec,
  LabelFieldType,
  LabelJobDto,
  LabelJobStatus,
  LabelPreviewRequest,
  LabelPreviewResponseDto,
  LabelPrintRequest,
  LabelPrintResponseDto,
  LabelReprintRequest,
  LabelTemplateDto,
} from "./model/types";
