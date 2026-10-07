import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 11/13 del legacy (`Users.tsx`): alta de usuarios.
 *
 * La diferencia de fondo es el modelo de acceso: permisos con **alcance**
 * (`OWN`/`AREA`/`ALL`) en lugar de dos roles fijos (`Admin`/`User`), y catálogo
 * dinámico desde la base de datos en lugar de uniones hardcodeadas.
 */
export default function UsersPage() {
  return (
    <PhasePlaceholder
      title="Usuarios"
      description="Altas, edición, restablecimiento de contraseña y asignación de roles."
      breadcrumbLabel="Configuración"
      permission="user.view"
      endpoints={["GET /v1/users", "POST /v1/users", "PUT /v1/users/{id}"]}
    />
  );
}
