import type { NostrEvent } from "nostr-tools/pure";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForProfile } from "../../lib/content/labels";
import { timeLabel } from "../../lib/time";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { WavingHandIcon } from "../../ui/icons";
import { useTimeDisplay } from "../settings/timeDisplay";
import { Avatar } from "../timeline/NoteItem";
import { useNow } from "../timeline/useNow";
import styles from "./Yo.module.css";

/**
 * [#1025] 自分が送った Yo の行（ネイティブ YoUi.kt MyYoRow。フォロー中カラムの「自分がしたリアクション」・ふぁぼ欄）:
 * 「(手を振るアイコン) (アバター) 〇〇 さんに Yo  時刻」。対象は相手のプロフィール（kind:0）なので、投稿としては出さない。
 * 押すと相手のプロフィールを開く。
 */
export function MyYoRow({ reaction, to }: { reaction: NostrEvent; to: string }) {
  const t = useT();
  const profile = useProfile(to);
  const picture = pictureOf(profile);
  const now = useNow();
  const timeDisplay = useTimeDisplay();
  return (
    <Link to={hrefForProfile(to)} className={styles.myRow} data-testid="my-yo-row">
      <span className={styles.myMark}>
        <WavingHandIcon className={styles.markIcon} />
      </span>
      <span className={styles.myLine}>
        <Avatar key={picture} url={picture} size="sm" seed={to} pubkey={to} />
        <span className={styles.myText}>{t("feed_you_yo_fmt", displayName(profile, to))}</span>
      </span>
      <span className={styles.myTime}>{timeLabel(reaction.created_at, now, timeDisplay)}</span>
    </Link>
  );
}
