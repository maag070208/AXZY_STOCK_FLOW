import { ITAlert, ITBadget, ITButton, ITFlex, ITInput, ITSelect, type Column } from "@axzydev/axzy_ui_system";
import { useCallback, useMemo, useState } from "react";
import { FaEdit, FaPlus, FaTrashAlt, FaLayerGroup } from "react-icons/fa";
import { useDispatch, useSelector } from "react-redux";
import { showToast } from "@app/toast/toast.slice";
import type { AppDispatch, RootState } from "@app/store";
import { catalogApi, useWarehouses, type Location } from "@entities/catalog";
import { can } from "@entities/session";
import LocationFormDialog from "@features/catalog/LocationFormDialog";
import ReasonDialog from "@features/catalog/ReasonDialog";

import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

const KIND_LABEL: Record<Location["kind"], string> = {
  SHELF: i18n.t("common.shelf"),
  RECEIVING: i18n.t("nav.receiving"),
  UNASSIGNED: i18n.t("catalog.unassigned"),
};

/**
 * Locations: the list, with the filters the API supports (`q`, `warehouseId`,
 * `kind`, `active`).
 *
 * The visible name column shows `displayName`, which **the server composes**:
 * the web does not concatenate `hall + code + position` (docs/arquitectura/web-fsd.md §2.3).
 */
export default function LocationsPage() {
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);
  const { items: warehouses } = useWarehouses();

  const [search, setSearch] = useState("");
  const q = useDebouncedValue(search, 400);
  const [warehouseId, setWarehouseId] = useState<string>("");
  const [kind, setKind] = useState<string>("");
  const [reload, setReload] = useState(0);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Location | null>(null);
  const [toDeactivate, setToDeactivate] = useState<Location | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const externalFilters = useMemo(() => {
    const filters: Record<string, string | number> = {};
    if (q.trim() !== "") filters.q = q.trim();
    if (warehouseId !== "") filters.warehouseId = Number(warehouseId);
    if (kind !== "") filters.kind = kind;
    return Object.keys(filters).length > 0 ? filters : undefined;
  }, [q, warehouseId, kind]);

  const refresh = useCallback(() => setReload((value) => value + 1), []);

  const deactivate = async (reason: string) => {
    if (!toDeactivate) return;
    setBusy(true);
    setActionError(null);
    try {
      await catalogApi.deactivateLocation(toDeactivate.id, reason);
      setToDeactivate(null);
      refresh();
      dispatch(showToast(i18n.t("catalog.locationDeactivated", { name: toDeactivate.displayName })));
    } catch (caught) {
      // `LOCATION_HAS_STOCK` and `RECEIVING_LOCATION_PROTECTED` explain what happened and
      // what to do; the message from the API is the one that is shown.
      setActionError(describeError(caught, "errors.unexpected"));
    } finally {
      setBusy(false);
    }
  };

  const columns: Column<Location>[] = [
    { key: "displayName", label: i18n.t("common.location"), type: "string" },
    { key: "warehouseCode", label: i18n.t("common.warehouse"), type: "string" },
    { key: "kind", label: i18n.t("common.type"), type: "string", render: (row) => KIND_LABEL[row.kind] },
    { key: "levels", label: i18n.t("common.levels"), type: "number" },
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
      actions: (row) => (
        <ITFlex gap={2}>
          {can(user, "location.edit") && (
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
          {can(user, "location.delete") && row.active && (
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
    <Page icon={<FaLayerGroup size={18} />}
      title={i18n.t("nav.locations")}
      description={i18n.t("catalog.locationsDescription")}
      breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.locations") }]}
      actions={
        <ITFlex gap={2} align="center" wrap="wrap">
          {can(user, "location.create") && (
            <ITButton
              label={i18n.t("catalog.newLocation")}
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
        <div className="w-64">
          <ITInput
            name="search"
            placeholder={i18n.t("catalog.searchLocationPlaceholder")}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            size="sm"
          />
        </div>
        <div className="w-64">
          <ITSelect
            name="warehouseId"
            options={[
              { value: "", label: i18n.t("common.allWarehouses") },
              ...warehouses.map((warehouse) => ({ value: String(warehouse.id), label: warehouse.code })),
            ]}
            valueField="value"
            labelField="label"
            value={warehouseId}
            onChange={(event) => setWarehouseId(event.target.value)}
            size="sm"
          />
        </div>
        <div className="w-64">
          <ITSelect
            name="kind"
            options={[
              { value: "", label: i18n.t("common.allTypes") },
              { value: "SHELF", label: i18n.t("common.shelves") },
              { value: "RECEIVING", label: i18n.t("nav.receiving") },
            ]}
            valueField="value"
            labelField="label"
            value={kind}
            onChange={(event) => setKind(event.target.value)}
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

        <DataTable<Location>
          columns={columns}
          fetchPage={(query) => catalogApi.locations(query)}
          externalFilters={externalFilters}
          reloadTrigger={reload}
        />
      </ITFlex>

      <LocationFormDialog
        open={formOpen}
        location={editing}
        onClose={() => setFormOpen(false)}
        onSaved={() => {
          setFormOpen(false);
          refresh();
          dispatch(showToast(editing ? i18n.t("catalog.locationUpdated") : i18n.t("catalog.locationCreated")));
        }}
      />

      <ReasonDialog
        open={toDeactivate !== null}
        title={i18n.t("catalog.deactivateLocation")}
        description={
          toDeactivate
            ? i18n.t("catalog.deactivateLocationHint", { name: toDeactivate.displayName, warehouse: toDeactivate.warehouseCode })
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
    </Page>
  );
}
