import { get } from "@shared/api";
import type { AuditLogEntry, AuditLogParams, Page } from "@shared/api";

/**
 * Bitácora de auditoría (`GET /v1/audit/log`, permiso `audit.view`).
 *
 * Devuelve un sobre paginado. **Los filtros son los que la ruta acepta**: estaban
 * inventados desde la pantalla, y un filtro que el servidor ignora no filtra y no
 * falla — devuelve todo, y el usuario cree que no hay más registros de los que ve.
 * Ahora la lista sale del propio contrato de la ruta.
 */
export const fetchAuditLog = (params: AuditLogParams = {}): Promise<Page<AuditLogEntry>> =>
  get<Page<AuditLogEntry>>("/audit/log", { params: { size: 100, ...params } });
