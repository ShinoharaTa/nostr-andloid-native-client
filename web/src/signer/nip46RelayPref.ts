import { normalizeURL } from "applesauce-core/helpers/url";
import { create } from "zustand";

// ---- nostrconnect:// で署名アプリとの通信に使う中継リレー（#899） ----

/** 値は wss:// のリレーの URL（末尾の / なし）。無い・読めなければ既定 */
export const NOSTRCONNECT_RELAY_KEY = "nostrism.nip46.relay";

/** 既定の中継リレー（#899 の決定。ネイティブ #886 も同じ既定にする） */
export const DEFAULT_NOSTRCONNECT_RELAY = "wss://relay.nsec.app";

/** 候補（ログイン画面で選べるもの。手入力も別にできる） */
export const NOSTRCONNECT_RELAY_PRESETS: readonly string[] = ["wss://relay.nsec.app", "wss://nos.lol"];

/**
 * 入力を中継リレーの URL（正規化して末尾の / を落とす。例: wss://relay.nsec.app）にする。
 * wss:// の URL でなければ null
 */
export function parseNostrConnectRelay(input: string): string | null {
  const value = input.trim();
  if (!value.startsWith("wss://")) return null;
  try {
    new URL(value);
    return normalizeURL(value).replace(/\/$/, "");
  } catch {
    return null;
  }
}

function readRelay(): string {
  try {
    const raw = localStorage.getItem(NOSTRCONNECT_RELAY_KEY);
    return (raw === null ? null : parseNostrConnectRelay(raw)) ?? DEFAULT_NOSTRCONNECT_RELAY;
  } catch {
    return DEFAULT_NOSTRCONNECT_RELAY;
  }
}

/** 選んだ中継リレー */
export const useNostrConnectRelay = create<{ relay: string }>()(() => ({ relay: readRelay() }));

/** 中継リレーを変える。wss:// の URL でなければ保存せず false */
export function setNostrConnectRelay(input: string): boolean {
  const relay = parseNostrConnectRelay(input);
  if (relay === null) return false;
  useNostrConnectRelay.setState({ relay });
  try {
    localStorage.setItem(NOSTRCONNECT_RELAY_KEY, relay);
  } catch {
    // 保存できなくてもこのセッションでは効く
  }
  return true;
}

/** 選べる中継リレー（候補 + 選んだもの。候補に無いもの（手入力）は末尾に足す） */
export function nostrConnectRelayChoices(relay: string): string[] {
  const list = [...NOSTRCONNECT_RELAY_PRESETS];
  if (!list.includes(relay)) list.push(relay);
  return list;
}

/** テスト専用: 既定へ戻す（localStorage は呼び元で消す） */
export function resetNostrConnectRelayForTest(): void {
  useNostrConnectRelay.setState({ relay: DEFAULT_NOSTRCONNECT_RELAY });
}
