import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import type { OrderLine } from "@entities/fulfillment";

import { formatQuantity } from "@shared/lib/format";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

interface Props {
  open: boolean;
  line: OrderLine | null;
  onClose: () => void;
  /** Throws if the API rejects the packing (e.g. `EXCEEDS_PENDING_QUANTITY`); the dialog shows it. */
  onSubmit: (quantity: number) => Promise<void>;
}

/**
 * Packs pieces of a line. The quantity is **what is packed now**, not the total: the API
 * validates against the pending amount, and here it is shown how much is left so as not to request too much.
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
      setError(i18n.t("fulfillment.piecesPackedRequired"));
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await onSubmit(quantity);
    } catch (caught) {
      setError(describeError(caught, "fulfillment.packFailedDot"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={i18n.t("fulfillment.packPieces")}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          {line && (
            <ITText muted className="text-sm">
              {line.productCode} · {line.productName}. Faltan {formatQuantity(String(pending))} piezas por surtir.
            </ITText>
          )}
          <ITInput name="quantity" label={i18n.t("fulfillment.piecesPackedNow")} value={quantityText} onChange={(e) => setQuantityText(e.target.value)} required autoFocus />

          {error && <ITAlert variant="error" title={i18n.t("fulfillment.packFailed")}>{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label={i18n.t("common.cancel")} variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? i18n.t("common.recording") : i18n.t("fulfillment.recordPack")} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
