/** Entidad `fulfillment`: órdenes, empaque, confirmación y exportación al ERP (§6). */
export {
  confirmOrder,
  createOrder,
  exportOrder,
  fetchOrder,
  fetchOrders,
  packageOrderLine,
  registerOrderShortage,
} from "./api/fulfillmentApi";
export type {
  OrderDetailDto,
  OrderExportResultDto,
  OrderLineDto,
  OrderShortagePayload,
  OrderSummaryDto,
  PackageLinePayload,
} from "./model/types";
export type { OrderExportStatus, OrderStatus } from "@shared/api";
