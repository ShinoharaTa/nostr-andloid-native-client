import { create } from "zustand";
import { YO_IMAGE_URL, yoSendImageUrl } from "./yoModel";

// ---- [#1025] Yo に付ける絵文字の画像（設定 > 表示。ネイティブ EventRepository.yoEmojiUrlFlow） ----

/**
 * 値は画像の URL（ショートコードは Yo の決まりで `yo` のまま）。標準は nostr-yo の絵（YO_IMAGE_URL）。
 * NIP-78 の同期には入れない（ネイティブも端末ローカル）
 */
export const YO_EMOJI_KEY = "nostrism.display.yoEmoji";

function readYoEmojiUrl(): string {
  try {
    return yoSendImageUrl(localStorage.getItem(YO_EMOJI_KEY));
  } catch {
    // 読めなければ標準へ
    return YO_IMAGE_URL;
  }
}

const useYoEmojiStore = create<{ url: string }>()(() => ({ url: readYoEmojiUrl() }));

/** Yo に付ける絵文字の画像の URL（設定値） */
export function useYoEmojiUrl(): string {
  return useYoEmojiStore((s) => s.url);
}

/** いまの設定値（送るときに読む） */
export function yoEmojiUrl(): string {
  return useYoEmojiStore.getState().url;
}

/** Yo に付ける絵文字を変える（設定画面から）。使えない URL（https でない等）は標準に戻す */
export function setYoEmojiUrl(url: string): void {
  const next = yoSendImageUrl(url);
  useYoEmojiStore.setState({ url: next });
  try {
    localStorage.setItem(YO_EMOJI_KEY, next);
  } catch {
    // 保存できなくてもこのセッションでは効く
  }
}

/** テスト専用: 保存値を読み直す（localStorage は呼び元で用意・消す） */
export function reloadYoEmojiForTest(): void {
  useYoEmojiStore.setState({ url: readYoEmojiUrl() });
}
