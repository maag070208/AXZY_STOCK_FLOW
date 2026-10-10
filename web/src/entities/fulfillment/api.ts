import { get } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { OrderHeader } from "./model";

export const fulfillmentApi = {
  orders: (query: PageQuery, filters: { status?: string }): Promise<Page<OrderHeader>> =>
    get<Page<OrderHeader>>("/orders", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),
};
