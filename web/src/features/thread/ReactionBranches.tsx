import { useMemo, useState } from "react";
import { useT } from "../../i18n";
import { displayName, useProfile } from "../../nostr/loaders";
import { RepeatIcon } from "../../ui/icons";
import type { ReactionGroup } from "./engagement";
import styles from "./ReactionBranches.module.css";
import { ReactorEmoji, ReactorRow } from "./ReactorRow";
import { childBranches, type ReactionBranch, visibleBranches, withHighlightFirst } from "./reactionTree";
import type { ReactionHighlight } from "./useReactionAnchor";

/**
 * [#904] 反応が付いたリアクションの枝（ネイティブ #903 の ThreadColumn.kt ReactionBranches）。
 * 「↳ 〇〇 の 👍 へ」の見出しの下に、その kind:7 に付いた反応を絵文字ごとの行（ReactorRow）とリポストした人の行で出し、
 * さらに反応が付いていれば孫の枝を重ねる。字下げはリプライの深さ 1 段と同じ。
 * 最初から出すのは REACTION_TREE_MAX_BLOCKS 件まで。残りは「ほか n 件」で開く（ハイライトの経路上の枝は常に出す）。
 */
export function ReactionBranches({
  blocks,
  highlight,
  flash,
}: {
  blocks: readonly ReactionBranch[];
  highlight: ReactionHighlight | null;
  flash: boolean;
}) {
  const t = useT();
  // 開いた枝の親（別の行に差し替わったら畳んだ状態に戻る）
  const parentId = blocks[0]?.target.targetId ?? "";
  const [expandedFor, setExpandedFor] = useState<string | null>(null);
  const forced = useMemo(() => new Set(highlight?.path ?? []), [highlight]);
  const [shown, hidden] = visibleBranches(blocks, expandedFor === parentId, forced);
  return (
    <div className={styles.branches}>
      {shown.map((b) => (
        <Branch key={b.target.id} branch={b} highlight={highlight} flash={flash} />
      ))}
      {hidden > 0 && (
        <button type="button" className={styles.more} onClick={() => setExpandedFor(parentId)}>
          {t("thread_more_branches_fmt", hidden)}
        </button>
      )}
    </div>
  );
}

function Branch({
  branch,
  highlight,
  flash,
}: {
  branch: ReactionBranch;
  highlight: ReactionHighlight | null;
  flash: boolean;
}) {
  const t = useT();
  const profile = useProfile(branch.target.pubkey);
  const name = displayName(profile, branch.target.pubkey, "npub");
  const suffix = t("thread_reaction_to_suffix");
  const isHighlighted = (g: ReactionGroup) =>
    highlight?.path.includes(branch.target.id) === true &&
    g.display === highlight.display &&
    g.people.includes(highlight.pubkey);
  return (
    <div>
      <p className={styles.heading}>
        <span className={styles.headingName}>↳ {t("thread_reaction_to_prefix_fmt", name)}</span>
        <span className={styles.headingEmoji}>
          <ReactorEmoji display={branch.target.display} imageUrl={branch.target.imageUrl} />
        </span>
        {suffix !== "" && <span className={styles.headingSuffix}>{suffix}</span>}
      </p>
      {branch.groups.map((g) => {
        const hl = isHighlighted(g);
        const sub = childBranches(branch, g);
        return (
          <div key={`${g.display}\n${g.imageUrl ?? ""}`}>
            <ReactorRow
              leading={<ReactorEmoji display={g.display} imageUrl={g.imageUrl} />}
              label={String(g.people.length)}
              people={hl && highlight ? withHighlightFirst(g.people, highlight.pubkey) : g.people}
              highlighted={hl}
              flash={flash}
            />
            {sub.length > 0 && <ReactionBranches blocks={sub} highlight={highlight} flash={flash} />}
          </div>
        );
      })}
      {branch.reposters.length > 0 && (
        <ReactorRow
          leading={<RepeatIcon className={styles.repost} />}
          label={String(branch.reposters.length)}
          people={branch.reposters}
        />
      )}
    </div>
  );
}
