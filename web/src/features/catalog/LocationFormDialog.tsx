import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITSelect, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import { catalogApi, useWarehouses, type Location, type LocationInput } from "@entities/catalog";
import { ApiError } from "@shared/api/types";

interface Props {
  open: boolean;
  /** `null` para alta; una ubicación para edición. */
  location: Location | null;
  onClose: () => void;
  onSaved: () => void;
}

const KIND_OPTIONS = [
  { value: "SHELF", label: "Estante" },
  { value: "RECEIVING", label: "Recepción" },
];

/**
 * Alta y edición de una ubicación.
 *
 * `hall`, `code` y `position` se mandan tal como se escriben y **el servidor los
 * pone en mayúsculas** (docs/flujos/catalogo.md §3): así `04`/`b`/`c` y
 * `04`/`B`/`C` son la misma ubicación en lugar de dos. La web no normaliza por su
 * cuenta, porque entonces habría dos reglas y una se quedaría atrás.
 *
 * La bodega **no se puede cambiar** al editar: mover una ubicación de bodega
 * cambiaría de bodega su stock sin ningún movimiento de inventario, y la API lo
 * rechaza con `WAREHOUSE_IMMUTABLE`.
 */
export default function LocationFormDialog({ open, location, onClose, onSaved }: Props) {
  const editing = location !== null;
  const { items: warehouses, loading: loadingWarehouses } = useWarehouses();

  const [warehouseId, setWarehouseId] = useState<string>("");
  const [hall, setHall] = useState("");
  const [code, setCode] = useState("");
  const [position, setPosition] = useState("");
  const [levels, setLevels] = useState("1");
  const [kind, setKind] = useState("SHELF");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setWarehouseId(String(location?.warehouseId ?? warehouses[0]?.id ?? ""));
    setHall(location?.hall ?? "");
    setCode(location?.code ?? "");
    setPosition(location?.position ?? "");
    setLevels(String(location?.levels ?? 1));
    setKind(location?.kind === "RECEIVING" ? "RECEIVING" : "SHELF");
  }, [open, location, warehouses]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);

    const body: LocationInput = {
      // El `ITSelect` del kit solo admite valores de texto; el id se convierte aquí.
      warehouseId: Number(warehouseId),
      hall: hall.trim(),
      code: code.trim(),
      position: position.trim(),
      levels: Number(levels) || 1,
      kind: kind === "RECEIVING" ? "RECEIVING" : "SHELF",
    };

    try {
      if (editing) await catalogApi.updateLocation(location.id, body);
      else await catalogApi.createLocation(body);
      onSaved();
    } catch (caught) {
      setError((caught as ApiError).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={editing ? "Editar ubicación" : "Nueva ubicación"}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          <ITSelect
            name="warehouseId"
            label="Bodega"
            options={warehouses.map((warehouse) => ({
              value: String(warehouse.id),
              label: `${warehouse.code} — ${warehouse.name}`,
            }))}
            valueField="value"
            labelField="label"
            value={warehouseId}
            onChange={(event) => setWarehouseId(event.target.value)}
            disabled={editing || loadingWarehouses}
            required
          />
          {editing && (
            <ITText muted className="text-xs">
              La bodega no se cambia: el stock que guarda la ubicación cambiaría de bodega sin un
              movimiento de inventario.
            </ITText>
          )}

          <ITFlex gap={3}>
            <ITInput name="hall" label="Pasillo" value={hall} onChange={(e) => setHall(e.target.value)} required maxLength={20} />
            <ITInput name="code" label="Localidad" value={code} onChange={(e) => setCode(e.target.value)} required maxLength={20} />
            <ITInput
              name="position"
              label="Posición"
              value={position}
              onChange={(e) => setPosition(e.target.value)}
              required
              maxLength={20}
            />
          </ITFlex>

          <ITFlex gap={3}>
            <ITInput
              name="levels"
              label="Niveles"
              type="number"
              min={1}
              value={levels}
              onChange={(e) => setLevels(e.target.value)}
              required
            />
            <ITSelect
              name="kind"
              label="Tipo"
              options={KIND_OPTIONS}
              valueField="value"
              labelField="label"
              value={kind}
              onChange={(event) => setKind(event.target.value)}
              required
            />
          </ITFlex>

          {error && <ITAlert variant="error" title="No se pudo guardar">{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label="Cancelar" variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? "Guardando…" : "Guardar"} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
