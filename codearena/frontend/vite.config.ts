import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// The dev server proxies /api to the gateway, so the browser sees one origin (no CORS, cookies just work).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: { "/api": { target: "http://localhost:9080", changeOrigin: false } },
  },
  build: {
    chunkSizeWarningLimit: 4000, // Monaco is large; it is only loaded on the problem page
  },
});