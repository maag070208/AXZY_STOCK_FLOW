import { ITAlert, ITButton, ITCheckbox, ITDialog, ITFlex, ITInput, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import { catalogApi, type Warehouse, type WarehouseInput } from "@entities/catalog";

import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

interface Props {
  open: boolean;
  /** `null` para alta; una bodega para edición. */
  warehouse: Warehouse | null;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Alta y edición de una bodega.
 *
 * La marca de **predeterminada no se puede quitar sin poner otra**: la API la
 * conserva si se intenta (`UpdateWarehouse`), porque el sistema necesita saber
 * dónde aterriza lo que llega sin destino declarado. Aquí la casilla se muestra
 * bloqueada en ese caso, en lugar de ofrecer algo que no va a pasar.
 */
export default function WarehouseFormDialog({ open, warehouse, onClose, onSaved }: Props) {
  const editing = warehouse !== null;
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const [isDefault, setIsDefault] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setCode(warehouse?.code ?? "");
    setName(warehouse?.name ?? "");
    setAddress(warehouse?.address ?? "");
    setIsDefault(warehouse?.isDefault ?? false);
  }, [open, warehouse]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);

    const body: WarehouseInput = {
      code: code.trim().toUpperCase(),
      name: name.trim(),
      address: address.trim() === "" ? null : address.trim(),
      isDefault,
    };

    try {
      if (editing) await catalogApi.updateWarehouse(warehouse.id, body);
      else await catalogApi.createWarehouse(body);
      onSaved();
    } catch (caught) {
      setError(describeError(caught, "errors.unexpected"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={editing ? i18n.t("catalog.editWarehouse") : i18n.t("catalog.newWarehouse")}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          <ITInput
            name="code"
            label={i18n.t("common.code")}
            value={code}
            onChange={(event) => setCode(event.target.value)}
            required
            maxLength={20}
            placeholder="MAIN"
          />
          <ITInput
            name="name"
            label={i18n.t("common.name")}
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            maxLength={120}
          />
          <ITInput name="address" label={i18n.t("common.address")} value={address} onChange={(event) => setAddress(event.target.value)} />

          <ITCheckbox
            checked={isDefault}
            onChange={setIsDefault}
            label={i18n.t("common.isDefaultWarehouse")}
            disabled={editing && warehouse.isDefault}
          />
          {editing && warehouse.isDefault && (
            <ITText muted className="text-xs">
              Para dejar de ser la predeterminada, marca otra bodega: el sistema siempre necesita una.
            </ITText>
          )}

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
