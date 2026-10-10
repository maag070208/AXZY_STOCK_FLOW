import { api, get } from "@shared/api";
import type {
  AdjustmentResult,
  KardexEntry,
  MoveRequest,
  Page,
  StockItem,
  StockMovementResult,
} from "@shared/api";
import type {
  AdjustmentListParams,
  AdjustmentRequestDto,
  InventoryListParams,
  KardexListParams,
  LocateRequestDto,
} from "../model/types";

/**
 * API de inventario (§4 del contrato).
 *
 * Recordatorio que gobierna todo este archivo: **el kardex es append-only**.
 * No hay `PUT` ni `DELETE` de movimientos; una corrección es un movimiento
 * nuevo. Por eso aquí sólo hay lecturas y operaciones que *agregan* asientos.
 */

/** Existencias por producto y ubicación. `onlyWithStock=true` omite los ceros. */
export const fetchStock = (params: InventoryListParams = {}): Promise<Page<StockItem>> =>
  get<Page<StockItem>>("/inventory/stock", { params: { size: 50, ...params } });

/** Movimientos por producto y rango de fechas. */
export const fetchKardex = (params: KardexListParams = {}): Promise<Page<KardexEntry>> =>
  get<Page<KardexEntry>>("/inventory/kardex", { params: { size: 50, ...params } });

/**
 * **La regla única `AdjustStock`.** Los cuatro orígenes del legacy se unifican
 * aquí: no hay un segundo flujo por origen. `reason` es obligatorio siempre.
 * Un ajuste por encima del umbral devuelve `status = PENDING_APPROVAL`.
 */
export const createAdjustment = (
  body: AdjustmentRequestDto,
  idempotencyKey?: string,
): Promise<AdjustmentResult> =>
  api.post<AdjustmentResult>("/inventory/adjustments", body, { idempotencyKey });

/**
 * ⚠️ **Estas dos funciones apuntan a rutas que la API NO expone.**
 *
 * El backend solo tiene el alta (`POST /v1/inventory/adjustments`): no hay
 * bandeja de pendientes ni ruta de aprobación, y el contrato tampoco las
 * documenta ([api/inventario.md §4](../../../../../docs/api/inventario.md)). Se
 * dejan como superficie prevista, pero **hoy fallarían con `404`**. La pantalla
 * de ajustes ya lo dice en su aviso; si se implementa el flujo, empieza por el
 * backend y por el contrato.
 */

/** Bandeja de ajustes pendientes de aprobación. **No existe en la API.** */
export const fetchPendingAdjustments = (
  params: AdjustmentListParams = {}
): Promise<Page<AdjustmentResult>> =>
  get<Page<AdjustmentResult>>("/inventory/adjustments/pending", {
    params: { size: 50, ...params },
  });

/** Aprueba un ajuste pendiente. **No existe en la API.** */
export const approveAdjustment = (id: number, note?: string): Promise<AdjustmentResult> =>
  api.post<AdjustmentResult>(`/inventory/adjustments/${id}/approve`, { note });

/**
 * Ubicar mercancía: recepción → estante. **El total no cambia**: es un traslado.
 * Una línea en `DISCREPANCY` responde `409 DISCREPANCY_NOT_APPROVED` (regla B1).
 *
 * La idempotencia viaja en la **cabecera `Idempotency-Key`** (nunca en el
 * cuerpo): se pasa como segundo argumento.
 */
export const locateStock = (
  body: LocateRequestDto,
  idempotencyKey?: string,
): Promise<StockMovementResult> =>
  api.post<StockMovementResult>("/inventory/locate", body, { idempotencyKey });

/** Traslado entre estantes de la misma bodega. Idempotencia por cabecera. */
export const moveStock = (
  body: MoveRequest,
  idempotencyKey?: string,
): Promise<StockMovementResult> =>
  api.post<StockMovementResult>("/inventory/move", body, { idempotencyKey });
