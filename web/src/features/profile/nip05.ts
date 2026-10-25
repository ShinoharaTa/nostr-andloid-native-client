import { useEffect, useState } from "react";

/**
 * NIP-05 の検証（ネイティブ EventRepository.verifyNip05）。
 * verified = 一致、invalid = 不一致・names に無い・書式不正、unverified = 取得できなかった（通信・CORS・
 * リダイレクト・非 2xx・JSON 不正）。ブラウザでは CORS 未対応のサーバーが多いので、取得失敗は「エラー」にしない。
 */
export type Nip05Status = "verified" | "invalid" | "unverified";

/** ネイティブの HTTP の接続タイムアウトと同じ */
export const NIP05_TIMEOUT_MS = 15_000;
/** 結果を覚えておく件数（超えたら古いものから消す） */
export const NIP05_CACHE_MAX = 500;

// パス・ポート・userinfo を含む値で別の URL を叩かないよう、ドメインは英数字とハイフンのラベルだけ
const DOMAIN = /^(?=.{1,253}$)[a-z0-9-]+(\.[a-z0-9-]+)+$/;

export type Nip05Address = { local: string; domain: string };

/** "name@domain"（@ 無しは "_@domain"）を分ける。domain は小文字。書式が不正なら null */
export function parseNip05(value: string): Nip05Address | null {
  const trimmed = value.trim();
  if (trimmed === "") return null;
  const at = trimmed.indexOf("@");
  const local = at >= 0 ? trimmed.slice(0, at) : "_";
  const domain = (at >= 0 ? trimmed.slice(at + 1) : trimmed).toLowerCase();
  if (local === "" || !DOMAIN.test(domain)) return null;
  return { local, domain };
}

/**
 * [#939] NIP-05 の表示（ネイティブ Nip05Display.label）。ローカル部が `_` の識別子（`_@domain`）はそのドメインの
 * 「ルート」の識別子で、ドメインだけを表示してよい（NIP-05）。表示だけを変え、検証（照会）とプロフィールの値は元のまま使う。
 * 照会できない形（ドメインに `.` が無い・`@` が 2 つ・空白を含む）は畳まずにそのまま出す。
 */
export function nip05Label(nip05: string): string {
  const value = nip05.trim();
  const at = value.indexOf("@");
  if (at < 0) return value;
  const local = value.slice(0, at);
  const domain = value.slice(at + 1);
  const queryable = domain.includes(".") && !domain.includes("@") && !/\s/.test(domain);
  return local === "_" && queryable ? domain.toLowerCase() : value;
}

export function nip05Url(p: Nip05Address): string {
  return `https://${p.domain}/.well-known/nostr.json?name=${encodeURIComponent(p.local)}`;
}

const cache = new Map<string, Promise<Nip05Status>>();

/**
 * nip05 が pubkey を指しているか。同じ pubkey + nip05 は 2 回目以降取りに行かない（結果を使い回す）。
 */
export function verifyNip05(
  pubkey: string,
  nip05: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Nip05Status> {
  const key = `${pubkey}\n${nip05}`;
  const cached = cache.get(key);
  if (cached) return cached;
  const result = fetchNip05Status(pubkey, nip05, fetchImpl);
  cache.set(key, result);
  if (cache.size > NIP05_CACHE_MAX) {
    const oldest = cache.keys().next().value;
    if (oldest !== undefined) cache.delete(oldest);
  }
  return result;
}

async function fetchNip05Status(
  pubkey: string,
  nip05: string,
  fetchImpl: typeof fetch,
): Promise<Nip05Status> {
  const address = parseNip05(nip05);
  if (!address) return "invalid";
  let json: unknown;
  try {
    // NIP-05: リダイレクトには従わない
    const res = await fetchImpl(nip05Url(address), {
      redirect: "error",
      credentials: "omit",
      referrerPolicy: "no-referrer",
      signal: AbortSignal.timeout(NIP05_TIMEOUT_MS),
    });
    if (!res.ok) return "unverified";
    json = await res.json();
  } catch {
    return "unverified";
  }
  const names: unknown =
    typeof json === "object" && json !== null ? (json as { names?: unknown }).names : null;
  if (typeof names !== "object" || names === null) return "invalid";
  // local はそのまま引く（ネイティブと同じ）
  const value: unknown = Object.hasOwn(names, address.local)
    ? (names as Record<string, unknown>)[address.local]
    : undefined;
  return typeof value === "string" && value.toLowerCase() === pubkey.toLowerCase() ? "verified" : "invalid";
}

/**
 * プロフィールの nip05 の検証状態。nip05 が無ければ null、結果が出るまで "checking"
 * （pubkey / nip05 が変わったら前の結果は使わない）。
 */
export function useNip05Status(pubkey: string, nip05: string | null): Nip05Status | "checking" | null {
  const value = nip05 !== null && nip05.trim() !== "" ? nip05 : null;
  const key = value === null ? null : `${pubkey}\n${value}`;
  const [result, setResult] = useState<{ key: string; status: Nip05Status } | null>(null);

  useEffect(() => {
    if (value === null) return;
    let alive = true;
    verifyNip05(pubkey, value).then((status) => {
      if (alive) setResult({ key: `${pubkey}\n${value}`, status });
    });
    return () => {
      alive = false;
    };
  }, [pubkey, value]);

  if (key === null) return null;
  return result?.key === key ? result.status : "checking";
}
