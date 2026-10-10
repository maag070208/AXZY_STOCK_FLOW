import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITSelect, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import type { OrderLine, ShortageBody } from "@entities/fulfillment";

import { ADJUSTMENT_REASONS } from "@shared/lib/adjustmentReasons";
import { formatQuantity } from "@shared/lib/format";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

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
      setError(i18n.t("fulfillment.piecesMissingRequired"));
      return;
    }
    if (reason === "") {
      setError(i18n.t("fulfillment.chooseShortageReason"));
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await onSubmit({ quantity, reason, ...(note.trim() === "" ? {} : { note: note.trim() }) });
    } catch (caught) {
      setError(describeError(caught, "fulfillment.shortageFailedDot"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={i18n.t("fulfillment.recordShortage")}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          {line && (
            <ITText muted className="text-sm">
              {line.productCode}. Faltan {formatQuantity(String(pending))} piezas por surtir. El faltante descuenta del pendiente y queda como ajuste.
            </ITText>
          )}
          <ITInput name="quantity" label={i18n.t("fulfillment.piecesMissing")} value={quantityText} onChange={(e) => setQuantityText(e.target.value)} required autoFocus />
          <ITSelect
            name="reason"
            label={i18n.t("common.reason")}
            placeholder={i18n.t("receiving.chooseReason")}
            value={reason}
            options={ADJUSTMENT_REASONS.map((r) => ({ value: r.value, label: r.label }))}
            onChange={(e) => setReason(e.target.value)}
          />
          <ITInput name="note" label={i18n.t("common.noteOptional")} value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />

          {error && <ITAlert variant="error" title={i18n.t("fulfillment.shortageFailed")}>{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label={i18n.t("common.cancel")} variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? i18n.t("common.recording") : i18n.t("fulfillment.recordShortage")} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
