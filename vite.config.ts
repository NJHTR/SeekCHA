import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 4174,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8001",
        configure(proxy) {
          proxy.on("proxyReq", (proxyRequest, request) => {
            if (request.headers.host) proxyRequest.setHeader("X-Forwarded-Host", request.headers.host);
            proxyRequest.setHeader("X-Forwarded-Proto", "http");
          });
        },
      },
    },
  },
});
