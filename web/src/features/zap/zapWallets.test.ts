import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { ZAP_WALLETS } from "./zapWallets";

/**
 * ネイティブ（composeApp の ZapWallet.kt。#993）の一覧。enum の項目 `NAME("id", "表示名", "prefix")` を読む。
 * #993（PR #997）が main に入るまではファイルが無いので、そのあいだだけ比べない。
 */
const NATIVE_PATH = resolve(
  process.cwd(),
  "../composeApp/src/commonMain/kotlin/app/nostrdeck/wallet/ZapWallet.kt",
);
const hasNative = existsSync(NATIVE_PATH);
const NATIVE = hasNative
  ? [
      ...readFileSync(NATIVE_PATH, "utf8").matchAll(/^\s+[A-Z_]+\("([^"]+)", "([^"]*)", "([^"]+)"\)[,;]/gm),
    ].map((m) => ({ id: m[1], name: m[2], prefix: m[3] }))
  : [];

describe("[#994] Zap のウォレットの一覧", () => {
  it.skipIf(!hasNative)("ネイティブ ZapWallet と同じ並び・同じ URL（端末の既定はネイティブだけ）", () => {
    expect(NATIVE[0]).toEqual({ id: "system", name: "", prefix: "lightning:" });
    expect(ZAP_WALLETS).toEqual(NATIVE.slice(1));
  });

  it("id は重ならず、URL は請求書をそのまま付けられる形（: か / で終わる）", () => {
    expect(new Set(ZAP_WALLETS.map((w) => w.id)).size).toBe(ZAP_WALLETS.length);
    for (const w of ZAP_WALLETS) expect(w.prefix).toMatch(/[:/]$/);
  });
});
