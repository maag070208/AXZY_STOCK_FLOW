/**
 * DTOs del contrato HTTP (CONTRATO_API.md).
 *
 * Reglas que se respetan aquí y conviene no "mejorar":
 * - Las cantidades son `number` con 3 decimales, nunca `string` (§1.7). El legacy
 *   mezclaba `Decimal` de Prisma con `number` de JSON y de ahí venían los
 *   descuadres de redondeo.
 * - Los identificadores son `number`; las claves de negocio (`productCode`,
 *   `invoiceNumber`, `code`) son `string`.
 * - Los instantes son ISO 8601 en UTC con `Z`; las fechas operativas, `YYYY-MM-DD`
 *   (el turno de noche usa la fecha de negocio, no la del reloj).
 * - Los enums se declaran como uniones de literales: un valor fuera de la unión
 *   es un error de compilación, no una cadena que se cuela a la UI.
 */

/* ------------------------------------------------------------------ comunes */

/** Sobre de paginación de toda lista (§1.4). `page` empieza en 0. */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** Parámetros de paginación y búsqueda aceptados por los listados. */
export interface PageParams {
  page?: number;
  size?: number;
  sort?: string;
  q?: string;
}

/** Cuerpo de error único de la API (§1.3). */
export interface ErrorResponse {
  code: string;
  message: string;
  details?: Record<string, unknown> | null;
}

/** Instante ISO 8601 en UTC, p. ej. `2026-10-07T21:24:37.688Z`. */
export type Instant = string;
/** Fecha operativa sin hora, p. ej. `2026-10-07`. */
export type OperationalDate = string;

/* --------------------------------------------------------------------- auth */

export type PermissionScope = "NONE" | "OWN" | "AREA" | "ALL";

/**
 * Mapa permiso → alcance que entrega la API. Es la forma real del campo
 * `scopes`/`permissions` del contrato: `{ "invoice.approve": "AREA" }`.
 */
export type PermissionMap = Record<string, PermissionScope>;

export interface AuthUser {
  id: number;
  username: string;
  name: string;
  roles: string[];
  /** Permisos efectivos, ya resueltos por el servidor. */
  permissions: string[];
  /** Alcance por permiso. Sólo aparecen los permisos con alcance ≠ NONE. */
  scopes: PermissionMap;
  warehouses: number[];
  mustChangePassword: boolean;
}

export interface LoginRequest {
  username: string;
  password: string;
}

/** Respuesta de `POST /v1/auth/login` y de `POST /v1/auth/refresh`. */
export interface AuthTokensResponse {
  accessToken: string;
  refreshToken: string;
  /** Vigencia del access token **en segundos**, que es lo que esperan los clientes. */
  expiresIn: number;
  user: AuthUser;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/**
 * Códigos de error de autenticación que el cliente **debe** distinguir (§2).
 * Un `401` genérico no sirve: la acción del usuario es distinta en cada caso.
 */
export type AuthErrorCode =
  | "INVALID_CREDENTIALS"
  | "ACCOUNT_DISABLED"
  | "ACCOUNT_LOCKED"
  | "RATE_LIMITED"
  | "TOKEN_REUSED"
  | "UNAUTHENTICATED";

/** Códigos de autorización del §1.3. Colapsarlos en un 403 es el defecto del legacy. */
export type AuthorizationErrorCode =
  | "UNAUTHENTICATED"
  | "PERMISSION_DENIED"
  | "SCOPE_INSUFFICIENT"
  | "POLICY_DENIED"
  | "SEGREGATION_OF_DUTIES";

/* ---------------------------------------------------------------- catálogo */

export interface Product {
  id: number;
  productCode: string;
  name: string;
  description: string | null;
  unitMeasure: string;
  externalId: number | null;
  active: boolean;
  /** Total derivado de `stock_by_location` + `stock_in_transit`, en 3 decimales. */
  stock: number;
}

export interface ProductCreateRequest {
  productCode: string;
  name: string;
  description?: string | null;
  unitMeasure: string;
}

export type ProductSort = "productCode" | "name" | "createdAt";

export interface ProductListParams extends PageParams {
  active?: boolean;
}

/** Fila rechazada por el importador. `rowNumber` es obligatorio: sin él el usuario no corrige el archivo. */
export interface ProductImportRejection {
  rowNumber: number;
  productCode: string | null;
  reason: string;
}

/** Informe de `POST /v1/products/import`. Con `dryRun=true` no escribe nada. */
export interface ProductImportReport {
  dryRun: boolean;
  totalRows: number;
  accepted: number;
  rejected: number;
  totalStock: number;
  zeroStockCount: number;
  unitMeasures: string[];
  rejections: ProductImportRejection[];
}

/** `SHELF` (estante) o `RECEIVING` (recepción). La de recepción es real, con id real. */
export type LocationKind = "SHELF" | "RECEIVING";

export interface Location {
  id: number;
  warehouseId: number;
  warehouseCode: string;
  hall: string;
  code: string;
  position: string;
  levels: number;
  kind: LocationKind;
  active: boolean;
  displayName: string;
}

export interface LocationCreateRequest {
  warehouseId: number;
  hall: string;
  code: string;
  position: string;
  levels: number;
  kind?: LocationKind;
}

export interface Warehouse {
  id: number;
  code: string;
  name: string;
  isDefault: boolean;
  active: boolean;
}

/* --------------------------------------------------------------- inventario */

export interface StockItem {
  productId: number;
  productCode: string;
  locationId: number;
  locationDisplayName: string;
  warehouseId: number;
  quantity: number;
}

export interface StockParams extends PageParams {
  productId?: number;
  locationId?: number;
  warehouseId?: number;
  onlyWithStock?: boolean;
}

export type MoveType =
  | "RECEIPT_IN"
  | "LOCATE"
  | "MOVE"
  | "ADJUSTMENT_IN"
  | "ADJUSTMENT_OUT"
  | "FULFILLMENT_OUT"
  | "TRANSFER_OUT"
  | "TRANSFER_IN";

/** Asiento del kardex. El ledger es append-only: no hay `PUT` ni `DELETE`. */
export interface KardexEntry {
  id: number;
  movedAt: Instant;
  productCode: string;
  moveType: MoveType;
  units: number;
  quantityAfter: number;
  fromLocationId: number | null;
  fromLocationDisplayName: string | null;
  toLocationId: number | null;
  toLocationDisplayName: string | null;
  container: string | null;
  orderNumber: string | null;
  adjustmentReason: AdjustmentReason | null;
  userId: number | null;
  userName: string | null;
  note: string | null;
}

export interface KardexParams extends PageParams {
  productCode?: string;
  productId?: number;
  locationId?: number;
  from?: OperationalDate;
  to?: OperationalDate;
  moveType?: MoveType;
}

export type AdjustmentDirection = "IN" | "OUT";

/**
 * Motivo del ajuste. **Obligatorio siempre** (§4): un ajuste sin motivo no es
 * auditable y por eso la API responde `REASON_REQUIRED`.
 */
export type AdjustmentReason =
  | "SHORTAGE"
  | "DAMAGE"
  | "THEFT"
  | "SUPPLIER_ERROR"
  | "COUNT_ERROR"
  | "RETURN_TO_SUPPLIER"
  | "EXPIRED"
  | "OTHER";

/**
 * Origen del ajuste. Los cuatro orígenes del legacy se unifican en la regla
 * única `AdjustStock`; no hay un segundo flujo por origen.
 */
export type AdjustmentOrigin =
  | "INVOICE_DISCREPANCY"
  | "FULFILLMENT_SHORTAGE"
  | "MANUAL_COUNT"
  | "DAMAGE_REPORT";

export type AdjustmentStatus = "APPLIED" | "PENDING_APPROVAL";

export interface AdjustmentRequest {
  productId: number;
  locationId: number;
  quantity: number;
  direction: AdjustmentDirection;
  reason: AdjustmentReason;
  origin: AdjustmentOrigin;
  note?: string | null;
  /** Alternativa en cuerpo a la cabecera `Idempotency-Key`. */
  idempotencyKey?: string;
}

export interface AdjustmentResult {
  id: number;
  productId: number;
  locationId: number;
  quantity: number;
  direction: AdjustmentDirection;
  reason: AdjustmentReason;
  origin: AdjustmentOrigin;
  /** `PENDING_APPROVAL` cuando el ajuste supera el umbral y exige aprobación. */
  status: AdjustmentStatus;
  movedAt: Instant;
}

export interface LocateRequest {
  invoiceLineId: number;
  toLocationId: number;
  quantity: number;
  idempotencyKey?: string;
}

export interface MoveRequest {
  productId: number;
  fromLocationId: number;
  toLocationId: number;
  quantity: number;
  idempotencyKey?: string;
}

/** Resultado de ubicar o mover: un traslado no cambia el total. */
export interface StockMovementResult {
  id: number;
  moveType: MoveType;
  units: number;
  quantityAfter: number;
  fromLocationId: number | null;
  toLocationId: number | null;
  movedAt: Instant;
}

/* ---------------------------------------------------------------- recepción */

export type InvoiceStatus =
  | "PENDING"
  | "IN_PROGRESS"
  | "PENDING_TO_LOCATE"
  | "COMPLETED"
  | "CANCELLED";

export type InvoiceType = "NATIONAL" | "IMPORT" | "SERVICE";

export interface InvoiceSummary {
  id: number;
  invoiceNumber: string;
  receiptDate: OperationalDate;
  invoiceType: InvoiceType;
  status: InvoiceStatus;
  customerCode: string | null;
  concept: string | null;
  totalLines: number;
  locatedLines: number;
  pendingLines: number;
  createdAt: Instant;
}

/**
 * Estados de línea. `DISCREPANCY` bloquea la ubicación hasta que se apruebe
 * (regla B1) y `PENDING_APPROVAL` es la bandeja nueva del §5.
 */
export type InvoiceLineStatus =
  | "PENDING"
  | "CAPTURED"
  | "DISCREPANCY"
  | "PENDING_APPROVAL"
  | "APPROVED"
  | "ASSIGNED"
  | "LOCATED"
  | "CANCELLED";

export interface InvoiceLine {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  container: string | null;
  unitMeasure: string;
  /** Cantidad según el documento. */
  quantity: number;
  boxQuantity: number;
  unitsPerBox: number;
  /** Cantidad contada físicamente; `null` mientras no se capture. */
  physicalQuantity: number | null;
  physicalBoxQuantity: number | null;
  locatedQuantity: number;
  /** `physicalQuantity - quantity`, calculada por el servidor (columna generada). */
  difference: number;
  status: InvoiceLineStatus;
  capturedBy: number | null;
  capturedByName: string | null;
  capturedAt: Instant | null;
  approvedBy: number | null;
  approvedByName: string | null;
  approvedAt: Instant | null;
  adjustmentReason: AdjustmentReason | null;
  locationId: number | null;
  locationDisplayName: string | null;
}

export interface InvoiceDetail extends Omit<InvoiceSummary, "totalLines" | "locatedLines" | "pendingLines"> {
  lines: InvoiceLine[];
}

export interface InvoiceListParams extends PageParams {
  status?: InvoiceStatus;
  from?: OperationalDate;
  to?: OperationalDate;
}

export interface InvoiceCreateRequest {
  invoiceNumber: string;
  receiptDate: OperationalDate;
  invoiceType: InvoiceType;
  customerCode?: string | null;
  concept?: string | null;
}

export interface CaptureLineRequest {
  physicalQuantity: number;
  physicalBoxQuantity?: number | null;
  idempotencyKey?: string;
}

export interface ApproveInvoiceRequest {
  adjustmentReason: AdjustmentReason;
  note?: string | null;
}

/* ------------------------------------------------------------------ surtido */

export type OrderStatus =
  | "PENDING"
  | "IN_PROGRESS"
  | "PACKAGED"
  | "PENDING_TO_OUT"
  | "COMPLETED"
  | "CANCELLED";

export interface OrderSummary {
  id: number;
  orderNumber: string;
  packagingDate: OperationalDate;
  status: OrderStatus;
  customerCode: string | null;
  concept: string | null;
  converted: boolean;
  convertedAt: Instant | null;
  exportStatus: OrderExportStatus | null;
  exportAttempts: number;
  exportLastError: string | null;
  totalLines: number;
  packagedLines: number;
}

export interface OrderLine {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  quantity: number;
  packagedQuantity: number;
  unitMeasure: string;
  locationId: number | null;
  locationDisplayName: string | null;
  status: InvoiceLineStatus;
}

export interface OrderDetail extends Omit<OrderSummary, "totalLines" | "packagedLines"> {
  lines: OrderLine[];
}

export interface OrderListParams extends PageParams {
  status?: OrderStatus;
}

export interface PackageLineRequest {
  quantity: number;
  locationId: number;
  idempotencyKey?: string;
}

/** Faltante de surtido: delega en la regla única de ajuste, no en un flujo nuevo. */
export interface OrderShortageRequest {
  lineId: number;
  quantity: number;
  reason: AdjustmentReason;
  note?: string | null;
}

export type OrderExportStatus = "PENDING" | "SENT" | "FAILED" | "CONFIRMED";

/** Resultado de `POST /v1/orders/{id}/export`. Es idempotente por `orderNumber`. */
export interface OrderExportResult {
  attempts: number;
  status: OrderExportStatus;
  lastError: string | null;
  nextRetryAt: Instant | null;
}

/* --------------------------------------------------------------- traslados */

/** `DRAFT → DISPATCHED → IN_TRANSIT → RECEIVED`, con `CANCELLED` en cualquier punto. */
export type TransferStatus = "DRAFT" | "DISPATCHED" | "IN_TRANSIT" | "RECEIVED" | "CANCELLED";

export interface Transfer {
  id: number;
  code: string;
  fromWarehouseId: number;
  fromWarehouseCode: string;
  toWarehouseId: number;
  toWarehouseCode: string;
  status: TransferStatus;
  lineCount: number;
  totalUnits: number;
  dispatchedBy: number | null;
  dispatchedAt: Instant | null;
  receivedBy: number | null;
  receivedAt: Instant | null;
}

/** Un traslado sin recibir es deuda visible: lleva responsable y días transcurridos. */
export interface InTransitTransfer extends Transfer {
  responsibleName: string | null;
  daysInTransit: number;
}

export interface TransferListParams extends PageParams {
  status?: TransferStatus;
}

/* --------------------------------------------------------------- etiquetas */

export type LabelFieldType = "string" | "number" | "boolean";

export interface LabelFieldSpec {
  type: LabelFieldType;
  label: string;
  required: boolean;
}

/** Esquema de campos de una plantilla: `{ productCode: { type, label, required } }`. */
export type LabelFieldSchema = Record<string, LabelFieldSpec>;

export interface LabelTemplate {
  id: number;
  code: string;
  name: string;
  description: string | null;
  size: string;
  isActive: boolean;
  fieldSchema: LabelFieldSchema;
  /** Sólo llega en el detalle: el listado lo omite porque es grande. */
  zpl?: string;
}

export interface LabelPreviewRequest {
  templateId: number;
  data: Record<string, string | number | boolean>;
}

export interface LabelPreviewResponse {
  zpl: string;
  preview: {
    widthMm: number;
    heightMm: number;
    copies: number;
  };
}

export interface LabelPrintRequest {
  templateId: number;
  copies: number;
  data: Array<Record<string, string | number | boolean>>;
}

/** `202 Accepted`: el trabajo queda `PENDING` hasta que la impresora confirma. */
export interface LabelPrintResponse {
  jobIds: number[];
  status: LabelJobStatus;
}

export type LabelJobStatus = "PENDING" | "SENT" | "PRINTED" | "FAILED";

export interface LabelJob {
  id: number;
  templateCode: string;
  copies: number;
  status: LabelJobStatus;
  attempts: number;
  lastError: string | null;
  printedAt: Instant | null;
  createdAt: Instant;
}

export interface LabelReprintRequest {
  /** Obligatorio: la trazabilidad de una reimpresión exige saber por qué. */
  reason: string;
}

/* ---------------------------------------------------------------- reportes */

export interface InventoryReportItem {
  productCode: string;
  name: string;
  locationDisplayName: string;
  quantity: number;
  warehouseCode: string;
}

export interface InventoryReport extends Page<InventoryReportItem> {
  totals: {
    distinctProducts: number;
    totalUnits: number;
  };
}

export type ReportFormat = "xlsx" | "csv";
export type MovementsGroupBy = "day" | "week" | "month" | "product" | "location";

export interface MovementsSummaryItem {
  period: string;
  moveType: MoveType;
  units: number;
  movements: number;
}

export interface MovementsSummary {
  items: MovementsSummaryItem[];
  groupBy: MovementsGroupBy;
  from: OperationalDate;
  to: OperationalDate;
}

/** El reporte de reconciliación: ledger contra proyección. No existía en el legacy. */
export interface KardexVsProjectionItem {
  productCode: string;
  ledgerNet: number;
  projected: number;
  difference: number;
}

export interface KardexVsProjectionReport {
  checkedAt: Instant;
  productsChecked: number;
  divergences: number;
  items: KardexVsProjectionItem[];
}

/* ------------------------------------------------------------------- salud */

export type HealthStatus = "UP" | "DOWN";

export interface HealthResponse {
  status: HealthStatus;
  schemaVersion: string | null;
  version: string;
  database: {
    status: HealthStatus;
    latencyMs: number | null;
    error: string | null;
  };
}

/* ------------------------------------------------------------------ acceso */

export interface AuditLogEntry {
  id: number;
  occurredAt: Instant;
  userId: number | null;
  userName: string | null;
  action: string;
  resource: string;
  resourceId: string | null;
  channel: "WEB" | "MOBILE" | "MOBILE_OFFLINE";
  requestId: string | null;
  allowed: boolean;
  errorCode: string | null;
  detail: Record<string, unknown> | null;
}

export interface AuditLogParams extends PageParams {
  userId?: number;
  action?: string;
  from?: Instant;
  to?: Instant;
  allowed?: boolean;
}
