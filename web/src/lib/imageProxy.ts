/**
 * 画像 URL をリサイズ/圧縮プロキシ（wsrv.nl）に通す（ネイティブの ImageProxy.kt の移植）。
 * 幅制限 + webp + 品質指定で転送量を抑え、同じ URL をブラウザのキャッシュに効かせる。
 */
const HOST = "https://wsrv.nl/";

/**
 * wsrv.nl が拒否したホスト（実行時に学習）。以後は元 URL を直接使い、毎回の失敗往復を避ける。
 */
const blockedHosts = new Set<string>();

type NetworkInformationLike = EventTarget & { saveData?: boolean };

function networkInformation(): NetworkInformationLike | undefined {
  // navigator.connection は Chromium 系のみ（lib.dom に型が無い）
  return (navigator as Navigator & { connection?: NetworkInformationLike }).connection;
}

/**
 * 通信量を抑えるモード（ブラウザの「データセーバー」= navigator.connection.saveData）。
 * true の間は幅を 2/3・品質を最大 60 に落とす（ネイティブの #359 と同じ係数）。
 */
let dataSaver = networkInformation()?.saveData === true;
networkInformation()?.addEventListener("change", () => {
  dataSaver = networkInformation()?.saveData === true;
});

/** データセーバーを明示的に切り替える（saveData の変化でも上書きされる） */
export function setDataSaver(on: boolean) {
  dataSaver = on;
}

/** データセーバー中か（サムネのアニメーションを止める判定に使う） */
export function isDataSaver(): boolean {
  return dataSaver;
}

/**
 * width px 幅・webp・品質 quality に圧縮した URL を返す。
 * animated = true なら n=-1 で全フレームを保持する（wsrv.nl は既定で先頭 1 フレームのみ返す）。
 * height を渡すと幅ではなく高さで縮める（カスタム絵文字。横長の絵文字を幅で縮めると高さが潰れるため。#862）。
 * 既にプロキシ拒否と分かっているホストは、プロキシを介さず元 URL を返す。
 */
export function proxied(url: string, width = 600, quality = 75, animated = false, height?: number): string {
  // kind:0 の picture には前後空白が混ざることがある（例: yabu.me のアイコン）。必ず trim する
  const clean = url.trim();
  if (clean === "") return clean;
  const host = hostOf(clean);
  if (host && blockedHosts.has(host)) return clean;
  // 縮める基準（幅 w= か高さ h=）。縦横比は保たれる
  const size =
    height !== undefined
      ? `h=${dataSaver ? Math.floor((height * 2) / 3) : height}`
      : `w=${dataSaver ? Math.floor((width * 2) / 3) : width}`;
  const q = dataSaver ? Math.min(quality, 60) : quality;
  // we = 拡大しない（元が小さければそのまま）
  const frames = animated ? "&n=-1" : "";
  return `${HOST}?url=${encodeParameter(clean)}&${size}&output=webp&q=${q}&we${frames}`;
}

/** カスタム絵文字をプロキシに縮めさせるときの高さ（px。ネイティブの EMOJI_PROXY_HEIGHT_PX と同じ） */
export const EMOJI_PROXY_HEIGHT = 96;

/**
 * カスタム絵文字の URL（高さ基準・アニメーション保持）。表示は高さ固定・幅は縦横比どおり（#862）。
 */
export function proxiedEmoji(url: string, quality = 80): string {
  return proxied(url, 600, quality, true, EMOJI_PROXY_HEIGHT);
}

/**
 * proxied() が生成した URL から元画像 URL を復元する（プロキシ URL でなければ null）。
 * プロキシが読み込めなかったときの元 URL 直取得のフォールバックに使う。
 */
export function originOf(data: unknown): string | null {
  if (typeof data !== "string" || !data.startsWith(`${HOST}?url=`)) return null;
  const encoded = data.slice(`${HOST}?url=`.length).split("&")[0];
  try {
    const origin = decodeURIComponent(encoded);
    return origin.trim() === "" ? null : origin;
  } catch {
    return null;
  }
}

/**
 * プロキシが拒否した元 URL のホストを記録する。以後 proxied() はそのホストに対して元 URL を返す。
 */
export function markProxyBlocked(originUrl: string) {
  const host = hostOf(originUrl);
  if (host) blockedHosts.add(host);
}

/** URL からホスト部（小文字）を取り出す。`scheme://host[:port]/...` 前提。失敗時 null */
function hostOf(url: string): string | null {
  const at = url.indexOf("://");
  if (at < 0) return null;
  const host = url
    .slice(at + 3)
    .split("/")[0]
    .split("?")[0]
    .split(":")[0]
    .toLowerCase();
  return host === "" ? null : host;
}

// Ktor の encodeURLParameter に合わせ、encodeURIComponent が残す !'()* も符号化する
function encodeParameter(value: string): string {
  return encodeURIComponent(value).replace(
    /[!'()*]/g,
    (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`,
  );
}
