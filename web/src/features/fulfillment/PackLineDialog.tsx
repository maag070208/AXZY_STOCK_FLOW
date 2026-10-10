import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import type { OrderLine } from "@entities/fulfillment";
import { ApiError } from "@shared/api/types";
import { formatQuantity } from "@shared/lib/format";

interface Props {
  open: boolean;
  line: OrderLine | null;
  onClose: () => void;
  /** Lanza si la API rechaza el empaque (p. ej. `EXCEEDS_PENDING_QUANTITY`); el diálogo lo muestra. */
  onSubmit: (quantity: number) => Promise<void>;
}

/**
 * Empacar piezas de una línea. La cantidad es **lo que se empaca ahora**, no el total: la API la
 * valida contra lo pendiente, y aquí se muestra cuánto queda para no pedir de más.
 */
export default function PackLineDialog({ open, line, onClose, onSubmit }: Props) {
  const [quantityText, setQuantityText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setQuantityText("");
    setError(null);
  }, [open, line]);

  const pending = line ? Number(line.quantity) - Number(line.suppliedQuantity) : 0;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const quantity = Number(quantityText.trim().replace(",", "."));
    if (quantityText.trim() === "" || !Number.isFinite(quantity) || quantity <= 0) {
      setError("Escribe cuántas piezas empacas, mayor que cero.");
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await onSubmit(quantity);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "No se pudo registrar el empaque.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title="Empacar piezas">
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          {line && (
            <ITText muted className="text-sm">
              {line.productCode} · {line.productName}. Faltan {formatQuantity(String(pending))} piezas por surtir.
            </ITText>
          )}
          <ITInput name="quantity" label="Piezas empacadas ahora" value={quantityText} onChange={(e) => setQuantityText(e.target.value)} required autoFocus />

          {error && <ITAlert variant="error" title="No se pudo registrar el empaque">{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label="Cancelar" variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? "Registrando…" : "Registrar empaque"} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
