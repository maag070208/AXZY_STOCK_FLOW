import { ITBadget, ITButton, ITText, type Column } from "@axzydev/axzy_ui_system";
import { useCallback, useState } from "react";
import { FaEdit, FaPlus, FaWarehouse } from "react-icons/fa";
import { useDispatch, useSelector } from "react-redux";
import { showToast } from "@app/toast/toast.slice";
import type { AppDispatch, RootState } from "@app/store";
import { catalogApi, type Warehouse } from "@entities/catalog";
import { can } from "@entities/session";
import WarehouseFormDialog from "@features/catalog/WarehouseFormDialog";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import i18n from "@shared/i18n";

/**
 * Bodegas.
 *
 * This list **is not paginated**, and it is not an oversight: it is a closed and
 * tiny catalog that is requested whole (docs/api/convenciones.md §1.4). So that the
 * table wrapper —which expects a page— does not have to invent one, the
 * full response is wrapped as a single page of size `total`. The
 * alternative was a different table for this screen, and then the interface
 * would have two ways of drawing the same thing.
 */
export default function WarehousesPage() {
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);

  const [reload, setReload] = useState(0);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Warehouse | null>(null);

  const refresh = useCallback(() => setReload((value) => value + 1), []);

  const columns: Column<Warehouse>[] = [
    { key: "code", label: i18n.t("common.code"), type: "string" },
    { key: "name", label: i18n.t("common.name"), type: "string" },
    { key: "address", label: i18n.t("common.address"), type: "string" },
    {
      key: "isDefault",
      label: i18n.t("common.default"),
      type: "boolean",
      render: (row) =>
        row.isDefault ? <ITBadget label={i18n.t("common.yes")} color="primary" size="sm" /> : <ITText muted>—</ITText>,
    },
    {
      key: "active",
      label: i18n.t("common.status"),
      type: "boolean",
      render: (row) => (
        <ITBadget label={row.active ? i18n.t("common.active") : i18n.t("common.inactive")} color={row.active ? "success" : "secondary"} size="sm" />
      ),
    },
    {
      key: "actions",
      label: "",
      type: "actions",
      actions: (row) =>
        can(user, "warehouse.edit") ? (
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
        ) : null,
    },
  ];

  return (
    <Page icon={<FaWarehouse size={18} />}
      title={i18n.t("nav.warehouses")}
      description={i18n.t("catalog.warehousesDescription")}
      breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.warehouses") }]}
      actions={
        can(user, "warehouse.create") ? (
          <ITButton
            label={i18n.t("catalog.newWarehouse")}
            icon={<FaPlus size={12} />}
            color="primary"
            size="sm"
            onClick={() => {
              setEditing(null);
              setFormOpen(true);
            }}
          />
        ) : null
      }
    >
      <DataTable<Warehouse>
        columns={columns}
        fetchPage={async (query) => {
          const response = await catalogApi.warehouses({
            q: query.q,
            active: typeof query.filters.active === "boolean" ? query.filters.active : undefined,
          });
          return {
            items: response.items,
            page: 0,
            size: response.items.length,
            totalItems: response.items.length,
            totalPages: 1,
          };
        }}
        reloadTrigger={reload}
      />

      <WarehouseFormDialog
        open={formOpen}
        warehouse={editing}
        onClose={() => setFormOpen(false)}
        onSaved={() => {
          setFormOpen(false);
          refresh();
          dispatch(showToast(editing ? i18n.t("catalog.warehouseUpdated") : i18n.t("catalog.warehouseCreated")));
        }}
      />
    </Page>
  );
}
