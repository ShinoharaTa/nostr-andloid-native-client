/** 現在の UNIX 時刻（秒） */
export function unixNow(): number {
  return Math.floor(Date.now() / 1000);
}

/**
 * 投稿の相対時刻（ネイティブの TimeLabels.kt の relative と同じ表記）。
 * 10 秒未満 = now、以降 秒 s / 分 m / 時間 h / 日 d / 週 w（端数は切り捨て）。未来の時刻も now。
 */
export function relativeTime(createdAt: number, now = unixNow()): string {
  const diff = now - createdAt;
  if (diff < 10) return "now";
  if (diff < 60) return `${diff}s`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h`;
  if (diff < 604800) return `${Math.floor(diff / 86400)}d`;
  return `${Math.floor(diff / 604800)}w`;
}

/**
 * 投稿の日時（ネイティブの DateFormat.kt と同じ `yyyy/MM/dd HH:mm`。秒ありは `yyyy/MM/dd HH:mm:ss`。
 * 端末のタイムゾーン・24 時間制）。Date の範囲外なら ""。
 */
export function formatAbsoluteTime(unixSeconds: number, withSeconds = false): string {
  const date = new Date(unixSeconds * 1000);
  if (!Number.isFinite(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  const seconds = withSeconds ? `:${pad(date.getSeconds())}` : "";
  return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}${seconds}`;
}

/** [#940] 時刻の表示（設定 > 表示。ネイティブ nostr-core Appearance.kt の TimeDisplay）。既定は relative */
export type TimeDisplay = "relative" | "absolute" | "absolute_seconds";

/**
 * [#940] 設定に従った時刻の表記（ネイティブ TimeLabels.label と同じ）。relative は relativeTime。
 * 日時は 1 行のヘッダに収まるよう、今日なら時刻だけ（`20:44` / `20:44:01`）、今年なら月日と時刻
 * （`10/23 20:44`。月日はゼロ埋めなし）、それより前は年月日と時刻にする。今日・今年の判定は
 * 端末のタイムゾーンでの年月日の比較。
 */
export function timeLabel(createdAt: number, now: number, mode: TimeDisplay): string {
  if (mode === "relative") return relativeTime(createdAt, now);
  const full = formatAbsoluteTime(createdAt, mode === "absolute_seconds");
  const date = new Date(createdAt * 1000);
  const today = new Date(now * 1000);
  if (full === "" || !Number.isFinite(today.getTime())) return full;
  const time = full.slice(full.indexOf(" ") + 1);
  if (date.getFullYear() !== today.getFullYear()) return full;
  if (date.getMonth() === today.getMonth() && date.getDate() === today.getDate()) return time;
  return `${date.getMonth() + 1}/${date.getDate()} ${time}`;
}
