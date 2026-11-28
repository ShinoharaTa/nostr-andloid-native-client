import type { NostrEvent } from "nostr-tools/pure";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForEvent, oneLine } from "../../lib/content/labels";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { FavoriteBorderIcon } from "../../ui/icons";
import { useReactionTarget } from "../deck/useReactionTarget";
import { Avatar } from "../timeline/NoteItem";
import { useNow } from "../timeline/useNow";
import { MyYoRow } from "../yo/MyYoRow";
import { reactsToProfile, yoRecipientOf } from "../yo/yoModel";
import styles from "./MyReactionRow.module.css";
import { plainTextOf } from "./noteLinks";
import { ReactionDisplay } from "./ReactionDisplay";
import { reactionDisplay } from "./reactions";

/**
 * ふぁぼ欄の 1 行（ネイティブ FeedColumn.kt の MyReactionRow）。左に ♡ のアイコン、右に「あなたがリアクション」→
 * 絵文字の行 →「(アバター) 名前: 本文」の 1 行（[#878] 絵文字は本体の 1 行。操作ボタンは出さない）。
 * 押すと対象のスレッド。対象が解決できるまでは 1px の空行
 * （仮想リストは高さ 0 の行を扱えない）。
 */
export function MyReactionRow({ reaction }: { reaction: NostrEvent }) {
  // [#1025] 自分が送った Yo は「〇〇 さんに Yo」の行。対象は相手のプロフィール（kind:0）なので、投稿としては出さない
  const yoTo = yoRecipientOf(reaction, useNow());
  if (yoTo !== null) return <MyYoRow reaction={reaction} to={yoTo} />;
  // プロフィール（kind:0）へのリアクションは、対象を投稿として出せない（取りにも行かない）
  if (reactsToProfile(reaction.tags)) return <div className={styles.pending} aria-hidden="true" />;
  return <NoteReactionRow reaction={reaction} />;
}

function NoteReactionRow({ reaction }: { reaction: NostrEvent }) {
  const target = useReactionTarget(reaction);
  if (!target) return <div className={styles.pending} aria-hidden="true" />;
  return <ResolvedRow reaction={reaction} target={target} />;
}

function ResolvedRow({ reaction, target }: { reaction: NostrEvent; target: NostrEvent }) {
  const t = useT();
  const profile = useProfile(target.pubkey);
  const picture = pictureOf(profile);
  const { text, imageUrl } = reactionDisplay(reaction);
  return (
    <Link
      to={hrefForEvent({ id: target.id, author: target.pubkey, kind: target.kind })}
      className={styles.row}
    >
      <span className={styles.mark}>
        <FavoriteBorderIcon className={styles.markIcon} />
      </span>
      <span className={styles.body}>
        <span className={styles.label}>{t("feed_you_reacted")}</span>
        <span className={styles.reaction}>
          <ReactionDisplay display={text} imageUrl={imageUrl} />
        </span>
        <span className={styles.line}>
          <Avatar key={picture} url={picture} size="sm" seed={target.pubkey} pubkey={target.pubkey} />
          <span className={styles.summary}>
            {`${displayName(profile, target.pubkey)}: ${oneLine(plainTextOf(target))}`}
          </span>
        </span>
      </span>
    </Link>
  );
}
