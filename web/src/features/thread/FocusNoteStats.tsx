import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useMemo, useRef, useState } from "react";
import { useT } from "../../i18n";
import { BoltIcon, RepeatIcon } from "../../ui/icons";
import type { NoteZaps } from "../zap/zapTotals";
import {
  countEngagement,
  groupReactions,
  type ReactionGroup,
  reactionTotal,
  repostersOf,
} from "./engagement";
import styles from "./FocusNoteStats.module.css";
import { ReactionBranches } from "./ReactionBranches";
import { ReactorEmoji, ReactorRow } from "./ReactorRow";
import { branchesFor, EMPTY_REACTION_TREE, type ReactionTreeData, withHighlightFirst } from "./reactionTree";
import type { ReactionHighlight } from "./useReactionAnchor";

/**
 * 起点ノートの反応（ネイティブの ThreadColumn.kt FocusNoteStats）。
 * 「リプライ N · リポスト N · リアクション N」（0 の項目は出さない）→ ⚡ Zap した人（合計 sats）→
 * 🔁 リポストした人 → 絵文字ごとのリアクションした人。全部 0 なら何も描かない
 * （ネイティブは Zap だけのときも描かないが、Web は ⚡ 行を出す）。
 *
 * [#904] 絵文字ごとの行の下に、その行の人のリアクションに付いた反応の木（ReactionBranches。ネイティブ #903）。
 * highlight（kind:7 の id で開かれたとき）があれば、その行でその人のアバターを先頭に移す。flash の間は、
 * 届いたところで一度だけその行へ寄せ（scrollIntoView）、下地を点けて薄く消す（onFlashed で親に伝え、二度目は光らせない）。
 * L1（起点に直接付いたもの）は経路が 1 つのときだけ。深いものは木の中の行が光る。
 */
export function FocusNoteStats({
  noteId,
  events,
  zaps,
  tree = EMPTY_REACTION_TREE,
  highlight = null,
  flash = false,
  onFlashed,
}: {
  noteId: string;
  events: readonly NostrEvent[];
  zaps: NoteZaps;
  tree?: ReactionTreeData;
  highlight?: ReactionHighlight | null;
  flash?: boolean;
  onFlashed?: () => void;
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

  const isHighlighted = (g: ReactionGroup) =>
    highlight !== null &&
    highlight.path.length === 1 &&
    g.display === highlight.display &&
    g.people.includes(highlight.pubkey);
  const arrived = highlight !== null && (groups.some(isHighlighted) || tree.nodeIds.has(highlight.eventId));

  // 光らせている間 true（このコンポーネントの間だけ。仮想リストで描き直されても二度目は光らせない）
  const [flashing, setFlashing] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!arrived || !flash) return;
    onFlashed?.();
    setFlashing(true);
  }, [arrived, flash, onFlashed]);
  useEffect(() => {
    if (!flashing) return;
    root.current?.querySelector("[data-rx-highlight]")?.scrollIntoView?.({ block: "center" });
  }, [flashing]);

  const total = reactionTotal(groups);
  // リポストした人・リアクションのグループがあれば reposts / total も 0 ではない
  if (replies === 0 && reposts === 0 && total === 0 && zaps.zaps.length === 0) return null;

  const summary = [
    replies > 0 ? t("thread_stat_replies_fmt", replies) : null,
    reposts > 0 ? t("thread_stat_reposts_fmt", reposts) : null,
    total > 0 ? t("thread_stat_reactions_fmt", total) : null,
  ].filter((s) => s !== null);

  return (
    <div ref={root} className={styles.stats}>
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
      {groups.map((g) => {
        const hl = isHighlighted(g);
        const blocks = branchesFor(tree, g);
        return (
          <div key={`${g.display}\n${g.imageUrl ?? ""}`}>
            <ReactorRow
              leading={<ReactorEmoji display={g.display} imageUrl={g.imageUrl} />}
              label={String(g.people.length)}
              people={hl && highlight ? withHighlightFirst(g.people, highlight.pubkey) : g.people}
              highlighted={hl}
              flash={flashing}
            />
            {blocks.length > 0 && <ReactionBranches blocks={blocks} highlight={highlight} flash={flashing} />}
          </div>
        );
      })}
    </div>
  );
}
