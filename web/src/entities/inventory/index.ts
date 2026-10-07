/** Entidad `inventory`: existencias, kardex y ajustes (§4 del contrato). */
export {
  approveAdjustment,
  createAdjustment,
  fetchKardex,
  fetchPendingAdjustments,
  fetchStock,
  locateStock,
  moveStock,
} from "./api/inventoryApi";
export type {
  AdjustmentListParams,
  AdjustmentRequestDto,
  InventoryListParams,
  KardexListParams,
  LocateRequestDto,
  StockItemDto,
} from "./model/types";
export type {
  AdjustmentDirection,
  AdjustmentOrigin,
  AdjustmentReason,
  AdjustmentResult,
  AdjustmentStatus,
  KardexEntry,
  MoveType,
  StockItem,
} from "@shared/api";
