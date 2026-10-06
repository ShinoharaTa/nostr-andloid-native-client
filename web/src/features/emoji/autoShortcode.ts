/** ピッカーで作った絵文字の自動のショートコードの頭（#768） */
export const AUTO_SHORTCODE_PREFIX = "nostrism_";

/**
 * ピッカーで作った絵文字の自動のショートコード（#768）。正規化した画像 URL の SHA-256 の先頭 8 桁（hex）を
 * nostrism_ に続ける。同じ絵文字（同じ URL）なら毎回同じ名前になる。英小文字・数字・_ だけなので NIP-30 の文字種に合う。
 */
export async function autoEmojiShortcode(url: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(url));
  const hex = [...new Uint8Array(digest, 0, 4)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${AUTO_SHORTCODE_PREFIX}${hex}`;
}
