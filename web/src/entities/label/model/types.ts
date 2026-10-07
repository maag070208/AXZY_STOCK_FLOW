import type {
  LabelFieldSchema,
  LabelFieldSpec,
  LabelFieldType,
  LabelJob,
  LabelJobStatus,
  LabelPreviewRequest,
  LabelPreviewResponse,
  LabelPrintRequest,
  LabelPrintResponse,
  LabelReprintRequest,
  LabelTemplate,
} from "@shared/api";

/** Alias de los DTOs del contrato que expone esta entidad. */
export type LabelTemplateDto = LabelTemplate;
export type LabelJobDto = LabelJob;
export type LabelPreviewResponseDto = LabelPreviewResponse;
export type LabelPrintResponseDto = LabelPrintResponse;

export type {
  LabelFieldSchema,
  LabelFieldSpec,
  LabelFieldType,
  LabelJobStatus,
  LabelPreviewRequest,
  LabelPrintRequest,
  LabelReprintRequest,
};
