import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import type { CaptureBody, InvoiceLine } from "@entities/receiving";

import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

interface Props {
  open: boolean;
  line: InvoiceLine | null;
  onClose: () => void;
  /** Throws if the API rejects the count; the dialog shows the reason and stays open. */
  onSubmit: (body: CaptureBody) => Promise<void>;
}

/**
 * The physical count of a line.
 *
 * What is counted is in pieces. Boxes are optional and are a **whole count** ("1 box and 30
 * "single"): a value with a fraction is rejected by the API, and here it is reported before sending it.
 */
export default function CaptureLineDialog({ open, line, onClose, onSubmit }: Props) {
  const [physicalQuantity, setPhysicalQuantity] = useState("");
  const [physicalBoxQuantity, setPhysicalBoxQuantity] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setPhysicalQuantity(line?.physicalQuantity ?? "");
    setPhysicalBoxQuantity(line?.physicalBoxQuantity ?? "");
    setError(null);
  }, [open, line]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const quantity = Number(physicalQuantity.trim().replace(",", "."));
    if (physicalQuantity.trim() === "" || !Number.isFinite(quantity) || quantity < 0) {
      setError(i18n.t("receiving.countedPiecesRequired"));
      return;
    }
    const boxesRaw = physicalBoxQuantity.trim();
    const boxes = boxesRaw === "" ? undefined : Number(boxesRaw.replace(",", "."));
    if (boxes !== undefined && (!Number.isInteger(boxes) || boxes < 0)) {
      setError(i18n.t("receiving.boxesWholeNumber"));
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await onSubmit(boxes === undefined ? { physicalQuantity: quantity } : { physicalQuantity: quantity, physicalBoxQuantity: boxes });
    } catch (caught) {
      setError(describeError(caught, "receiving.saveCountFailedDot"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={i18n.t("receiving.countLine")}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          {line && (
            <ITText muted className="text-sm">
              {line.productCode} · {line.productName}. Facturado: {line.quantity} piezas.
            </ITText>
          )}
          <ITInput
            name="physicalQuantity"
            label={i18n.t("receiving.countedPieces")}
           
            value={physicalQuantity}
            onChange={(event) => setPhysicalQuantity(event.target.value)}
            required
            autoFocus
          />
          <ITInput
            name="physicalBoxQuantity"
            label={i18n.t("receiving.countedBoxesOptional")}
           
            value={physicalBoxQuantity}
            onChange={(event) => setPhysicalBoxQuantity(event.target.value)}
          />

          {error && <ITAlert variant="error" title={i18n.t("receiving.saveCountFailed")}>{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label={i18n.t("common.cancel")} variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? i18n.t("catalog.saving") : i18n.t("receiving.saveCount")} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
