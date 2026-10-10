import { ITAlert, ITButton, ITDatePicker, ITDialog, ITFlex, ITInput, ITSelect, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import type { NewInvoice } from "@entities/receiving";
import { ApiError } from "@shared/api/types";
import { asDate, toIsoDay } from "@shared/lib/day";

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
  if (invoiceNumber.trim() === "") return "Falta el número de factura.";
  if (lines.length === 0) return "Una factura necesita al menos una línea.";
  for (const [index, line] of lines.entries()) {
    const n = index + 1;
    if (line.productCode.trim() === "") return `La línea ${n} necesita un código de producto.`;
    if (!positive(line.quantity)) return `La línea ${n} necesita una cantidad mayor que cero.`;
    if (!positive(line.boxQuantity)) return `La línea ${n} necesita un número de cajas mayor que cero.`;
    if (!positive(line.unitsPerBox)) return `La línea ${n} necesita piezas por caja mayores que cero.`;
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
    const problem = receiptDay === undefined ? "Falta una fecha de recepción válida." : problemWith(invoiceNumber, lines);
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
      setError(caught instanceof ApiError ? caught.message : "No se pudo registrar la factura.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title="Nueva factura">
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          <ITFlex gap={3} wrap="wrap">
            <ITInput
              name="invoiceNumber"
              label="Número de factura"
              value={invoiceNumber}
              onChange={(event) => setInvoiceNumber(event.target.value)}
              required
              maxLength={40}
            />
            <ITDatePicker name="receiptDate" label="Fecha de recepción" value={receiptDate} onChange={(event) => setReceiptDate(asDate(event.target.value) ?? new Date())} />
            <ITSelect
              name="invoiceType"
              label="Tipo"
              value={invoiceType}
              options={[
                { value: "NATIONAL", label: "Nacional" },
                { value: "INTERNATIONAL", label: "Internacional" },
              ]}
              onChange={(event) => setInvoiceType(event.target.value as NewInvoice["invoiceType"])}
            />
          </ITFlex>

          <ITText className="font-semibold">Líneas facturadas</ITText>
          {lines.map((line, index) => (
            <ITFlex key={index} gap={2} wrap="wrap" align="end">
              <ITInput name={`productCode-${index}`} label="Producto" value={line.productCode} onChange={(e) => updateLine(index, { productCode: e.target.value })} required maxLength={20} />
              <ITInput name={`container-${index}`} label="Contenedor" value={line.container} onChange={(e) => updateLine(index, { container: e.target.value })} maxLength={40} />
              <ITInput name={`quantity-${index}`} label="Cantidad" value={line.quantity} onChange={(e) => updateLine(index, { quantity: e.target.value })} required />
              <ITInput name={`boxQuantity-${index}`} label="Cajas" value={line.boxQuantity} onChange={(e) => updateLine(index, { boxQuantity: e.target.value })} required />
              <ITInput name={`unitsPerBox-${index}`} label="Piezas por caja" value={line.unitsPerBox} onChange={(e) => updateLine(index, { unitsPerBox: e.target.value })} required />
              <ITButton
                label="Quitar"
                variant="outline"
                color="danger"
                disabled={busy || lines.length === 1}
                onClick={() => setLines((current) => current.filter((_, i) => i !== index))}
              />
            </ITFlex>
          ))}
          <div>
            <ITButton label="Agregar línea" variant="outline" disabled={busy} onClick={() => setLines((current) => [...current, emptyLine()])} />
          </div>

          {error && <ITAlert variant="error" title="No se pudo registrar la factura">{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label="Cancelar" variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? "Registrando…" : "Registrar factura"} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
