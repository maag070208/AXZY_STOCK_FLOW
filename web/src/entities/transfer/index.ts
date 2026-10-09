/** Entidad `transfer`: traslados entre bodegas y mercancía en tránsito (§7). */
export {
  cancelTransfer,
  createTransfer,
  dispatchTransfer,
  fetchInTransit,
  fetchTransfer,
  fetchTransfers,
  receiveTransfer,
} from "./api/transferApi";
export type {
  InTransitParams,
  InTransitTransferDto,
  TransferCreatePayload,
  TransferDto,
  TransferQueryParams,
} from "./model/types";
export type { TransferStatus } from "@shared/api";
