import { ITAlert, ITBadget, ITButton, ITFlex, ITInput, type Column } from "@axzydev/axzy_ui_system";
import { useCallback, useMemo, useState } from "react";
import { FaEdit, FaFileImport, FaPlus, FaTrashAlt, FaBoxes } from "react-icons/fa";
import { useDispatch, useSelector } from "react-redux";
import { showToast } from "@app/toast/toast.slice";
import type { AppDispatch, RootState } from "@app/store";
import { catalogApi, type Product } from "@entities/catalog";
import { can } from "@entities/session";
import ImportProductsDialog from "@features/catalog/ImportProductsDialog";
import ProductFormDialog from "@features/catalog/ProductFormDialog";
import ReasonDialog from "@features/catalog/ReasonDialog";

import { formatQuantity } from "@shared/lib/format";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

/**
 * Productos: el listado con paginación, filtro y orden **en el servidor**.
 *
 * Solo se ofrecen los filtros que la API admite: la búsqueda libre (`q`) y el
 * estado (`active`). Marcar una columna como filtrable cuando el endpoint no la
 * filtra daría un resultado que parece filtrado y no lo está.
 */
export default function ProductsPage() {
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);

  const [search, setSearch] = useState("");
  const q = useDebouncedValue(search, 400);
  const [reload, setReload] = useState(0);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Product | null>(null);
  const [toDeactivate, setToDeactivate] = useState<Product | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const externalFilters = useMemo(() => (q.trim() === "" ? undefined : { q: q.trim() }), [q]);
  const refresh = useCallback(() => setReload((value) => value + 1), []);

  const deactivate = async (reason: string) => {
    if (!toDeactivate) return;
    setBusy(true);
    setActionError(null);
    try {
      await catalogApi.deactivateProduct(toDeactivate.id, reason);
      setToDeactivate(null);
      refresh();
      dispatch(showToast(`Producto ${toDeactivate.productCode} desactivado`));
    } catch (caught) {
      // `PRODUCT_HAS_STOCK` (409) es el caso interesante: se dice cuánto stock hay
      // y qué hacer, en lugar de un «no se pudo».
      setActionError(describeError(caught, "errors.unexpected"));
    } finally {
      setBusy(false);
    }
  };

  const columns: Column<Product>[] = [
    { key: "productCode", label: i18n.t("common.code"), type: "string", sortable: true },
    { key: "name", label: i18n.t("common.name"), type: "string", sortable: true },
    { key: "description", label: i18n.t("common.description"), type: "string" },
    { key: "unitMeasure", label: i18n.t("common.unit"), type: "string" },
    { key: "externalId", label: i18n.t("catalog.erpId"), type: "number" },
    {
      key: "stock",
      label: i18n.t("nav.stock"),
      type: "number",
      render: (row) => formatQuantity(row.stock),
    },
    {
      key: "active",
      label: i18n.t("common.status"),
      type: "boolean",
      render: (row) => (
        <ITBadget label={row.active ? i18n.t("common.activeMasc") : i18n.t("common.inactiveMasc")} color={row.active ? "success" : "secondary"} size="sm" />
      ),
    },
    {
      key: "actions",
      label: "",
      type: "actions",
      actions: (row) => (
        <ITFlex gap={2}>
          {can(user, "product.edit") && (
            <ITButton
              icon={<FaEdit size={12} />}
              variant="outline"
              size="sm"
              ariaLabel={i18n.t("common.edit")}
              title={i18n.t("common.edit")}
              onClick={() => {
                setEditing(row);
                setFormOpen(true);
              }}
            />
          )}
          {can(user, "product.delete") && row.active && (
            <ITButton
              icon={<FaTrashAlt size={12} />}
              variant="outline"
              color="danger"
              size="sm"
              ariaLabel={i18n.t("common.deactivate")}
              title={i18n.t("common.deactivate")}
              onClick={() => {
                setActionError(null);
                setToDeactivate(row);
              }}
            />
          )}
        </ITFlex>
      ),
    },
  ];

  return (
    <Page icon={<FaBoxes size={18} />}
      title={i18n.t("nav.products")}
      description={i18n.t("catalog.productsDescription")}
      breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.products") }]}
      actions={
        <ITFlex gap={2} align="center">
          {can(user, "product.import") && (
            <ITButton
              label={i18n.t("catalog.import")}
              className="whitespace-nowrap"
              icon={<FaFileImport size={12} />}
              variant="outline"
              size="sm"
              onClick={() => setImportOpen(true)}
            />
          )}
          {can(user, "product.create") && (
            <ITButton
              label={i18n.t("catalog.newProduct")}
              className="whitespace-nowrap"
              icon={<FaPlus size={12} />}
              color="primary"
              size="sm"
              onClick={() => {
                setEditing(null);
                setFormOpen(true);
              }}
            />
          )}
        </ITFlex>
      }
    >
      <ITFlex gap={2} align="center" wrap="wrap" className="mb-4">
        <div className="w-72">
          <ITInput
            name="search"
            placeholder={i18n.t("catalog.searchProductPlaceholder")}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            size="sm"
          />
        </div>
      </ITFlex>
      <ITFlex direction="column" gap={3}>
        {actionError && !toDeactivate && (
          <ITAlert variant="error" title={i18n.t("common.actionFailed")} dismissible onDismiss={() => setActionError(null)}>
            {actionError}
          </ITAlert>
        )}

        <DataTable<Product>
          columns={columns}
          fetchPage={(query) => catalogApi.products(query)}
          externalFilters={externalFilters}
          reloadTrigger={reload}
        />
      </ITFlex>

      <ProductFormDialog
        open={formOpen}
        product={editing}
        onClose={() => setFormOpen(false)}
        onSaved={() => {
          setFormOpen(false);
          refresh();
          dispatch(showToast(editing ? i18n.t("catalog.productUpdated") : i18n.t("catalog.productCreated")));
        }}
      />

      <ReasonDialog
        open={toDeactivate !== null}
        title={i18n.t("catalog.deactivateProduct")}
        description={
          toDeactivate
            ? i18n.t("catalog.deactivateProductHint", { name: toDeactivate.name, code: toDeactivate.productCode })
            : ""
        }
        busy={busy}
        error={actionError}
        onCancel={() => {
          setToDeactivate(null);
          setActionError(null);
        }}
        onConfirm={(reason) => void deactivate(reason)}
      />

      <ImportProductsDialog open={importOpen} onClose={() => setImportOpen(false)} onImported={refresh} />
    </Page>
  );
}
