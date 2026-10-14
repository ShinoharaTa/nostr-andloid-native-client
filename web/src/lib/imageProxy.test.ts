import { afterEach, expect, it } from "vitest";
import {
  EMOJI_PROXY_HEIGHT,
  markProxyBlocked,
  originOf,
  proxied,
  proxiedEmoji,
  setDataSaver,
} from "./imageProxy";

afterEach(() => {
  setDataSaver(false);
});

it("wsrv.nl の URL を幅・webp・品質・we 付きで作り、animated なら n=-1 を足す", () => {
  expect(proxied("https://example.com/a b.png?x=1&y=2", 96)).toBe(
    "https://wsrv.nl/?url=https%3A%2F%2Fexample.com%2Fa%20b.png%3Fx%3D1%26y%3D2&w=96&output=webp&q=75&we",
  );
  expect(proxied("https://example.com/a.gif", 300, 80, true)).toBe(
    "https://wsrv.nl/?url=https%3A%2F%2Fexample.com%2Fa.gif&w=300&output=webp&q=80&we&n=-1",
  );
});

it("前後の空白を trim してから通し、空白だけなら空文字を返す", () => {
  expect(proxied("  https://yabu.me/icon.png\n")).toBe(proxied("https://yabu.me/icon.png"));
  expect(proxied(" \t")).toBe("");
});

it("拒否を学習したホストは元 URL を返し、別ホストは引き続きプロキシを通す", () => {
  const src = "https://blockme.testonly.cc/a.webp";
  expect(proxied(src).startsWith("https://wsrv.nl/?url=")).toBe(true);

  markProxyBlocked(src);

  expect(proxied(src)).toBe(src);
  expect(proxied("https://BlockMe.testonly.cc:443/other/b.png")).toBe(
    "https://BlockMe.testonly.cc:443/other/b.png",
  );
  expect(proxied("https://ok.testonly.org/c.jpg").startsWith("https://wsrv.nl/?url=")).toBe(true);
});

it("データセーバー中は幅を 2/3・品質を最大 60 に落とす", () => {
  setDataSaver(true);
  expect(proxied("https://example.com/a.png", 96)).toContain("&w=64&output=webp&q=60&we");
  expect(proxied("https://example.com/a.png", 600, 50)).toContain("&w=400&output=webp&q=50&we");
});

it("originOf はプロキシ URL から元 URL を復元し、それ以外は null", () => {
  const src = "https://cdn.example.com/i?id=42&w=1";
  expect(originOf(proxied(src, 300, 80, true))).toBe(src);
  expect(originOf("https://dev.sabotenism.cc/img/ruri.webp")).toBeNull();
  expect(originOf(null)).toBeNull();
  expect(originOf(42)).toBeNull();
});

it("height を渡すと w= の代わりに h= で縮める（カスタム絵文字。#862）", () => {
  const src = "https://example.com/wide.webp";
  const byHeight = proxied(src, 600, 80, true, 96);
  expect(byHeight).toBe(
    "https://wsrv.nl/?url=https%3A%2F%2Fexample.com%2Fwide.webp&h=96&output=webp&q=80&we&n=-1",
  );
  expect(byHeight).not.toContain("&w=");
  expect(originOf(byHeight)).toBe(src);
  expect(proxiedEmoji(src)).toBe(proxied(src, 600, 80, true, EMOJI_PROXY_HEIGHT));
  expect(proxiedEmoji(src, 75)).toContain(`&h=${EMOJI_PROXY_HEIGHT}&output=webp&q=75&we&n=-1`);
});

it("データセーバー中は h= も 2/3 に落とす", () => {
  setDataSaver(true);
  const src = "https://cdn.example.com/e?id=1&h=2";
  const url = proxiedEmoji(src);
  expect(url).toContain("&h=64&output=webp&q=60&we&n=-1");
  expect(originOf(url)).toBe(src);
});
