import fs from "node:fs";
import path from "node:path";

/**
 * What the browser tests need to know about the environment.
 *
 * The credentials are read from the repository's `.env` —the same one that the
 * `docker compose`— and they are not written here: a versioned file with
 * password is a leaked password.
 */
const readEnvFile = (): Record<string, string> => {
  // The configuration is loaded as ESM (`"type": "module"`), so there is no
  // `__dirname`: the path comes from `import.meta`.
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
  // A test password of its own. `ADMIN_BOOTSTRAP_PASSWORD` only serves to create
  // the first administrator, and the API requires changing it on entry: a test that used it
  // as the password in force would break as soon as someone changed it.
  adminPassword: pick("E2E_ADMIN_PASSWORD", pick("ADMIN_BOOTSTRAP_PASSWORD", "")),
};
