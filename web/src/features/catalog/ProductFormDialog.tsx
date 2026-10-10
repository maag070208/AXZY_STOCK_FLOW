import { ITAlert, ITButton, ITDialog, ITFlex, ITInput, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import { catalogApi, type Product, type ProductInput } from "@entities/catalog";
import { ApiError } from "@shared/api/types";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

interface Props {
  open: boolean;
  /** `null` for creation; a product for editing. */
  product: Product | null;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Create and edit a product.
 *
 * Two things it does on purpose:
 *
 *  - **The code is not edited.** It is printed on the labels and on the shelves:
 *    the API rejects it with `PRODUCT_CODE_IMMUTABLE` and here it is shown as blocked
 *    to avoid offering something that is going to fail.
 *  - **The unit of measure is text, not a list.** The unit catalog lives
 *    in the database and **there is no endpoint that exposes it yet**; a list written to
 *    manually in the web would be a second source of truth that drifts out of sync. If not
 *    exists, the API responds with `UNKNOWN_UNIT_MEASURE` saying which one was wrong.
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
      // The field is marked when the error code says which field it refers to; the
      // the client decides with `code`, never by comparing the text (docs/api/errores.md).
      setError({
        message: describeError(apiError, "errors.unexpected"),
        field: isProductCodeError(apiError.code) ? "productCode" : undefined,
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <ITDialog isOpen={open} onClose={onClose} title={editing ? i18n.t("catalog.editProduct") : i18n.t("catalog.newProduct")}>
      <form onSubmit={submit}>
        <ITFlex direction="column" gap={4}>
          <ITInput
            name="productCode"
            label={i18n.t("common.code")}
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
            label={i18n.t("common.name")}
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            maxLength={120}
          />
          <ITInput
            name="description"
            label={i18n.t("common.description")}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
          <ITInput
            name="unitMeasure"
            label={i18n.t("common.unitMeasure")}
            value={unitMeasure}
            onChange={(event) => setUnitMeasure(event.target.value)}
            required
            placeholder="PZA"
          />

          {error && !error.field && <ITAlert variant="error" title={i18n.t("catalog.saveFailed")}>{error.message}</ITAlert>}

          <ITFlex justify="end" gap={2}>
            <ITButton label={i18n.t("common.cancel")} variant="outline" onClick={onClose} disabled={busy} />
            <ITButton type="submit" label={busy ? i18n.t("catalog.saving") : i18n.t("common.save")} color="primary" disabled={busy} />
          </ITFlex>
        </ITFlex>
      </form>
    </ITDialog>
  );
}

/** The errors that refer to the product code, according to the error catalog. */
const isProductCodeError = (code?: string): boolean =>
  code === "INVALID_PRODUCT_CODE" || code === "DUPLICATE_PRODUCT_CODE" || code === "PRODUCT_CODE_IMMUTABLE";
