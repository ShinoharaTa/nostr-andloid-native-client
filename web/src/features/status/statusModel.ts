import { getExpirationTimestamp } from "applesauce-core/helpers/expiration";
import type { NostrEvent } from "nostr-tools/pure";

/** NIP-38 User Status（addressable。d = 種類） */
export const STATUS_KIND = 30315;

/** 表示する種類（NIP-38 で定義済みの 2 つ）。ほかの d（presence 等）は扱わない */
export type StatusType = "general" | "music";

/** 期限の無いステータスを出す上限（30 日。#767 の仕様 §3.4。値はデモで確かめる） */
export const STATUS_MAX_AGE_SEC = 30 * 86400;

/** d タグの種類。完全一致だけ（`general,expiration=…` のような壊れた値は null） */
export function statusTypeOf(event: NostrEvent): StatusType | null {
  const d = event.tags.find((tag) => tag[0] === "d")?.[1];
  return d === "general" || d === "music" ? d : null;
}

/** NIP-40 の期限（unix 秒）。無い・数値でなければ null（期限無し扱い） */
export function statusExpirationOf(event: NostrEvent): number | null {
  const expiration = getExpirationTimestamp(event);
  return expiration !== undefined && Number.isFinite(expiration) ? expiration : null;
}

/**
 * カラム・プロフィールに出すステータスか（§3.4）。filter が null なら両方の種類。
 * 空（空白だけを含む）は「クリア」、期限が過ぎたものは出さない。期限が無いものは STATUS_MAX_AGE_SEC より古ければ出さない
 * （期限が未来なら古くても出す）。
 */
export function isStatusVisible(event: NostrEvent, filter: StatusType | null, now: number): boolean {
  if (event.kind !== STATUS_KIND) return false;
  const type = statusTypeOf(event);
  if (type === null || (filter !== null && type !== filter)) return false;
  if (event.content.trim() === "") return false;
  const expiration = statusExpirationOf(event);
  if (expiration !== null) return expiration > now;
  return event.created_at >= now - STATUS_MAX_AGE_SEC;
}

/** 新しい順。同時刻は id の昇順（並びを安定させる）。元の配列は変えない */
export function sortStatuses(events: readonly NostrEvent[]): NostrEvent[] {
  return [...events].sort((a, b) =>
    a.created_at !== b.created_at ? b.created_at - a.created_at : a.id < b.id ? -1 : a.id > b.id ? 1 : 0,
  );
}

/** ホスト名 → サービス名（固有名詞なので翻訳しない） */
const SERVICE_BY_HOST: Readonly<Record<string, string>> = {
  "open.spotify.com": "Spotify",
  "music.apple.com": "Apple Music",
  "music.youtube.com": "YouTube Music",
  "youtube.com": "YouTube",
  "www.youtube.com": "YouTube",
  "m.youtube.com": "YouTube",
  "youtu.be": "YouTube",
  "soundcloud.com": "SoundCloud",
};

/**
 * リンク行に出すサービス名（§3.5）。http(s) 以外（`spotify:…`・壊れた `hittps://…` 等）は null（リンクにしない）。
 * 知らないホストはホスト名（先頭の www. を外す）。
 */
export function serviceLabelOf(url: string): string | null {
  if (!/^https?:\/\//i.test(url)) return null;
  let host: string;
  try {
    host = new URL(url).hostname;
  } catch {
    return null;
  }
  if (host === "") return null;
  const known = SERVICE_BY_HOST[host];
  if (known) return known;
  if (host === "bandcamp.com" || host.endsWith(".bandcamp.com")) return "Bandcamp";
  return host.replace(/^www\./, "");
}

type Translate = (key: string, ...args: (string | number)[]) => string;

/**
 * 期限の表示（§3.2）。60 秒未満「まもなく終了」、60 分未満「残り N 分」（切り上げ）、
 * 24 時間未満「残り N 時間」（切り捨て）、それ以上は「M/D HH:mm まで」（端末のタイムゾーン）。
 */
export function expiryLabel(expiration: number, now: number, t: Translate): string {
  const left = expiration - now;
  if (left < 60) return t("web_status_expires_soon");
  if (left < 3600) return t("web_status_expires_min", Math.ceil(left / 60));
  if (left < 86400) return t("web_status_expires_hour", Math.floor(left / 3600));
  const date = new Date(expiration * 1000);
  const pad = (n: number) => String(n).padStart(2, "0");
  const at = `${date.getMonth() + 1}/${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return t("web_status_expires_at", at);
}
