import { t } from "../../i18n";
import { useSession } from "../../signer/session";
import { showToast } from "../../ui/toast";
import { appendToEmojiList, EmojiListError } from "../compose/customEmojis";

/**
 * ピッカーの「絵文字を作る」で作った絵文字（#768。onPick の第 3 引数）。
 * autoName = ショートコードを自動で付けた（nostrism_…）、save = 「自分の絵文字リストにも保存」がオン。
 */
export type MadeEmoji = { made: true; autoName: boolean; save: boolean };

/** ピッカーからのリアクションを送ったときのトースト（#743）。自動の名前は意味をなさないので「作った絵文字で」 */
export function reactionSentMessage(content: string, made?: MadeEmoji): string {
  return made?.autoName ? t("web_reaction_sent_made") : t("web_reaction_sent_fmt", content);
}

/**
 * 作った絵文字を自分の絵文字リスト（kind:10030）にも足す（save がオンのとき。リアクションの送信の後に呼ぶ）。
 * 結果はリアクションの成否とは別のトーストで出す（成功・同じショートコードが既にある・失敗）。
 */
export async function saveMadeEmoji(
  content: string,
  imageUrl: string | null,
  made?: MadeEmoji,
): Promise<void> {
  const me = useSession.getState().pubkey;
  if (!made?.save || imageUrl === null || me === null) return;
  try {
    await appendToEmojiList(me, { shortcode: content.slice(1, -1), url: imageUrl });
    showToast(t("emoji_saved"));
  } catch (e) {
    if (e instanceof EmojiListError && e.reason === "duplicate")
      showToast(t("web_picker_make_save_duplicate"));
    else if (e instanceof EmojiListError && e.reason === "no-emoji-list") showToast(t("web_emoji_no_base"));
    // ネイティブ emoji_save_failed
    else showToast(t("emoji_save_failed"));
  }
}
