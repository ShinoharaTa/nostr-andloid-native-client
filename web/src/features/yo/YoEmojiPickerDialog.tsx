import { useMemo, useState } from "react";
import { useT } from "../../i18n";
import { proxiedEmoji } from "../../lib/imageProxy";
import { useSession } from "../../signer/session";
import { ModalSheet } from "../../ui/ModalSheet";
import picker from "../actions/ReactionPickerDialog.module.css";
import { useCustomEmojis } from "../compose/customEmojis";
import styles from "./Yo.module.css";
import { yoEmojiChoices } from "./yoModel";

/**
 * [#1025] Yo に付ける絵文字を選ぶ画面（ネイティブ YoEmojiPicker.kt YoEmojiPickerSheet）。先頭に標準（nostr-yo の絵）を
 * 必ず並べ、続けて自分のカスタム絵文字（kind:10030 / 30030 由来。リアクションのピッカーと同じ一覧）。
 * ショートコードで絞り込める。いま選んでいる絵には枠を付ける。見た目はリアクションのピッカーのマス目を使う。
 */
export function YoEmojiPickerDialog({
  current,
  onPick,
  onClose,
}: {
  current: string;
  onPick(url: string): void;
  onClose(): void;
}) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const customs = useCustomEmojis(me);
  const [query, setQuery] = useState("");
  const choices = useMemo(() => yoEmojiChoices(customs), [customs]);
  const q = query.trim().toLowerCase();
  const shown = q === "" ? choices : choices.filter((c) => c.shortcode.toLowerCase().includes(q));
  return (
    <ModalSheet title={t("yo_emoji_pick_title")} onDismiss={onClose}>
      <div className={picker.searchRow}>
        <input
          type="search"
          className={picker.search}
          aria-label={t("web_picker_search_label")}
          placeholder={t("picker_search_placeholder")}
          enterKeyHint="search"
          value={query}
          onChange={(e) => setQuery(e.currentTarget.value)}
        />
      </div>
      <div className={picker.scroll}>
        {shown.length === 0 ? (
          <p className={styles.pickHint}>{t("picker_no_match")}</p>
        ) : (
          <div className={picker.grid}>
            {shown.map((c) => (
              <button
                key={c.url}
                type="button"
                className={`${picker.cell} ${picker.imageCell} ${styles.pickCell}`}
                aria-label={`:${c.shortcode}:`}
                aria-pressed={c.url === current}
                onClick={() => onPick(c.url)}
              >
                <img src={proxiedEmoji(c.url, 80)} alt="" decoding="async" referrerPolicy="no-referrer" />
              </button>
            ))}
          </div>
        )}
        {/* 自分のカスタム絵文字が無いとき（標準だけが並ぶ）は、足し方を添える */}
        {choices.length === 1 && <p className={styles.pickHint}>{t("yo_emoji_pick_hint")}</p>}
      </div>
    </ModalSheet>
  );
}
