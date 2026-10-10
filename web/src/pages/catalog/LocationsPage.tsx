import { ITAlert, ITBadget, ITButton, ITFlex, ITInput, ITSelect, type Column } from "@axzydev/axzy_ui_system";
import { useCallback, useMemo, useState } from "react";
import { FaEdit, FaPlus, FaTrashAlt } from "react-icons/fa";
import { useDispatch, useSelector } from "react-redux";
import { showToast } from "@app/toast/toast.slice";
import type { AppDispatch, RootState } from "@app/store";
import { catalogApi, useWarehouses, type Location } from "@entities/catalog";
import { can } from "@entities/session";
import LocationFormDialog from "@features/catalog/LocationFormDialog";
import ReasonDialog from "@features/catalog/ReasonDialog";
import { ApiError } from "@shared/api/types";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

const KIND_LABEL: Record<Location["kind"], string> = {
  SHELF: "Estante",
  RECEIVING: "Recepción",
  UNASSIGNED: "Sin ubicar",
};

/**
 * Ubicaciones: el listado, con los filtros que la API admite (`q`, `warehouseId`,
 * `kind`, `active`).
 *
 * La columna de nombre visible muestra `displayName`, que **compone el servidor**:
 * la web no concatena `hall + code + position` (docs/arquitectura/web-fsd.md §2.3).
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
      dispatch(showToast(`Ubicación ${toDeactivate.displayName} desactivada`));
    } catch (caught) {
      // `LOCATION_HAS_STOCK` y `RECEIVING_LOCATION_PROTECTED` explican qué pasó y
      // qué hacer; el mensaje de la API es el que se muestra.
      setActionError((caught as ApiError).message);
    } finally {
      setBusy(false);
    }
  };

  const columns: Column<Location>[] = [
    { key: "displayName", label: "Ubicación", type: "string" },
    { key: "warehouseCode", label: "Bodega", type: "string" },
    { key: "kind", label: "Tipo", type: "string", render: (row) => KIND_LABEL[row.kind] },
    { key: "levels", label: "Niveles", type: "number" },
    {
      key: "active",
      label: "Estado",
      type: "boolean",
      filter: true,
      render: (row) => (
        <ITBadget label={row.active ? "Activa" : "Inactiva"} color={row.active ? "success" : "secondary"} size="sm" />
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
              ariaLabel="Editar"
              title="Editar"
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
              ariaLabel="Desactivar"
              title="Desactivar"
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
    <Page
      title="Ubicaciones"
      description="Pasillos, localidades y posiciones del almacén."
      breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Ubicaciones" }]}
      actions={
        <ITFlex gap={2} align="center" wrap="wrap">
          <ITInput
            name="search"
            placeholder="Buscar por pasillo, localidad o posición"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            size="sm"
          />
          <ITSelect
            name="warehouseId"
            options={[
              { value: "", label: "Todas las bodegas" },
              ...warehouses.map((warehouse) => ({ value: String(warehouse.id), label: warehouse.code })),
            ]}
            valueField="value"
            labelField="label"
            value={warehouseId}
            onChange={(event) => setWarehouseId(event.target.value)}
            size="sm"
          />
          <ITSelect
            name="kind"
            options={[
              { value: "", label: "Todo tipo" },
              { value: "SHELF", label: "Estantes" },
              { value: "RECEIVING", label: "Recepción" },
            ]}
            valueField="value"
            labelField="label"
            value={kind}
            onChange={(event) => setKind(event.target.value)}
            size="sm"
          />
          {can(user, "location.create") && (
            <ITButton
              label="Nueva ubicación"
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
      <ITFlex direction="column" gap={3}>
        {actionError && !toDeactivate && (
          <ITAlert variant="error" title="No se pudo completar" dismissible onDismiss={() => setActionError(null)}>
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
          dispatch(showToast(editing ? "Ubicación actualizada" : "Ubicación creada"));
        }}
      />

      <ReasonDialog
        open={toDeactivate !== null}
        title="Desactivar ubicación"
        description={
          toDeactivate
            ? `Se desactivará «${toDeactivate.displayName}» de ${toDeactivate.warehouseCode}. Una ubicación con existencias no se puede desactivar.`
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
