import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import type { CaptureBody, InvoiceLine } from "@entities/receiving";
import { ApiError } from "@shared/api/types";

interface Props {
  open: boolean;
  line: InvoiceLine | null;
  onClose: () => void;
  /** Lanza si la API rechaza el conteo; el diálogo muestra el motivo y sigue abierto. */
  onSubmit: (body: CaptureBody) => Promise<void>;
}

/**
 * El conteo físico de una línea.
 *
 * Lo contado va en piezas. Las cajas son opcionales y son una **cuenta entera** («1 caja y 30
 * sueltas»): un valor con fracción lo rechaza la API, y aquí se avisa antes de enviarlo.
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
      setError("Escribe cuántas piezas contaste, cero o más.");
      return;
    }
    const boxesRaw = physicalBoxQuantity.trim();
    const boxes = boxesRaw === "" ? undefined : Number(boxesRaw.replace(",", "."));
    if (boxes !== undefined && (!Number.isInteger(boxes) || boxes < 0)) {
      setError("Las cajas contadas son un número entero: no hay media caja.");
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await onSubmit(boxes === undefined ? { physicalQuantity: quantity } : { physicalQuantity: quantity, physicalBoxQuantity: boxes });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "No se pudo guardar el conteo.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title="Contar línea">
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          {line && (
            <ITText muted className="text-sm">
              {line.productCode} · {line.productName}. Facturado: {line.quantity} piezas.
            </ITText>
          )}
          <ITInput
            name="physicalQuantity"
            label="Piezas contadas"
           
            value={physicalQuantity}
            onChange={(event) => setPhysicalQuantity(event.target.value)}
            required
            autoFocus
          />
          <ITInput
            name="physicalBoxQuantity"
            label="Cajas contadas (opcional)"
           
            value={physicalBoxQuantity}
            onChange={(event) => setPhysicalBoxQuantity(event.target.value)}
          />

          {error && <ITAlert variant="error" title="No se pudo guardar el conteo">{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label="Cancelar" variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? "Guardando…" : "Guardar conteo"} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
