import fs from "node:fs";
import path from "node:path";

/**
 * Lo que las pruebas de navegador necesitan saber del entorno.
 *
 * Las credenciales se leen del `.env` del repositorio —el mismo que usa
 * `docker compose`— y no se escriben aquí: un archivo versionado con una
 * contraseña es una contraseña filtrada.
 */
const readEnvFile = (): Record<string, string> => {
  // La configuración se carga como ESM (`"type": "module"`), así que no hay
  // `__dirname`: la ruta sale de `import.meta`.
  const file = path.resolve(import.meta.dirname, "../../../.env");
  if (!fs.existsSync(file)) return {};

  return Object.fromEntries(
    fs
      .readFileSync(file, "utf8")
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line !== "" && !line.startsWith("#") && line.includes("="))
      .map((line) => {
        const index = line.indexOf("=");
        return [line.slice(0, index).trim(), line.slice(index + 1).trim().replace(/^["']|["']$/g, "")];
      }),
  );
};

const file = readEnvFile();
const pick = (name: string, fallback: string): string => process.env[name] ?? file[name] ?? fallback;

export const E2E = {
  webUrl: pick("E2E_WEB_URL", "http://127.0.0.1:5173"),
  apiUrl: pick("E2E_API_URL", "http://127.0.0.1:8081"),
  adminUser: pick("STOCK_ADMIN_USERNAME", "admin"),
  // Una contraseña de prueba propia. `ADMIN_BOOTSTRAP_PASSWORD` solo sirve para crear
  // el primer administrador, y la API exige cambiarla al entrar: una prueba que la usara
  // como contraseña vigente se rompería en cuanto alguien la cambiara.
  adminPassword: pick("E2E_ADMIN_PASSWORD", pick("ADMIN_BOOTSTRAP_PASSWORD", "")),
};
