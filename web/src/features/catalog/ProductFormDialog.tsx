import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import { catalogApi, type Product, type ProductInput } from "@entities/catalog";
import { ApiError } from "@shared/api/types";

interface Props {
  open: boolean;
  /** `null` para alta; un producto para edición. */
  product: Product | null;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Alta y edición de un producto.
 *
 * Dos cosas que hace a propósito:
 *
 *  - **El código no se edita.** Está impreso en las etiquetas y en los estantes:
 *    la API lo rechaza con `PRODUCT_CODE_IMMUTABLE` y aquí se muestra bloqueado
 *    para no ofrecer algo que va a fallar.
 *  - **La unidad de medida es texto, no una lista.** El catálogo de unidades vive
 *    en la base y **no hay endpoint que lo exponga todavía**; una lista escrita a
 *    mano en la web sería una segunda fuente de verdad que se desincroniza. Si no
 *    existe, la API responde `UNKNOWN_UNIT_MEASURE` diciendo cuál se equivocó.
 */
export default function ProductFormDialog({ open, product, onClose, onSaved }: Props) {
  const editing = product !== null;
  const [productCode, setProductCode] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [unitMeasure, setUnitMeasure] = useState("PZA");
  const [error, setError] = useState<{ message: string; field?: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setProductCode(product?.productCode ?? "");
    setName(product?.name ?? "");
    setDescription(product?.description ?? "");
    setUnitMeasure(product?.unitMeasure ?? "PZA");
  }, [open, product]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);

    const body: ProductInput = {
      productCode: productCode.trim(),
      name: name.trim(),
      description: description.trim() === "" ? null : description.trim(),
      unitMeasure: unitMeasure.trim().toUpperCase(),
    };

    try {
      if (editing) await catalogApi.updateProduct(product.id, body);
      else await catalogApi.createProduct(body);
      onSaved();
    } catch (caught) {
      const apiError = caught as ApiError;
      // El campo se marca cuando el código del error dice de qué campo habla; el
      // cliente decide con `code`, nunca comparando el texto (docs/api/errores.md).
      setError({
        message: apiError.message,
        field: isProductCodeError(apiError.code) ? "productCode" : undefined,
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={editing ? "Editar producto" : "Nuevo producto"}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          <ITInput
            name="productCode"
            label="Código"
            value={productCode}
            onChange={(event) => setProductCode(event.target.value)}
            disabled={editing}
            required
            maxLength={20}
            placeholder="ABRAA1B"
            error={error?.field === "productCode" ? error.message : undefined}
          />
          {editing && (
            <ITText muted className="text-xs">
              El código no se cambia: está impreso en las etiquetas y en los estantes.
            </ITText>
          )}

          <ITInput
            name="name"
            label="Nombre"
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            maxLength={120}
          />
          <ITInput
            name="description"
            label="Descripción"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
          <ITInput
            name="unitMeasure"
            label="Unidad de medida"
            value={unitMeasure}
            onChange={(event) => setUnitMeasure(event.target.value)}
            required
            placeholder="PZA"
          />

          {error && !error.field && <ITAlert variant="error" title="No se pudo guardar">{error.message}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label="Cancelar" variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? "Guardando…" : "Guardar"} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}

/** Los errores que hablan del código de producto, según el catálogo de errores. */
const isProductCodeError = (code?: string): boolean =>
  code === "INVALID_PRODUCT_CODE" || code === "DUPLICATE_PRODUCT_CODE" || code === "PRODUCT_CODE_IMMUTABLE";
