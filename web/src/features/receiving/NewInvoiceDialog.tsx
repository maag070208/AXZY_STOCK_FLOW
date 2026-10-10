import { ITAlert, ITButton, ITDatePicker, ITDialog, ITFlex, ITInput, ITSelect, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import type { NewInvoice } from "@entities/receiving";

import { asDate, toIsoDay } from "@shared/lib/day";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

interface Props {
  open: boolean;
  onClose: () => void;
  /** Lanza si la API rechaza el alta: el diálogo muestra su mensaje y sigue abierto. */
  onSubmit: (body: NewInvoice) => Promise<void>;
}

/** Una línea tal como se escribe: texto, hasta que se convierte al enviar. */
type LineForm = { productCode: string; container: string; quantity: string; boxQuantity: string; unitsPerBox: string };

const emptyLine = (): LineForm => ({ productCode: "", container: "", quantity: "", boxQuantity: "", unitsPerBox: "" });

/** Acepta coma decimal, que es lo que se escribe en el teclado de México. */
const toNumber = (raw: string): number => Number(raw.trim().replace(",", "."));

const positive = (raw: string): boolean => {
  const value = toNumber(raw);
  return raw.trim() !== "" && Number.isFinite(value) && value > 0;
};

/**
 * Lo que la web comprueba antes de enviar: solo lo que el formulario puede saber sin la base.
 * Que el empaque cuadre, que el producto exista y que la unidad sea válida los dice la API con su
 * código, y se muestran tal cual.
 */
const problemWith = (invoiceNumber: string, lines: LineForm[]): string | null => {
  if (invoiceNumber.trim() === "") return i18n.t("receiving.invoiceNumberRequired");
  if (lines.length === 0) return i18n.t("receiving.atLeastOneLine");
  for (const [index, line] of lines.entries()) {
    const n = index + 1;
    if (line.productCode.trim() === "") return i18n.t("receiving.lineNeedsProductCode", { n });
    if (!positive(line.quantity)) return i18n.t("receiving.lineNeedsQuantity", { n });
    if (!positive(line.boxQuantity)) return i18n.t("receiving.lineNeedsBoxes", { n });
    if (!positive(line.unitsPerBox)) return i18n.t("receiving.lineNeedsPiecesPerBox", { n });
  }
  return null;
};

/** Alta de factura con sus líneas facturadas. Sin líneas no hay nada que contar, así que no se envía. */
export default function NewInvoiceDialog({ open, onClose, onSubmit }: Props) {
  const [invoiceNumber, setInvoiceNumber] = useState("");
  const [receiptDate, setReceiptDate] = useState<Date>(() => new Date());
  const [invoiceType, setInvoiceType] = useState<NewInvoice["invoiceType"]>("NATIONAL");
  const [lines, setLines] = useState<LineForm[]>([emptyLine()]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Cada apertura empieza en blanco: una factura a medio escribir no debe sobrevivir a cerrar el diálogo.
  useEffect(() => {
    if (!open) return;
    setInvoiceNumber("");
    setReceiptDate(new Date());
    setInvoiceType("NATIONAL");
    setLines([emptyLine()]);
    setError(null);
  }, [open]);

  const updateLine = (index: number, patch: Partial<LineForm>) =>
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...patch } : line)));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const receiptDay = toIsoDay(receiptDate);
    const problem = receiptDay === undefined ? i18n.t("receiving.receiptDateRequired") : problemWith(invoiceNumber, lines);
    if (problem || receiptDay === undefined) {
      setError(problem);
      return;
    }

    const body: NewInvoice = {
      invoiceNumber: invoiceNumber.trim(),
      receiptDate: receiptDay,
      invoiceType,
      lines: lines.map((line) => ({
        productCode: line.productCode.trim().toUpperCase(),
        container: line.container.trim() === "" ? null : line.container.trim(),
        quantity: toNumber(line.quantity),
        boxQuantity: toNumber(line.boxQuantity),
        unitsPerBox: toNumber(line.unitsPerBox),
      })),
    };

    setBusy(true);
    setError(null);
    try {
      await onSubmit(body);
    } catch (caught) {
      setError(describeError(caught, "receiving.registerFailedDot"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={i18n.t("receiving.newInvoice")}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          <ITFlex gap={3} wrap="wrap">
            <ITInput
              name="invoiceNumber"
              label={i18n.t("receiving.invoiceNumber")}
              value={invoiceNumber}
              onChange={(event) => setInvoiceNumber(event.target.value)}
              required
              maxLength={40}
            />
            <ITDatePicker name="receiptDate" label={i18n.t("receiving.receiptDate")} value={receiptDate} onChange={(event) => setReceiptDate(asDate(event.target.value) ?? new Date())} />
            <ITSelect
              name="invoiceType"
              label={i18n.t("common.type")}
              value={invoiceType}
              options={[
                { value: "NATIONAL", label: i18n.t("common.national") },
                { value: "INTERNATIONAL", label: i18n.t("common.international") },
              ]}
              onChange={(event) => setInvoiceType(event.target.value as NewInvoice["invoiceType"])}
            />
          </ITFlex>

          <ITText className="font-semibold">{i18n.t("receiving.billedLines")}</ITText>
          {lines.map((line, index) => (
            <ITFlex key={index} gap={2} wrap="wrap" align="end">
              <ITInput name={`productCode-${index}`} label={i18n.t("common.product")} value={line.productCode} onChange={(e) => updateLine(index, { productCode: e.target.value })} required maxLength={20} />
              <ITInput name={`container-${index}`} label={i18n.t("receiving.container")} value={line.container} onChange={(e) => updateLine(index, { container: e.target.value })} maxLength={40} />
              <ITInput name={`quantity-${index}`} label={i18n.t("common.quantity")} value={line.quantity} onChange={(e) => updateLine(index, { quantity: e.target.value })} required />
              <ITInput name={`boxQuantity-${index}`} label={i18n.t("receiving.boxes")} value={line.boxQuantity} onChange={(e) => updateLine(index, { boxQuantity: e.target.value })} required />
              <ITInput name={`unitsPerBox-${index}`} label={i18n.t("receiving.piecesPerBox")} value={line.unitsPerBox} onChange={(e) => updateLine(index, { unitsPerBox: e.target.value })} required />
              <ITButton
                label={i18n.t("common.remove")}
                variant="outline"
                color="danger"
                disabled={busy || lines.length === 1}
                onClick={() => setLines((current) => current.filter((_, i) => i !== index))}
              />
            </ITFlex>
          ))}
          <div>
            <ITButton label={i18n.t("receiving.addLine")} variant="outline" disabled={busy} onClick={() => setLines((current) => [...current, emptyLine()])} />
          </div>

          {error && <ITAlert variant="error" title={i18n.t("receiving.registerFailed")}>{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label={i18n.t("common.cancel")} variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? i18n.t("common.recording") : i18n.t("receiving.registerInvoice")} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
