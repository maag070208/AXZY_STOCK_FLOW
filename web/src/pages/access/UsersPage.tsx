import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@shared/api";
import { usePermissionScope } from "@shared/auth";
import type { RoleAdmin, UserAdmin } from "@shared/api";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";
import { fetchRoles, fetchUsers } from "@entities/identity";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * USUARIOS — CONSULTA contra la API real
 *
 * ── ESTA PANTALLA NO EDITA, Y ES DELIBERADO ────────────────────────────────
 *
 * La API publica **una sola ruta de usuarios: `GET /v1/users`**. No hay alta, ni
 * edición, ni restablecimiento de contraseña, ni asignación de roles.
 *
 * La pantalla anterior declaraba `["GET /v1/users", "POST /v1/users",
 * "PUT /v1/users/{id}"]`: **dos de esas tres rutas no existen**, y anunciarlas en
 * la documentación de la pantalla es la misma clase de mentira que la maqueta de
 * Traslados, que decía que un endpoint faltaba cuando ya estaba. Cambia el signo,
 * no la clase.
 *
 * Por eso aquí no hay formulario. **Un formulario que devuelve `404` al guardar es
 * peor que no tenerlo**: el usuario rellena ocho campos, pulsa guardar, y descubre
 * que su trabajo se perdió. La ausencia se dice en pantalla.
 *
 * ── LO QUE SÍ APORTA, Y NO ES POCO ─────────────────────────────────────────
 *
 * Lo primero que hace falta para gestionar accesos es **ver qué hay**: quién
 * existe, si está activo, cuándo entró por última vez y con qué roles. Y cruzar
 * los roles con el catálogo enseña lo que un listado de claves no enseña — que hay
 * un rol con cero usuarios, o uno con todos los permisos.
 *
 * El nombre del rol se resuelve contra `GET /v1/roles` **en el cliente**: la API
 * devuelve claves en el usuario y el catálogo aparte. Inventar aquí una tabla de
 * nombres sería una segunda fuente de verdad que se separa de la primera en cuanto
 * alguien renombre un rol.
 * ═══════════════════════════════════════════════════════════════════════════
 */
export default function UsersPage() {
  const scope = usePermissionScope("user.view");

  const [usuarios, setUsuarios] = useState<UserAdmin[] | null>(null);
  const [roles, setRoles] = useState<RoleAdmin[] | null>(null);
  const [total, setTotal] = useState(0);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sinPermiso, setSinPermiso] = useState(false);
  const [busqueda, setBusqueda] = useState("");
  const [soloActivos, setSoloActivos] = useState(false);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    setSinPermiso(false);
    try {
      const [pagina, paginaRoles] = await Promise.all([
        fetchUsers({
          ...(busqueda.trim() ? { q: busqueda.trim() } : {}),
          ...(soloActivos ? { active: true } : {}),
        }),
        // El catálogo de roles se pide aparte y puede fallar por permiso distinto
        // (`role.manage`). Si falla, el listado de usuarios sigue sirviendo: los
        // roles se muestran con su clave, que es lo que devuelve este endpoint.
        fetchRoles().catch(() => null),
      ]);
      setUsuarios(pagina.items);
      setRoles(paginaRoles?.items ?? null);
      setTotal(pagina.totalItems);
    } catch (e) {
      setUsuarios(null);
      setSinPermiso(e instanceof ApiError && e.status === 403);
      setError(e instanceof Error ? e.message : "No se pudieron cargar los usuarios.");
    } finally {
      setCargando(false);
    }
  }, [busqueda, soloActivos]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  /** La clave del rol → su nombre visible, si el catálogo se pudo leer. */
  const nombreDeRol = (clave: string): string =>
    roles?.find((r) => r.key === clave)?.name ?? clave;

  return (
    <PageShell
      title="Usuarios"
      description="Quién tiene acceso, con qué roles y cuándo entró por última vez."
      breadcrumbLabel="Configuración"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="user.view" scope={scope} />
      </p>

      {/*
        La ausencia se dice, y en el mismo sitio donde estaría el botón. Es lo que
        la maqueta honesta hacía bien y hay que conservar.
      */}
      <section
        style={{
          background: "#FFF6E5",
          borderLeft: "6px solid #F28C28",
          padding: 16,
          margin: "16px 0",
        }}
      >
        <strong>Esta pantalla es de consulta.</strong> El alta de usuarios, la edición, el
        restablecimiento de contraseña y la asignación de roles <strong>se hacen por API</strong>:
        la ruta de lectura está publicada y las de escritura todavía no. No hay formulario porque
        un formulario que devuelve <code>404</code> al guardar es peor que no tenerlo.
      </section>

      <div style={{ display: "flex", gap: 12, alignItems: "center", margin: "16px 0" }}>
        <label style={{ display: "flex", flexDirection: "column" }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Buscar</span>
          <input
            type="text"
            aria-label="Buscar usuario"
            value={busqueda}
            onChange={(e) => setBusqueda(e.target.value)}
            placeholder="usuario o nombre"
          />
        </label>
        <label style={{ display: "flex", gap: 6, alignItems: "center", alignSelf: "end" }}>
          <input
            type="checkbox"
            checked={soloActivos}
            onChange={(e) => setSoloActivos(e.target.checked)}
          />
          Solo activos
        </label>
        {!cargando && !error && <span style={{ alignSelf: "end", color: "#4A5A72" }}>{total} usuario(s)</span>}
      </div>

      {cargando && <p style={{ color: "#4A5A72" }}>Cargando usuarios…</p>}

      {!cargando && error && (
        <div
          role="alert"
          style={{ background: "#FDECEA", borderLeft: "6px solid #C0392B", padding: 16, margin: "16px 0" }}
        >
          <div style={{ marginBottom: 8 }}>{error}</div>
          {sinPermiso && (
            <div style={{ marginBottom: 8 }}>
              Esta pantalla exige el permiso <code>user.view</code>. Pídeselo a un
              administrador.
            </div>
          )}
          <button type="button" onClick={() => void cargar()}>
            Reintentar
          </button>
        </div>
      )}

      {!cargando && !error && usuarios && usuarios.length === 0 && (
        <p style={{ color: "#4A5A72" }}>No hay usuarios que cumplan el filtro.</p>
      )}

      {!cargando && !error && usuarios && usuarios.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
              <th style={{ padding: 10 }}>Usuario</th>
              <th style={{ padding: 10 }}>Nombre</th>
              <th style={{ padding: 10 }}>Roles</th>
              <th style={{ padding: 10 }}>Estado</th>
              <th style={{ padding: 10 }}>Último acceso</th>
            </tr>
          </thead>
          <tbody>
            {usuarios.map((u) => (
              <tr key={u.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                <td style={{ padding: 10 }}>{u.username}</td>
                <td style={{ padding: 10 }}>{u.name}</td>
                <td style={{ padding: 10 }}>
                  {u.roles.length === 0 ? (
                    // **Un usuario sin rol no puede hacer nada**, y decirlo es más
                    // útil que una celda vacía que parece un dato que no cargó.
                    <span style={{ color: "#8A94A6" }}>sin rol</span>
                  ) : (
                    u.roles.map((clave) => (
                      <span
                        key={clave}
                        style={{
                          display: "inline-block",
                          background: "#E4EBF5",
                          color: "#16315F",
                          padding: "2px 8px",
                          marginRight: 4,
                          fontSize: 13,
                        }}
                      >
                        {nombreDeRol(clave)}
                      </span>
                    ))
                  )}
                </td>
                <td style={{ padding: 10, color: u.active ? "#2E7D5B" : "#C0392B", fontWeight: 600 }}>
                  {u.active ? "Activo" : "Desactivado"}
                </td>
                <td style={{ padding: 10 }}>
                  {u.lastLoginAt ? (
                    u.lastLoginAt.replace("T", " ").slice(0, 19)
                  ) : (
                    // Un usuario que nunca ha entrado es un dato de gestión, no un
                    // hueco: puede ser un alta olvidada.
                    <span style={{ color: "#8A94A6" }}>nunca</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/*
        ── LOS ROLES, CON SUS CONTADORES ──────────────────────────────────────
        Es lo que un listado de claves no enseña: un rol sin usuarios se puede
        retirar, y uno con muchísimos permisos es el que hay que revisar.
      */}
      {!cargando && roles && roles.length > 0 && (
        <>
          <h2 style={{ fontSize: 17, color: "#16315F", marginTop: 32 }}>
            Roles ({roles.length})
          </h2>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
                <th style={{ padding: 10 }}>Rol</th>
                <th style={{ padding: 10 }}>Descripción</th>
                <th style={{ padding: 10, textAlign: "right" }}>Permisos</th>
                <th style={{ padding: 10, textAlign: "right" }}>Usuarios</th>
                <th style={{ padding: 10 }}>Sistema</th>
              </tr>
            </thead>
            <tbody>
              {roles.map((r) => (
                <tr key={r.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                  <td style={{ padding: 10 }}>
                    {r.name}
                    <div style={{ color: "#8A94A6", fontSize: 13 }}>{r.key}</div>
                  </td>
                  <td style={{ padding: 10 }}>{r.description ?? "—"}</td>
                  <td
                    style={{
                      padding: 10,
                      textAlign: "right",
                      // Muchos permisos en un solo rol es lo que hay que mirar.
                      color: r.permissionCount > 40 ? "#F28C28" : "#4A5A72",
                      fontWeight: r.permissionCount > 40 ? 600 : 400,
                    }}
                  >
                    {r.permissionCount}
                  </td>
                  <td style={{ padding: 10, textAlign: "right" }}>
                    {r.userCount === 0 ? (
                      <span style={{ color: "#8A94A6" }}>0 · sin usar</span>
                    ) : (
                      r.userCount
                    )}
                  </td>
                  <td style={{ padding: 10 }}>{r.isSystem ? "Sí" : "No"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </PageShell>
  );
}
