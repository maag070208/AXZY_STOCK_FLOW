import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITSelect, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import { catalogApi, useWarehouses, type Location, type LocationInput } from "@entities/catalog";

import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

interface Props {
  open: boolean;
  /** `null` for creation; a location for editing. */
  location: Location | null;
  onClose: () => void;
  onSaved: () => void;
}

const KIND_OPTIONS = [
  { value: "SHELF", label: i18n.t("common.shelf") },
  { value: "RECEIVING", label: i18n.t("nav.receiving") },
];

/**
 * Create and edit a location.
 *
 * `hall`, `code` and `position` are sent exactly as written and **the server
 * capitalizes** (docs/flujos/catalogo.md §3): so `04`/`b`/`c` and
 * `04`/`B`/`C` are the same location, not two. The web does not normalize by its
 * account, because otherwise there would be two rules and one would be left behind.
 *
 * The warehouse **cannot be changed** when editing: moving a location to another warehouse
 * would change its warehouse stock without any inventory movement, and the API
 * rejects with `WAREHOUSE_IMMUTABLE`.
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
      // The kit's `ITSelect` only accepts text values; the id is converted here.
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
      setError(describeError(caught, "errors.unexpected"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={editing ? i18n.t("catalog.editLocation") : i18n.t("catalog.newLocation")}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          <ITSelect
            name="warehouseId"
            label={i18n.t("common.warehouse")}
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
            <ITInput name="hall" label={i18n.t("common.aisle")} value={hall} onChange={(e) => setHall(e.target.value)} required maxLength={20} />
            <ITInput name="code" label={i18n.t("common.locality")} value={code} onChange={(e) => setCode(e.target.value)} required maxLength={20} />
            <ITInput
              name="position"
              label={i18n.t("common.position")}
              value={position}
              onChange={(e) => setPosition(e.target.value)}
              required
              maxLength={20}
            />
          </ITFlex>

          <ITFlex gap={3}>
            <ITInput
              name="levels"
              label={i18n.t("common.levels")}
              type="number"
              min={1}
              value={levels}
              onChange={(e) => setLevels(e.target.value)}
              required
            />
            <ITSelect
              name="kind"
              label={i18n.t("common.type")}
              options={KIND_OPTIONS}
              valueField="value"
              labelField="label"
              value={kind}
              onChange={(event) => setKind(event.target.value)}
              required
            />
          </ITFlex>

          {error && <ITAlert variant="error" title={i18n.t("catalog.saveFailed")}>{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label={i18n.t("common.cancel")} variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? i18n.t("catalog.saving") : i18n.t("common.save")} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
