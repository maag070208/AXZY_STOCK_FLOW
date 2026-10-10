import { ITInput, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { inventoryApi, type StockLine } from "@entities/inventory";
import { formatQuantity } from "@shared/lib/format";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import { FaBoxOpen } from "react-icons/fa";
import i18n from "@shared/i18n";

/**
 * Stock by location. Two filters the endpoint really accepts: the product code and the
 * the location. There is no free search or sort: marking them here would give a result that looks
 * filtered and is not.
 */
export default function StockPage() {
  const [productCode, setProductCode] = useState("");
  const [locationCode, setLocationCode] = useState("");
  const product = useDebouncedValue(productCode.trim(), 400);
  const location = useDebouncedValue(locationCode.trim(), 400);

  const externalFilters = useMemo(
    () => ({ ...(product ? { productCode: product } : {}), ...(location ? { locationCode: location } : {}) }),
    [product, location],
  );

  const columns: Column<StockLine>[] = [
    { key: "productCode", label: i18n.t("common.code"), type: "string" },
    { key: "productName", label: i18n.t("common.product"), type: "string" },
    { key: "locationDisplayName", label: i18n.t("common.location"), type: "string" },
    { key: "warehouseCode", label: i18n.t("common.warehouse"), type: "string" },
    { key: "quantity", label: i18n.t("nav.stock"), type: "number", render: (row) => formatQuantity(row.quantity) },
  ];

  return (
    <Page icon={<FaBoxOpen size={18} />} title={i18n.t("nav.stock")} description={i18n.t("inventory.stockDescription")} breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.stock") }]}>
      <div className="mb-3 flex flex-wrap gap-3">
        <div className="w-56">
          <ITInput size="sm" name="productCode" label={i18n.t("inventory.productCode")} value={productCode} onChange={(e) => setProductCode(e.target.value)} />
        </div>
        <div className="w-56">
          <ITInput size="sm" name="locationCode" label={i18n.t("common.location")} value={locationCode} onChange={(e) => setLocationCode(e.target.value)} />
        </div>
      </div>
      <DataTable<StockLine>
        columns={columns}
        fetchPage={(query) => inventoryApi.stock(query, {})}
        externalFilters={externalFilters}
      />
    </Page>
  );
}
