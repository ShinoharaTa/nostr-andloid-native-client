/// <reference types="vite-plugin-pwa/react" />

/** ビルド時のアプリバージョン（vite.config.ts / vitest.config.ts の define。lib/appVersion.ts から使う） */
declare const __APP_VERSION__: string | null;
