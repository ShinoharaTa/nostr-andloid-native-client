import { afterEach, describe, expect, it, vi } from "vitest";
import {
  DEFAULT_NOSTRCONNECT_RELAY,
  NOSTRCONNECT_RELAY_KEY,
  NOSTRCONNECT_RELAY_PRESETS,
  nostrConnectRelayChoices,
  parseNostrConnectRelay,
  resetNostrConnectRelayForTest,
  setNostrConnectRelay,
  useNostrConnectRelay,
} from "./nip46RelayPref";

afterEach(() => {
  resetNostrConnectRelayForTest();
  localStorage.clear();
});

describe("parseNostrConnectRelay", () => {
  it("wss:// の URL だけ（前後の空白を除き、正規化して末尾の / を落とす）", () => {
    expect(parseNostrConnectRelay("  wss://Relay.Example/ ")).toBe("wss://relay.example");
    expect(parseNostrConnectRelay("wss://relay.example/path/")).toBe("wss://relay.example/path");
    expect(parseNostrConnectRelay("ws://relay.example")).toBeNull();
    expect(parseNostrConnectRelay("https://relay.example")).toBeNull();
    expect(parseNostrConnectRelay("relay.example")).toBeNull();
    expect(parseNostrConnectRelay("wss://")).toBeNull();
    expect(parseNostrConnectRelay("")).toBeNull();
  });
});

describe("中継リレーの設定", () => {
  it("既定は wss://relay.nsec.app。候補は relay.nsec.app / nos.lol", () => {
    expect(DEFAULT_NOSTRCONNECT_RELAY).toBe("wss://relay.nsec.app");
    expect(useNostrConnectRelay.getState().relay).toBe(DEFAULT_NOSTRCONNECT_RELAY);
    expect(NOSTRCONNECT_RELAY_PRESETS).toEqual(["wss://relay.nsec.app", "wss://nos.lol"]);
  });

  it("変えると localStorage に残る", () => {
    expect(setNostrConnectRelay("wss://nos.lol/")).toBe(true);
    expect(useNostrConnectRelay.getState().relay).toBe("wss://nos.lol");
    expect(localStorage.getItem(NOSTRCONNECT_RELAY_KEY)).toBe("wss://nos.lol");
  });

  it("wss:// でなければ保存せず false", () => {
    setNostrConnectRelay("wss://nos.lol");
    for (const input of ["ws://relay.example", "https://relay.example", "relay.example", " "]) {
      expect(setNostrConnectRelay(input)).toBe(false);
    }
    expect(useNostrConnectRelay.getState().relay).toBe("wss://nos.lol");
    expect(localStorage.getItem(NOSTRCONNECT_RELAY_KEY)).toBe("wss://nos.lol");
  });

  it("起動時に保存値を読む。読めない値は既定", async () => {
    localStorage.setItem(NOSTRCONNECT_RELAY_KEY, "wss://relay.example");
    vi.resetModules();
    expect((await import("./nip46RelayPref")).useNostrConnectRelay.getState().relay).toBe(
      "wss://relay.example",
    );

    localStorage.setItem(NOSTRCONNECT_RELAY_KEY, "ws://relay.example");
    vi.resetModules();
    expect((await import("./nip46RelayPref")).useNostrConnectRelay.getState().relay).toBe(
      DEFAULT_NOSTRCONNECT_RELAY,
    );
  });

  it("選べるのは候補 + 手入力したもの（末尾）", () => {
    expect(nostrConnectRelayChoices("wss://nos.lol")).toEqual(["wss://relay.nsec.app", "wss://nos.lol"]);
    expect(nostrConnectRelayChoices("wss://relay.example")).toEqual([
      "wss://relay.nsec.app",
      "wss://nos.lol",
      "wss://relay.example",
    ]);
  });
});
