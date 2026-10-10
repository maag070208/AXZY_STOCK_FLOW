/** La bitácora (docs/api/auditoria.md). Los estados son texto JSON tal cual lo guardó la base. */
export type AuditEntry = {
  id: number;
  occurredAt: string;
  businessDate: string;
  actorUserId: number | null;
  actorUsername: string | null;
  actorName: string | null;
  actorRoles: string[];
  channel: string;
  entityType: string;
  entityId: string | null;
  entityLabel: string | null;
  action: string;
  level: string;
  previousState: string | null;
  newState: string | null;
  reason: string | null;
  note: string | null;
  permissionKey: string | null;
  decision: string | null;
  result: string;
  errorCode: string | null;
  errorMessage: string | null;
  serviceKeyId: number | null;
  requestId: string | null;
  idempotencyKey: string | null;
};

export type AuditFilters = {
  entityType?: string;
  entityId?: string;
  actorUserId?: number;
  action?: string;
  level?: string;
  result?: string;
  channel?: string;
  from?: string;
  to?: string;
};
