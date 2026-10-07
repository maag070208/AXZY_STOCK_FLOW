import { get } from "@shared/api";
import type { AuditLogEntry, AuditLogParams, Page } from "@shared/api";

/**
 * Bitácora de auditoría (`GET /v1/audit/log`, permiso `audit.view`).
 *
 * La forma exacta de los parámetros no está en `CONTRATO_API.md` más allá de la
 * tabla de permisos del §11; se declaran los filtros que la pantalla necesita y
 * se marca como punto a cerrar con el backend en la Fase 5.
 */
export const fetchAuditLog = (params: AuditLogParams = {}): Promise<Page<AuditLogEntry>> =>
  get<Page<AuditLogEntry>>("/audit/log", { params: { size: 50, ...params } });
