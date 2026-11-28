import { useState } from "react";
import { useT } from "../../i18n";
import { proxiedEmoji } from "../../lib/imageProxy";
import { MoodIcon } from "../../ui/icons";
import { YoEmojiPickerDialog } from "../yo/YoEmojiPickerDialog";
import { setYoEmojiUrl, useYoEmojiUrl } from "../yo/yoEmoji";
import { YO_IMAGE_URL } from "../yo/yoModel";
import styles from "./SettingsSections.module.css";

/**
 * [#1025] Yo の絵文字（ネイティブ YoEmojiPicker.kt YoEmojiSettings）。Yo を送るときに付ける絵文字の画像を選ぶ
 * （受け取った人の画面に出る）。「標準」は nostr-yo の絵。「カスタム絵文字」を押すと、自分のカスタム絵文字から選ぶ画面を開く。
 * ショートコードは Yo の決まりで yo のまま、画像だけが替わる。localStorage のみで NIP-78 の同期には入れない。
 */
export function YoEmojiSettings() {
  const t = useT();
  const url = useYoEmojiUrl();
  const standard = url === YO_IMAGE_URL;
  const [picking, setPicking] = useState(false);
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("yo_emoji_title")}</h3>
      <p className={styles.desc}>{t("yo_emoji_desc")}</p>
      <div className={styles.choices}>
        <button
          type="button"
          className={styles.choice}
          aria-pressed={standard}
          onClick={() => setYoEmojiUrl(YO_IMAGE_URL)}
        >
          <img
            className={styles.choiceEmoji}
            src={proxiedEmoji(YO_IMAGE_URL, 75)}
            alt=""
            decoding="async"
            referrerPolicy="no-referrer"
          />
          {t("yo_emoji_standard")}
        </button>
        <button
          type="button"
          className={styles.choice}
          aria-pressed={!standard}
          onClick={() => setPicking(true)}
        >
          {/* 選んであるときは、その絵を出す */}
          {standard ? (
            <MoodIcon className={styles.choiceIcon} />
          ) : (
            <img
              className={styles.choiceEmoji}
              src={proxiedEmoji(url, 75)}
              alt=""
              decoding="async"
              referrerPolicy="no-referrer"
            />
          )}
          {t("yo_emoji_custom")}
        </button>
      </div>
      {picking && (
        <YoEmojiPickerDialog
          current={url}
          onPick={(picked) => {
            setYoEmojiUrl(picked);
            setPicking(false);
          }}
          onClose={() => setPicking(false)}
        />
      )}
    </div>
  );
}
