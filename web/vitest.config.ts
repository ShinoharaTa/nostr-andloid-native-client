import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import { defineConfig } from "vitest/config";

// アプリ（src/）のテストは jsdom で走らせる。
// Pages Functions のテスト（test/functions/、workerd で走る）は vitest.functions.config.ts（npm run test:functions）。
// VitePWA は `virtual:pwa-register/react` を解決するためだけに入れる（テストでは何もしない dev 用スタブになる）。
export default defineConfig({
  plugins: [react(), VitePWA({ injectRegister: null })],
  // lib/appVersion.ts の APP_VERSION。テストは git を見ない固定値（値に依存するテストは vi.mock で差し替える）
  define: { __APP_VERSION__: JSON.stringify("0.0.0-test") },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./src/test/setup.ts"],
  },
});
