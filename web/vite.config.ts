import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";
import { defineConfig } from "vite";

/**
 * Servidor de desarrollo con **proxy a la API**, no llamadas cruzadas.
 *
 * El navegador pide `/v1/...` al mismo origen (5173) y Vite lo reenvía a
 * `127.0.0.1:8081`. Así la web no necesita CORS, y en producción nginx hace
 * exactamente el mismo trabajo: el cliente siempre habla con rutas relativas.
 */
export default defineConfig({
  plugins: [tailwindcss(), react()],
  resolve: {
    alias: {
      "@app": path.resolve(__dirname, "./src/app"),
      "@shared": path.resolve(__dirname, "./src/shared"),
      "@entities": path.resolve(__dirname, "./src/entities"),
      "@features": path.resolve(__dirname, "./src/features"),
      "@widgets": path.resolve(__dirname, "./src/widgets"),
      "@pages": path.resolve(__dirname, "./src/pages"),
    },
  },
  server: {
    proxy: {
      "/v1": {
        target: process.env.STOCK_API_URL ?? "http://127.0.0.1:8081",
        changeOrigin: false,
      },
    },
  },
});
