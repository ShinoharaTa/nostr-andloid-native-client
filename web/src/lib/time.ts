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
 * 投稿の日時（ネイティブの DateFormat.kt と同じ `yyyy/MM/dd HH:mm`。端末のタイムゾーン・24 時間制）。
 * Date の範囲外なら ""。
 */
export function formatAbsoluteTime(unixSeconds: number): string {
  const date = new Date(unixSeconds * 1000);
  if (!Number.isFinite(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
