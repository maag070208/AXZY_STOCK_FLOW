import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITSelect, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import type { OrderLine, ShortageBody } from "@entities/fulfillment";
import { ApiError } from "@shared/api/types";
import { ADJUSTMENT_REASONS } from "@shared/lib/adjustmentReasons";
import { formatQuantity } from "@shared/lib/format";

interface Props {
  open: boolean;
  line: OrderLine | null;
  onClose: () => void;
  /** Lanza si la API rechaza el faltante; el diálogo muestra el motivo y sigue abierto. */
  onSubmit: (body: Omit<ShortageBody, "lineId">) => Promise<void>;
}

/**
 * Un faltante durante el surtido. Es un **ajuste** de inventario, no un flujo aparte: por eso lleva
 * motivo obligatorio, igual que cualquier merma. La web no preselecciona un motivo.
 */
export default function ShortageDialog({ open, line, onClose, onSubmit }: Props) {
  const [quantityText, setQuantityText] = useState("");
  const [reason, setReason] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setQuantityText("");
    setReason("");
    setNote("");
    setError(null);
  }, [open, line]);

  const pending = line ? Number(line.quantity) - Number(line.suppliedQuantity) : 0;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const quantity = Number(quantityText.trim().replace(",", "."));
    if (quantityText.trim() === "" || !Number.isFinite(quantity) || quantity <= 0) {
      setError("Escribe cuántas piezas faltan, mayor que cero.");
      return;
    }
    if (reason === "") {
      setError("Elige el motivo del faltante.");
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await onSubmit({ quantity, reason, ...(note.trim() === "" ? {} : { note: note.trim() }) });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "No se pudo registrar el faltante.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title="Registrar faltante">
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          {line && (
            <ITText muted className="text-sm">
              {line.productCode}. Faltan {formatQuantity(String(pending))} piezas por surtir. El faltante descuenta del pendiente y queda como ajuste.
            </ITText>
          )}
          <ITInput name="quantity" label="Piezas que faltan" value={quantityText} onChange={(e) => setQuantityText(e.target.value)} required autoFocus />
          <ITSelect
            name="reason"
            label="Motivo"
            placeholder="Elige un motivo"
            value={reason}
            options={ADJUSTMENT_REASONS.map((r) => ({ value: r.value, label: r.label }))}
            onChange={(e) => setReason(e.target.value)}
          />
          <ITInput name="note" label="Nota (opcional)" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />

          {error && <ITAlert variant="error" title="No se pudo registrar el faltante">{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label="Cancelar" variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? "Registrando…" : "Registrar faltante"} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
