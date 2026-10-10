import { ITBadget, ITButton, ITText, type Column } from "@axzydev/axzy_ui_system";
import { useCallback, useState } from "react";
import { FaEdit, FaPlus } from "react-icons/fa";
import { useDispatch, useSelector } from "react-redux";
import { showToast } from "@app/toast/toast.slice";
import type { AppDispatch, RootState } from "@app/store";
import { catalogApi, type Warehouse } from "@entities/catalog";
import { can } from "@entities/session";
import WarehouseFormDialog from "@features/catalog/WarehouseFormDialog";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

/**
 * Bodegas.
 *
 * Este listado **no se pagina**, y no es un olvido: es un catálogo cerrado y
 * diminuto que se pide entero (docs/api/convenciones.md §1.4). Para que el
 * envoltorio de tabla —que espera una página— no tenga que inventar una, la
 * respuesta completa se envuelve como una sola página de tamaño `total`. La
 * alternativa era una tabla distinta para esta pantalla, y entonces la interfaz
 * tendría dos maneras de dibujar lo mismo.
 */
export default function WarehousesPage() {
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);

  const [reload, setReload] = useState(0);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Warehouse | null>(null);

  const refresh = useCallback(() => setReload((value) => value + 1), []);

  const columns: Column<Warehouse>[] = [
    { key: "code", label: "Código", type: "string" },
    { key: "name", label: "Nombre", type: "string" },
    { key: "address", label: "Dirección", type: "string" },
    {
      key: "isDefault",
      label: "Predeterminada",
      type: "boolean",
      render: (row) =>
        row.isDefault ? <ITBadget label="Sí" color="primary" size="sm" /> : <ITText muted>—</ITText>,
    },
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
      actions: (row) =>
        can(user, "warehouse.edit") ? (
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
        ) : null,
    },
  ];

  return (
    <Page
      title="Bodegas"
      description="Las bodegas del almacén. Solo una es la predeterminada."
      breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Bodegas" }]}
      actions={
        can(user, "warehouse.create") ? (
          <ITButton
            label="Nueva bodega"
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
          dispatch(showToast(editing ? "Bodega actualizada" : "Bodega creada"));
        }}
      />
    </Page>
  );
}
