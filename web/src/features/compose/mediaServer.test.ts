import { afterEach, describe, expect, it, vi } from "vitest";
import {
  DEFAULT_MEDIA_SERVERS,
  MEDIA_PRESETS,
  MEDIA_SERVER_KEY,
  parseServerInput,
  parseStoredServer,
  serverChoices,
  serverName,
  setMediaServer,
  uploadServers,
  useMediaServer,
} from "./mediaServer";

afterEach(() => {
  setMediaServer(null);
  localStorage.clear();
});

describe("parseServerInput", () => {
  it("https の URL だけ（前後の空白と末尾の / を除く）", () => {
    expect(parseServerInput("  https://nostr.build/ ")).toBe("https://nostr.build");
    expect(parseServerInput("https://example.com/media//")).toBe("https://example.com/media");
    expect(parseServerInput("http://nostr.build")).toBeNull();
    expect(parseServerInput("nostr.build")).toBeNull();
    expect(parseServerInput("")).toBeNull();
  });
});

describe("アップロード先", () => {
  it("未選択なら既定の一覧（nostr.build → nostrcheck.me）、選べばそれだけ。localStorage に方式ごと残る", () => {
    expect(useMediaServer.getState().server).toBeNull();
    expect(uploadServers(null)).toEqual([
      { url: "https://nostr.build", protocol: "nip96" },
      { url: "https://nostrcheck.me", protocol: "nip96" },
    ]);
    expect(uploadServers(null)).toEqual([...DEFAULT_MEDIA_SERVERS]);

    const band = { url: "https://blossom.band", protocol: "blossom" } as const;
    setMediaServer(band);
    expect(useMediaServer.getState().server).toEqual(band);
    expect(JSON.parse(localStorage.getItem(MEDIA_SERVER_KEY) ?? "")).toEqual(band);
    expect(uploadServers(band)).toEqual([band]);

    setMediaServer(null);
    expect(localStorage.getItem(MEDIA_SERVER_KEY)).toBeNull();
  });
});

describe("[#873] 保存形式", () => {
  it("以前の形（URL の文字列）は NIP-96 として読む", () => {
    expect(parseStoredServer("https://nostpic.com")).toEqual({
      url: "https://nostpic.com",
      protocol: "nip96",
    });
    expect(parseStoredServer("http://nostpic.com")).toBeNull();
  });

  it("JSON は方式ごと読む。方式が無い・知らない値は NIP-96", () => {
    expect(parseStoredServer('{"url":"https://blossom.band","protocol":"blossom"}')).toEqual({
      url: "https://blossom.band",
      protocol: "blossom",
    });
    expect(parseStoredServer('{"url":"https://nostpic.com"}')).toEqual({
      url: "https://nostpic.com",
      protocol: "nip96",
    });
    expect(parseStoredServer('{"url":"https://x.example","protocol":"something-new"}')?.protocol).toBe(
      "nip96",
    );
    expect(parseStoredServer('{"protocol":"blossom"}')).toBeNull();
    expect(parseStoredServer("null")).toBeNull();
  });

  it("起動時に以前の形の保存値を NIP-96 の送信先として読み込む", async () => {
    localStorage.setItem(MEDIA_SERVER_KEY, "https://nostpic.com");
    vi.resetModules();
    const fresh = await import("./mediaServer");
    expect(fresh.useMediaServer.getState().server).toEqual({ url: "https://nostpic.com", protocol: "nip96" });
  });
});

describe("[#873] 候補", () => {
  it("ネイティブ MEDIA_PRESETS と同じ顔ぶれ・並び（既定の 2 つも含む）", () => {
    expect(MEDIA_PRESETS.filter((s) => s.protocol === "nip96").map((s) => s.url)).toEqual([
      "https://share.yabu.me",
      "https://nostrcheck.me",
      "https://nostr.build",
      "https://nostpic.com",
      "https://files.sovbit.host",
      "https://nostrmedia.com",
    ]);
    expect(MEDIA_PRESETS.filter((s) => s.protocol === "blossom").map((s) => s.url)).toEqual([
      "https://cdn.nostrcheck.me",
      "https://blossom.band",
      "https://nostr.download",
      "https://blossom.primal.net",
    ]);
    for (const d of DEFAULT_MEDIA_SERVERS) expect(MEDIA_PRESETS).toContainEqual(d);
  });

  it("候補に無い送信先は末尾に足す（同じ URL でも方式が違えば別）", () => {
    expect(serverChoices(null)).toEqual([...MEDIA_PRESETS]);
    expect(serverChoices({ url: "https://nostr.build", protocol: "nip96" })).toEqual([...MEDIA_PRESETS]);
    const custom = { url: "https://nostr.build", protocol: "blossom" } as const;
    expect(serverChoices(custom)).toEqual([...MEDIA_PRESETS, custom]);
  });

  it("表示名はスキームと末尾の / を落とす", () => {
    expect(serverName("https://share.yabu.me/")).toBe("share.yabu.me");
    expect(serverName("https://nostr.build/api/v2")).toBe("nostr.build/api/v2");
  });
});
