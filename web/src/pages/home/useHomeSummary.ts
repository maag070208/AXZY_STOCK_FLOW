import { useEffect, useState } from "react";
import { useSelector } from "react-redux";
import type { RootState } from "@app/store";
import { catalogApi } from "@entities/catalog";
import { can } from "@entities/session";
import { labelsApi } from "@entities/labels";
import { receivingApi, type PendingApproval } from "@entities/receiving";
import { transfersApi, type InTransit } from "@entities/transfers";

import { describeError } from "@shared/api/errorMessage";

/**
 * What the home page shows about the state of the operation. Each figure comes from an endpoint that the user
 * can see: without permission, the figure stays `null` and is not requested. A failure also leaves `null`, but
 * keeps the message to report it, instead of showing a zero that looks like data.
 */
export interface HomeSummary {
  products: number | null;
  pendingApproval: number | null;
  inTransit: number | null;
  failedLabels: number | null;
  pendingList: PendingApproval[];
  transitList: InTransit[];
  error: string | null;
}

const EMPTY: HomeSummary = {
  products: null,
  pendingApproval: null,
  inTransit: null,
  failedLabels: null,
  pendingList: [],
  transitList: [],
  error: null,
};

const firstPage = { page: 0, size: 1, filters: {} };

export function useHomeSummary(): { summary: HomeSummary; loading: boolean } {
  const user = useSelector((state: RootState) => state.auth.user);
  const [summary, setSummary] = useState<HomeSummary>(EMPTY);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;

    const load = async () => {
      const [products, pending, transit, labels] = await Promise.allSettled([
        can(user, "product.view") ? catalogApi.products(firstPage) : Promise.resolve(null),
        can(user, "invoice.view") ? receivingApi.pendingApproval() : Promise.resolve(null),
        can(user, "transit.view") ? transfersApi.inTransit() : Promise.resolve(null),
        can(user, "label.queue_view") ? labelsApi.printJobs(firstPage, { status: "FAILED" }) : Promise.resolve(null),
      ]);

      if (!active) return;

      const failures = [products, pending, transit, labels]
        .filter((result): result is PromiseRejectedResult => result.status === "rejected")
        .map((result) => describeError(result.reason, "home.figureFailed"));

      setSummary({
        products: products.status === "fulfilled" && products.value ? products.value.totalItems : null,
        pendingApproval: pending.status === "fulfilled" && pending.value ? pending.value.items.length : null,
        inTransit: transit.status === "fulfilled" && transit.value ? transit.value.length : null,
        failedLabels: labels.status === "fulfilled" && labels.value ? labels.value.totalItems : null,
        pendingList: pending.status === "fulfilled" && pending.value ? pending.value.items.slice(0, 5) : [],
        transitList: transit.status === "fulfilled" && transit.value ? transit.value.slice(0, 5) : [],
        error: failures[0] ?? null,
      });
      setLoading(false);
    };

    void load();
    return () => {
      active = false;
    };
  }, [user]);

  return { summary, loading };
}
