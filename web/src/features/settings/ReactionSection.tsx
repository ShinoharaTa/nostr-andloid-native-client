import { useState } from "react";
import { useT } from "../../i18n";
import { proxiedEmoji } from "../../lib/imageProxy";
import { FavoriteIcon, MoodIcon, StarIcon } from "../../ui/icons";
import { ReactionPickerDialog } from "../actions/ReactionPickerDialog";
import { setDefaultReaction, useDefaultReaction } from "../actions/reactionPrefs";
import styles from "./SettingsSections.module.css";

/**
 * リアクション（ネイティブ ReactionSettings。#587 で「表示」から独立セクションへ戻した）。
 * ハート / スター / その他の絵文字（ピッカーで選ぶ）。♡ ボタンが送る内容と形が変わる（#459 の reactionPrefs）。
 */
export function ReactionSection() {
  const t = useT();
  const content = useDefaultReaction((s) => s.content);
  const image = useDefaultReaction((s) => s.image);
  const [picking, setPicking] = useState(false);
  const isHeart = content === "+" || content === "❤️";
  const isStar = content === "⭐" || content === "★";
  const isOther = !isHeart && !isStar;

  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("reaction_default_title")}</h3>
      <p className={styles.desc}>{t("web_settings_reaction_desc")}</p>
      <div className={styles.choices}>
        <button
          type="button"
          className={styles.choice}
          aria-pressed={isHeart}
          onClick={() => setDefaultReaction("+", null)}
        >
          <FavoriteIcon className={styles.choiceIcon} />
          {t("reaction_heart")}
        </button>
        <button
          type="button"
          className={styles.choice}
          aria-pressed={isStar}
          onClick={() => setDefaultReaction("⭐", null)}
        >
          <StarIcon className={styles.choiceIcon} />
          {t("reaction_star")}
        </button>
        <button
          type="button"
          className={styles.choice}
          aria-pressed={isOther}
          onClick={() => setPicking(true)}
        >
          {isOther && image ? (
            <img
              className={styles.choiceEmoji}
              src={proxiedEmoji(image, 75)}
              alt={content}
              decoding="async"
              referrerPolicy="no-referrer"
            />
          ) : isOther ? (
            <span aria-hidden="true">{content}</span>
          ) : (
            <MoodIcon className={styles.choiceIcon} />
          )}
          {t("reaction_other")}
        </button>
      </div>
      {picking && (
        <ReactionPickerDialog
          onPick={(picked, imageUrl) => setDefaultReaction(picked, imageUrl)}
          onClose={() => setPicking(false)}
        />
      )}
    </div>
  );
}
