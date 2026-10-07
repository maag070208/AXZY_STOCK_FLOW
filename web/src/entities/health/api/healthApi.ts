import { get } from "@shared/api";
import type { HealthResponse } from "@shared/api";

/**
 * Salud del sistema (§10). **Endpoint público**: no exige JWT, así que la página
 * de diagnóstico funciona incluso cuando la sesión es el problema. `503` si el
 * estado es `DOWN`; el cliente lo convierte en `ApiError` con el detalle en
 * `details`.
 */
export const fetchHealth = (): Promise<HealthResponse> => get<HealthResponse>("/health");
