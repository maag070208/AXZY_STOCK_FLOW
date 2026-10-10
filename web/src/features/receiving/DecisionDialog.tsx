import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITSelect, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import { ADJUSTMENT_REASONS, type DecisionBody } from "@entities/receiving";
import { ApiError } from "@shared/api/types";

interface Props {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  onClose: () => void;
  /** Lanza si la API rechaza la decisión (p. ej. `SEGREGATION_OF_DUTIES`); el diálogo muestra el motivo. */
  onSubmit: (body: DecisionBody) => Promise<void>;
}

/**
 * Aprobar o rechazar las discrepancias de una factura. Las dos decisiones piden lo mismo: un motivo
 * (obligatorio, lo registra la bitácora) y una nota opcional.
 */
export default function DecisionDialog({ open, title, description, confirmLabel, onClose, onSubmit }: Props) {
  const [reason, setReason] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Cada decisión empieza sin motivo: heredar el del diálogo anterior registraría un motivo que nadie eligió.
  useEffect(() => {
    if (!open) return;
    setReason("");
    setNote("");
    setError(null);
  }, [open]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (reason === "") {
      setError("Elige un motivo.");
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await onSubmit({ adjustmentReason: reason, ...(note.trim() === "" ? {} : { note: note.trim() }) });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "No se pudo registrar la decisión.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={title}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          <ITText muted className="text-sm">{description}</ITText>
          <ITSelect
            name="adjustmentReason"
            label="Motivo"
            placeholder="Elige un motivo"
            value={reason}
            options={ADJUSTMENT_REASONS.map((r) => ({ value: r.value, label: r.label }))}
            onChange={(event) => setReason(event.target.value)}
          />
          <ITInput name="note" label="Nota (opcional)" value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} />

          {error && <ITAlert variant="error" title="No se pudo registrar la decisión">{error}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label="Cancelar" variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? "Registrando…" : confirmLabel} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}
