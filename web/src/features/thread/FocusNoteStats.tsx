import type { NostrEvent } from "nostr-tools/pure";
import { useMemo, useState } from "react";
import { useT } from "../../i18n";
import { markProxyBlocked, originOf, proxiedEmoji } from "../../lib/imageProxy";
import { BoltIcon, RepeatIcon } from "../../ui/icons";
import type { NoteZaps } from "../zap/zapTotals";
import { countEngagement, groupReactions, reactionTotal, repostersOf } from "./engagement";
import styles from "./FocusNoteStats.module.css";
import { ReactorRow } from "./ReactorRow";

/**
 * 起点ノートの反応（ネイティブの ThreadColumn.kt FocusNoteStats）。
 * 「リプライ N · リポスト N · リアクション N」（0 の項目は出さない）→ ⚡ Zap した人（合計 sats）→
 * 🔁 リポストした人 → 絵文字ごとのリアクションした人。全部 0 なら何も描かない
 * （ネイティブは Zap だけのときも描かないが、Web は ⚡ 行を出す）。
 */
export function FocusNoteStats({
  noteId,
  events,
  zaps,
}: {
  noteId: string;
  events: readonly NostrEvent[];
  zaps: NoteZaps;
}) {
  const t = useT();
  const { replies, reposts, groups, reposters } = useMemo(
    () => ({
      ...countEngagement(events, noteId),
      groups: groupReactions(events, noteId),
      reposters: repostersOf(events, noteId),
    }),
    [events, noteId],
  );
  // Zap した人（重複なし・新しい順。送り主の分からない receipt は合計にだけ入る）
  const zappers = useMemo(() => [...new Set(zaps.zaps.flatMap((z) => (z.sender ? [z.sender] : [])))], [zaps]);
  const total = reactionTotal(groups);
  // リポストした人・リアクションのグループがあれば reposts / total も 0 ではない
  if (replies === 0 && reposts === 0 && total === 0 && zaps.zaps.length === 0) return null;

  const summary = [
    replies > 0 ? t("thread_stat_replies_fmt", replies) : null,
    reposts > 0 ? t("thread_stat_reposts_fmt", reposts) : null,
    total > 0 ? t("thread_stat_reactions_fmt", total) : null,
  ].filter((s) => s !== null);

  return (
    <div className={styles.stats}>
      {summary.length > 0 && <p className={styles.summary}>{summary.join(" · ")}</p>}
      {zaps.zaps.length > 0 && (
        <ReactorRow
          leading={<BoltIcon className={styles.zap} />}
          label={`${zaps.totalSats} sats`}
          people={zappers}
        />
      )}
      {reposters.length > 0 && (
        <ReactorRow
          leading={<RepeatIcon className={styles.repost} />}
          label={String(reposters.length)}
          people={reposters}
        />
      )}
      {groups.map((g) => (
        <ReactorRow
          key={`${g.display}\n${g.imageUrl ?? ""}`}
          leading={
            g.imageUrl && /^https:\/\//i.test(g.imageUrl) ? (
              <ReactionImage key={g.imageUrl} url={g.imageUrl} display={g.display} />
            ) : (
              <span className={styles.emojiText}>{g.display}</span>
            )
          }
          label={String(g.people.length)}
          people={g.people}
        />
      ))}
    </div>
  );
}

/**
 * カスタム絵文字の画像。プロキシが読めなければ元 URL（https のみ）で 1 度だけ取り直し、
 * それも読めなければ :code: の文字に戻す（NoteContent の EmojiImage と同じ）。
 */
function ReactionImage({ url, display }: { url: string; display: string }) {
  const [src, setSrc] = useState<string | null>(() => proxiedEmoji(url, 80));
  if (!src) return <span className={styles.emojiText}>{display}</span>;

  function onError() {
    const origin = originOf(src);
    if (origin) {
      markProxyBlocked(origin);
      setSrc(/^https:\/\//i.test(origin) ? origin : null);
    } else {
      setSrc(null);
    }
  }

  return (
    <img
      className={styles.emoji}
      src={src}
      alt={display}
      title={display}
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}
