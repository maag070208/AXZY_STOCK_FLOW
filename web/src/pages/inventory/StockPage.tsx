import { ITInput, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { inventoryApi, type StockLine } from "@entities/inventory";
import { formatQuantity } from "@shared/lib/format";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

/**
 * Existencias por ubicación. Dos filtros que el endpoint acepta de verdad: el código de producto y
 * la ubicación. No hay búsqueda libre ni orden: marcarlos aquí daría un resultado que parece
 * filtrado y no lo está.
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
    { key: "productCode", label: "Código", type: "string" },
    { key: "productName", label: "Producto", type: "string" },
    { key: "locationDisplayName", label: "Ubicación", type: "string" },
    { key: "warehouseCode", label: "Bodega", type: "string" },
    { key: "quantity", label: "Existencias", type: "number", render: (row) => formatQuantity(row.quantity) },
  ];

  return (
    <Page title="Existencias" description="Cuánto hay de cada producto y en qué ubicación." breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Existencias" }]}>
      <div className="mb-3 flex flex-wrap gap-3">
        <div className="w-56">
          <ITInput size="sm" name="productCode" label="Código de producto" value={productCode} onChange={(e) => setProductCode(e.target.value)} />
        </div>
        <div className="w-56">
          <ITInput size="sm" name="locationCode" label="Ubicación" value={locationCode} onChange={(e) => setLocationCode(e.target.value)} />
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
