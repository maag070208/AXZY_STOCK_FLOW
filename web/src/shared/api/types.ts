/**
 * DTOs del contrato HTTP (docs/historico/CONTRATO_API.md).
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
  productName: string;
  locationId: number;
  locationDisplayName: string;
  warehouseId: number;
  warehouseCode: string;
  /**
   * **Cadena** con 3 decimales (`"5193.000"`), no `number`: la API manda las
   * cantidades de existencias como texto (contrato §1.7). Tiparlo como `number`
   * compila y rompe al formatear.
   */
  quantity: string;
}

/** Filtros de `GET /v1/inventory/stock`: códigos, no identificadores. */
export interface StockParams extends PageParams {
  productCode?: string;
  locationCode?: string;
  /** Por defecto `true`: solo lo que tiene saldo. */
  onlyWithStock?: boolean;
}

/**
 * Tipos de movimiento. **Copiados de `MovementType`**
 * (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/StockMovement.kt`),
 * no inventados: el servidor castea el valor a `movement_type`, así que uno
 * desconocido no da `400`, da `500`.
 */
export type MoveType =
  | "INITIAL"
  | "RECEIPT_IN"
  | "ADJUSTMENT_IN"
  | "FULFILLMENT_OUT"
  | "ADJUSTMENT_OUT"
  | "LOCATE"
  | "MOVE_WITHIN_WAREHOUSE"
  | "TRANSFER_OUT"
  | "TRANSFER_IN"
  | "PENDING_OUT";

/**
 * Asiento del kardex. El ledger es append-only: no hay `PUT` ni `DELETE`.
 *
 * Los campos que el servidor **omite** cuando son nulos van opcionales: un
 * movimiento `INITIAL` no trae `fromLocationDisplayName` ni `adjustmentReason`.
 */
export interface KardexEntry {
  id: number;
  movedAt: Instant;
  productCode: string;
  productName: string;
  moveType: MoveType;
  /** Cadena con 3 decimales, como el resto de cantidades. */
  units: string;
  quantityAfter: string;
  fromLocationDisplayName?: string | null;
  toLocationDisplayName?: string | null;
  container?: string | null;
  orderNumber?: string | null;
  adjustmentReason?: AdjustmentReason | null;
  adjustmentOrigin?: AdjustmentOrigin | null;
  userId?: number | null;
  userName?: string | null;
  note?: string | null;
}

export interface KardexParams extends PageParams {
  productCode?: string;
  moveType?: MoveType;
  from?: OperationalDate;
  to?: OperationalDate;
}

/** `INCREASE` suma y `DECREASE` resta. `IN`/`OUT` los rechaza la API. */
export type AdjustmentDirection = "INCREASE" | "DECREASE";

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

/** Cuerpo real de `POST /v1/inventory/adjustments`: códigos, no identificadores. */
export interface AdjustmentRequest {
  productCode: string;
  locationCode: string;
  /** Número decimal con 3 decimales. La dirección dice si suma o resta. */
  quantity: number;
  direction: AdjustmentDirection;
  reason: AdjustmentReason;
  origin: AdjustmentOrigin;
  note?: string | null;
  /** Quién aprueba, si el ajuste lo requiere. */
  approverId?: number | null;
}

/**
 * Resultado de un ajuste, de ubicar o de mover: **la misma forma**.
 *
 * `MovementResponse` (`application/inventory/InventoryDtos.kt`): los identificadores de
 * los asientos escritos, el estado y el saldo resultante como cadena. Un ajuste que
 * exige aprobación responde `202` con `status = "PENDING_APPROVAL"`.
 */
export interface AdjustmentResult {
  kardexIds: number[];
  status: AdjustmentStatus;
  /** Saldo resultante, cadena con 3 decimales. */
  total: string;
  message?: string | null;
}

/** Cuerpo real de `POST /v1/inventory/locate` y `/move`. */
export interface LocateRequest {
  productCode: string;
  fromLocationCode: string;
  toLocationCode: string;
  quantity: number;
}

export type MoveRequest = LocateRequest;

/** Ver `AdjustmentResult`: ubicar y mover devuelven la misma forma. */
export type StockMovementResult = AdjustmentResult;

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
  /** Cantidad según el documento. **Cadena** de 3 decimales. */
  quantity: string;
  boxQuantity: string;
  unitsPerBox: string | null;
  /** Cantidad contada físicamente; `null` mientras no se capture. */
  physicalQuantity: string | null;
  physicalBoxQuantity: string | null;
  locatedQuantity: string;
  /** `physicalQuantity - quantity`, calculada por el servidor (columna generada). */
  difference: string | null;
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
  /** `null` mientras nadie le puso fecha de empaque. */
  packagingDate: OperationalDate | null;
  status: OrderStatus;
  customerCode: string | null;
  concept: string | null;
  /** `true` cuando el ERP ya la recibió. */
  converted: boolean;
  convertedAt: Instant | null;
  exportStatus: string | null;
  exportAttempts: number;
  exportLastError: string | null;
  totalLines: number;
  /** Líneas COMPLETAS. */
  packagedLines: number;
}

/**
 * Un estado de LÍNEA de surtido, y **no es el de factura**.
 *
 * **Este campo estaba declarado como `InvoiceLineStatus`**, que es la unión de la
 * RECEPCIÓN: estados como `DISCREPANCY` o `APPROVED`, que en una orden de surtido
 * no existen. Tipar contra la unión ajena hacía que comparar un estado real diera
 * un error de compilación por un valor que sí era válido, y —peor— que el código
 * tratara `SUPPLIED` como un estado de factura desconocido.
 *
 * La unión correcta la declara el dominio (`OrderLineStatus`).
 */
export type OrderLineStatus =
  | "PENDING"
  | "ASSIGNED"
  | "SUPPLIED"
  | "VERIFIED"
  | "CANCELLED";

export interface OrderLine {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  unitMeasure: string;
  /** Cadena decimal de 3 decimales (§1.7). */
  quantity: string;
  /**
   * **Lo YA descontado del inventario.** No es «lo empacado»: es el acumulado que
   * el inventario ya refleja. Confirmar la orden descuenta
   * `quantity - suppliedQuantity`, nunca `quantity` — hacerlo dos veces dejaría
   * el stock corto y el descuadre se confundiría con una merma.
   */
  suppliedQuantity: string;
  supplied: boolean;
  status: OrderLineStatus;
  assignedUserId: number | null;
  assignedUserName: string | null;
  locationId: number | null;
  locationDisplayName: string | null;
}

/** El detalle: la cabecera CON sus líneas. Solo este endpoint las trae. */
export interface OrderDetail extends OrderSummary {
  lines: OrderLine[];
}

export interface OrderListParams extends PageParams {
  status?: OrderStatus;
}

/**
 * El alta de una orden.
 *
 * **Las líneas van por `productCode` y la cantidad es CADENA decimal**, que es lo
 * que acepta la ruta. Mandar ids y números —como se declaraba antes— habría dado
 * `422` por producto inexistente y por cantidad ilegible.
 *
 * `orderNumber` es opcional: si no se manda, el servidor lo genera como
 * `ORD-AAAA-NNNN`. Que lo genere el servidor evita que dos clientes inventen el
 * mismo número.
 */
export interface OrderCreatePayload {
  orderNumber?: string | null;
  packagingDate?: string | null;
  customerCode?: string | null;
  concept?: string | null;
  lines: Array<{ productCode: string; quantity: string }>;
}

export interface PackageLinePayload {
  /** Cuánto se empaca AHORA. Se valida contra lo PENDIENTE, no contra `quantity`. */
  quantity: string;
  /** De qué ubicación sale. Sin él, la de la línea. */
  locationId?: number | null;
  idempotencyKey?: string;
}

/** Faltante de surtido: delega en la regla única de ajuste, no en un flujo nuevo. */
export interface OrderShortagePayload {
  lineId: number;
  quantity: string;
  reason?: AdjustmentReason;
  note?: string | null;
}

/**
 * El estado de la exportación al ERP.
 *
 * `CONFIRMED` es el estado final: el ERP la recibió. Los demás son intentos, y
 * solo `FAILED` exige que alguien pulse «reintentar» — que es manual a propósito.
 */
export type OrderExportStatus = "PENDING" | "SENT" | "FAILED" | "CONFIRMED";

/**
 * Lo que devuelve un intento de exportación al ERP.
 *
 * **No hay `nextRetryAt`.** El campo estaba declarado y la API no lo manda: quien
 * lo leyera obtendría `undefined` y una cuenta atrás que nunca avanza. El
 * reintento es **manual**, que es lo que el contrato dice.
 */
export interface OrderExportResult {
  /** Intentos acumulados, incluido este. */
  attempts: number;
  status: OrderExportStatus;
  lastError: string | null;
}

/* --------------------------------------------------------------- traslados */

/** `DRAFT → DISPATCHED → IN_TRANSIT → RECEIVED`, con `CANCELLED` en cualquier punto. */
export type TransferStatus = "DRAFT" | "DISPATCHED" | "IN_TRANSIT" | "RECEIVED" | "CANCELLED";

/**
 * Un traslado entre bodegas (§7).
 *
 * **LOS TIPOS DE ESTA SECCIÓN ESTABAN MAL, y por el mismo motivo que el del
 * informe de reconciliación:** declaraban lo que el contrato *prometía* y la ruta
 * devolvía otra cosa. `totalUnits` estaba como `number` cuando la API manda
 * **cadena decimal de 3 decimales** (`"150.000"`, §1.7), y `lineCount` tampoco
 * era el campo del contrato.
 *
 * Un cliente tipado contra esto leería `undefined` en los totales y pintaría
 * `NaN` al sumarlos. Se corrigió contra el DTO REAL del backend
 * (`TransferResponse`), no contra el ejemplo del contrato.
 */
export interface Transfer {
  id: number;
  code: string;
  fromWarehouseId: number;
  fromWarehouseCode: string;
  toWarehouseId: number;
  toWarehouseCode: string;
  status: TransferStatus;
  lineCount: number;
  /** **Cadena decimal de 3 decimales** (`"150.000"`), no un número (§1.7). */
  totalUnits: string;
  note: string | null;
  dispatchedBy: number | null;
  dispatchedByName: string | null;
  dispatchedAt: Instant | null;
  receivedBy: number | null;
  receivedByName: string | null;
  receivedAt: Instant | null;
}

/**
 * Un traslado sin recibir, con su responsable y los días transcurridos.
 *
 * **No extiende `Transfer`**: la respuesta de `/transfers/in-transit` no tiene los
 * mismos campos —no lleva los ids de bodega ni la nota— y declararlo como una
 * extensión obligaría al servidor a mandar campos de relleno o al cliente a
 * mentir. `daysInTransit` lo calcula el SERVIDOR: contarlo con el reloj del
 * equipo daría un número distinto en cada máquina.
 */
export interface InTransitTransfer {
  id: number;
  code: string;
  fromWarehouseCode: string;
  toWarehouseCode: string;
  status: "DISPATCHED" | "IN_TRANSIT";
  lineCount: number;
  /** Cadena decimal de 3 decimales (§1.7). */
  totalUnits: string;
  dispatchedAt: Instant;
  daysInTransit: number;
  dispatchedBy: number | null;
  dispatchedByName: string | null;
}

export interface TransferLine {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  /** Cadena decimal de 3 decimales (§1.7). */
  quantity: string;
  fromLocationId: number | null;
  fromLocationDisplayName: string | null;
  note: string | null;
}

/** El detalle de un traslado: la cabecera CON sus líneas. */
export interface TransferDetail {
  transfer: Transfer;
  lines: TransferLine[];
}

export interface TransferListParams extends PageParams {
  status?: TransferStatus;
  warehouseId?: number;
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
  /** **Nulo si la plantilla no lo declara**: la API lo admite y cae a `4x6`. */
  size: string | null;
  isActive: boolean;
  fieldSchema: LabelFieldSchema;
  /** **Solo llega en el DETALLE.** El listado lo omite porque es grande. */
  zpl?: string;
}

/**
 * El alta de una plantilla.
 *
 * `zpl` es obligatorio y **tiene que llevar `^XA` y `^XZ`**: la API lo valida al
 * guardar y responde `422 INVALID_ZPL`. Se valida ahí y no al imprimir porque una
 * plantilla sin cierre no da error en la impresora — deja la etiqueta en blanco o
 * el trabajo en la cola, y eso se descubre gastando papel.
 */
export interface LabelTemplateCreateRequest {
  code: string;
  name: string;
  description?: string | null;
  zpl: string;
  size?: string | null;
  fieldSchema?: LabelFieldSchema;
  isActive?: boolean;
}

/** La actualización: lo que no se envía queda como estaba. */
export interface LabelTemplateUpdateRequest {
  name?: string;
  description?: string | null;
  zpl?: string;
  size?: string | null;
  fieldSchema?: LabelFieldSchema;
  isActive?: boolean;
}

export interface LabelPreviewRequest {
  templateId: number;
  data: Record<string, string>;
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
  /** Por defecto 1. La API admite de 1 a 100. */
  copies?: number;
  /** **Un juego de datos por etiqueta.** Tiene que traer al menos uno. */
  data: Array<Record<string, string>>;
}

/** `202 Accepted`: el trabajo queda `PENDING` hasta que la impresora confirma. */
export interface LabelPrintResponse {
  jobIds: number[];
  status: string;
}

export type LabelJobStatus = "PENDING" | "SENT" | "PRINTED" | "FAILED";

/**
 * Un trabajo de impresión.
 *
 * **`status` es `string` y no la unión cerrada.** Se declara como unión porque es
 * lo que la interfaz compara, pero el tipo del contrato tiene cuatro valores y el
 * backend podría añadir uno; tiparlo como `string` en la respuesta evita que un
 * valor nuevo rompa el cliente. Donde importa —el filtro— la interfaz usa
 * `LabelJobStatus`.
 */
export interface LabelJob {
  id: number;
  templateCode: string;
  copies: number;
  status: string;
  attempts: number;
  lastError: string | null;
  printedAt: Instant | null;
  createdAt: Instant;
}

export interface LabelJobPage {
  items: LabelJob[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** Lo que la impresora reporta por `PATCH /v1/print-jobs/{id}`. */
export interface PrintJobUpdateRequest {
  status: "PRINTED" | "FAILED";
  error?: string | null;
}

export interface PrintJobUpdateResponse {
  jobId: number;
  status: string;
  error: string | null;
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
  /** Cadena de 3 decimales, como el resto de cantidades. */
  quantity: string;
  warehouseCode: string;
}

export interface InventoryReport extends Page<InventoryReportItem> {
  totals: {
    /** Productos DISTINTOS con existencia, no filas. */
    distinctProducts: number;
    totalUnits: string;
  };
}

export type ReportFormat = "xlsx" | "csv";
export type MovementsGroupBy = "day" | "week" | "month" | "product" | "location";

/**
 * Una fila del resumen de movimientos, agrupada por `groupBy`.
 *
 * **El servidor NO devuelve `period`/`moveType`/`units`**: devuelve `key` y `keyLabel`
 * (la clave por la que se agrupó y su etiqueta legible) y los tres totales como cadena.
 */
export interface MovementsSummaryItem {
  key: string;
  keyLabel: string;
  inUnits: string;
  outUnits: string;
  netUnits: string;
  movements: number;
}

export interface MovementsSummaryTotals {
  inUnits: string;
  outUnits: string;
  netUnits: string;
  movements: number;
}

export interface MovementsSummary {
  from: OperationalDate;
  to: OperationalDate;
  groupBy: MovementsGroupBy;
  totals: MovementsSummaryTotals;
  items: MovementsSummaryItem[];
}

/**
 * El reporte de reconciliación: ledger contra proyección. No existía en el legacy.
 *
 * **ESTA INTERFAZ ESTUVO MAL, y es el motivo por el que se corrigió el contrato.**
 * Declaraba la forma que `docs/historico/CONTRATO_API.md` §9 *prometía*
 * (`productsChecked`/`ledgerNet`/`projected`) y **la ruta nunca devolvió eso**:
 * respondía `cuadra`/`totalEnBd`/`totalEsperado`/`diferencia`, con los campos en
 * español. Un cliente tipado contra esta interfaz leía `undefined` en cada campo.
 *
 * Hoy coinciden los tres lados —contrato, ruta y este tipo— y los identificadores
 * van en inglés (`docs/historico/ARQUITECTURA.md` §5.1). **Los totales son `string` y no `number`**:
 * el servidor manda `"5193.000"` (§1.7) y leerlos como número perdería la precisión
 * que precisamente este reporte existe para comprobar.
 */
export interface KardexVsProjectionItem {
  productId: number;
  productCode: string;
  /** Lo que dice la proyección `product_stock`, como cadena decimal. */
  projectedTotal: string;
  /** Lo que dice el ledger, que es la fuente de verdad, como cadena decimal. */
  ledgerTotal: string;
  /** `projectedTotal - ledgerTotal`; el signo importa. */
  difference: string;
}

export interface KardexVsProjectionReport {
  checkedAt: Instant;
  /** Veredicto explícito del servidor: `true` cuando no hay ninguna divergencia. */
  balanced: boolean;
  /** Cuántos productos están descuadrados. `items` trae solo ésos. */
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

/**
 * Una entrada de la bitácora (`GET /v1/audit/log`).
 *
 * **El tipo estaba muy incompleto**: declaraba ocho campos cuando el DTO real
 * tiene veinticinco. Faltaban justo los que hacen útil la pantalla —`actionLevel`,
 * `businessDate`, `decision`, `result`, `errorCode`— y la dimensión que el legacy
 * no tenía: **el canal y el `requestId`**, que es lo que permite cruzar una
 * pantalla con su movimiento.
 *
 * Los nulos se omiten del JSON (`explicitNulls = false`), así que un registro del
 * sistema —sin usuario— no trae `userId`: de ahí que casi todo sea opcional.
 */
export interface AuditLogEntry {
  id: number;
  /** Orden total del ledger entre particiones. */
  sequenceNo: number;
  occurredAt: Instant;
  /** Fecha de NEGOCIO. Un cierre capturado de madrugada es del día anterior. */
  businessDate: OperationalDate;

  // ── Quién. Es un SNAPSHOT: el histórico se lee igual aunque el usuario se
  //    renombre o se desactive después.
  userId?: number;
  username?: string;
  userName?: string;
  roles?: string[];

  // ── Qué
  action: string;
  /** `INFO`, `WARN`, `CRITICAL`… para poder filtrar lo que importa. */
  actionLevel: string;

  // ── Sobre qué
  entity: string;
  entityId?: string;
  entityLabel?: string;
  changedFields?: string[];

  // ── Desde dónde: la dimensión que el legacy no tenía
  channel: string;
  device?: string;
  ip?: string;
  userAgent?: string;

  // ── Con qué permiso y con qué resultado
  permission?: string;
  decision?: string;
  result: string;
  errorCode?: string;
  errorMessage?: string;
  reason?: string;

  // ── El flujo
  correlationId?: string;
  causationId?: string;
  requestId?: string;

  // ── Integridad: la cadena de hashes, expuesta para poder verificarla
  prevHash?: string;
  recordHash: string;
}

/**
 * Los filtros de la bitácora.
 *
 * **Los que la ruta acepta, no los que la pantalla imaginaba.** Aquí se declaraba
 * `from`/`to`/`allowed` y el backend espera `from`/`to`, `userId`, `entity`,
 * `entityId`, `action`, `correlationId`, `decision`, `result` y `actionLevel`.
 * Un filtro que la ruta ignora **no filtra y no falla**: devuelve todo, y el
 * usuario cree que no hay más registros de los que ve.
 */
export interface AuditLogParams extends PageParams {
  /** ISO-8601. */
  from?: string;
  to?: string;
  userId?: number;
  entity?: string;
  entityId?: string;
  action?: string;
  correlationId?: string;
  decision?: string;
  result?: string;
  actionLevel?: string;
}

/* --------------------------------------------------------------- identidad */

/**
 * Un usuario del sistema (`GET /v1/users`).
 *
 * **`roles` es una lista de CLAVES, no de nombres**: es lo que devuelve la API y
 * lo que la interfaz necesita para comparar. El nombre visible de cada rol se
 * resuelve contra el catálogo de `GET /v1/roles`.
 */
export interface UserAdmin {
  id: number;
  username: string;
  name: string;
  roles: string[];
  active: boolean;
  /** ISO-8601 en UTC, o `null` si nunca ha entrado. */
  lastLoginAt: string | null;
}

/**
 * Un rol y **cuántos permisos y usuarios tiene**.
 *
 * Los dos contadores los calcula el servidor, y son la razón de que esta pantalla
 * sirva: un rol con cero usuarios es un rol que se puede retirar, y uno con
 * muchísimos permisos es el que hay que revisar. Contarlos en el cliente exigiría
 * traerse la matriz entera.
 */
export interface RoleAdmin {
  id: number;
  /** La clave estable (`admin`, `warehouse`). Es lo que va en `roles`. */
  key: string;
  name: string;
  description: string | null;
  /** Los roles de sistema no se pueden borrar. */
  isSystem: boolean;
  active: boolean;
  sortOrder: number;
  permissionCount: number;
  userCount: number;
}

/** Un permiso del catálogo, con **su alcance** (`OWN` | `AREA` | `ALL`). */
export interface PermissionAdmin {
  key: string;
  module: string;
  resource: string;
  action: string;
  name: string;
  description: string | null;
  /** Los alcances que este permiso admite. Una lista vacía significa «ninguno». */
  scopes: string[];
  /** Un permiso sensible es el que no se concede por defecto a nadie. */
  sensitive: boolean;
  active: boolean;
  sortOrder: number;
}

export interface PermissionModuleAdmin {
  module: string;
  moduleName: string;
  permissions: PermissionAdmin[];
}

/**
 * El catálogo de permisos, **entero y sin paginar**.
 *
 * Viaja agrupado por módulo (`modules`) y también plano (`items`): es el mismo
 * dato, y un selector de permisos no tiene por qué recorrer los módulos. El
 * contrato lo exceptúa de la paginación a propósito (§1.4): es la matriz de la
 * pantalla de roles, y paginarla obligaría a pedir todas las páginas antes de
 * poder dibujar nada.
 */
export interface PermissionCatalog {
  totalItems: number;
  modules: PermissionModuleAdmin[];
  items: PermissionAdmin[];
}

/** Filtros del listado de usuarios. */
export interface UserListParams extends PageParams {
  active?: boolean;
}

/** Filtros del listado de roles. */
export interface RoleListParams extends PageParams {
  active?: boolean;
}
