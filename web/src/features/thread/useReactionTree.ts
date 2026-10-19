import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useMemo } from "react";
import { subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { buildReactionTree, type ReactionNode, type ReactionTreeData, reactionNodeOf } from "./reactionTree";

/** 1 段の REQ の #e に入れる件数の上限（ネイティブ subscribeReactionBranches と同じ。Zap の購読と同じ上限） */
export const BRANCH_TARGETS_MAX = 300;
/** 1 段の REQ の limit（ネイティブ subscribeReactionBranches と同じ） */
export const BRANCH_LIMIT = 500;

const NO_EVENTS: NostrEvent[] = [];

/** 対象（最後の e）が targets のどれかである kind:7 / 16 を古い順に（ネイティブ Nostr.sq reactionNodesForTargets） */
function nodesTargeting(events: readonly NostrEvent[], targets: ReadonlySet<string>): ReactionNode[] {
  const seen = new Set<string>();
  const nodes: ReactionNode[] = [];
  for (const event of events) {
    if (seen.has(event.id)) continue;
    const node = reactionNodeOf(event);
    if (node === null || !targets.has(node.targetId)) continue;
    seen.add(event.id);
    nodes.push(node);
  }
  return nodes.sort((a, b) => a.createdAt - b.createdAt);
}

/** kind:7 の id（重複なし）を「,」でつないだもの（購読と EventStore の読み直しの鍵） */
function reactionIdsKey(nodes: readonly ReactionNode[]): string {
  return [...new Set(nodes.filter((n) => n.kind === 7).map((n) => n.id))].join(",");
}

const idsOf = (key: string) => (key === "" ? [] : key.split(","));

/** 1 段分: 対象が key の id である kind:7 / 16 を EventStore から読み、リレーにも取りに行く */
function useBranchLevel(relays: readonly string[], key: string): ReactionNode[] {
  // 対象の id が増えたら REQ を張り直す（#e は先頭 300 件まで）
  useEffect(() => {
    const ids = idsOf(key);
    if (ids.length === 0) return;
    const sub = subscribeTo(relays, [
      { kinds: [7, 16], "#e": ids.slice(0, BRANCH_TARGETS_MAX), limit: BRANCH_LIMIT },
    ]).subscribe();
    return () => sub.unsubscribe();
  }, [relays, key]);
  const events =
    use$(
      () => (key === "" ? undefined : eventStore.timeline([{ kinds: [7, 16], "#e": idsOf(key) }])),
      [key],
    ) ?? NO_EVENTS;
  return useMemo(() => nodesTargeting(events, new Set(idsOf(key))), [events, key]);
}

/**
 * [#904] 起点ノートのリアクションの木（ネイティブ #903 の EventRepository.kt noteReactionTreeFlow / subscribeReactionBranches）。
 * L1（起点に付いた kind:7。engagementEvents から）→ L2 → L3 と段ごとに EventStore を読み、
 * 段の対象 id が変わったときだけ次の段を読み直す・張り直す（段が無ければ REQ は張らない）。組み立ては buildReactionTree。
 */
export function useReactionTree(
  focusId: string | null,
  engagementEvents: readonly NostrEvent[],
  relays: readonly string[],
): ReactionTreeData {
  const l1 = useMemo(
    () => (focusId === null ? [] : nodesTargeting(engagementEvents, new Set([focusId]))),
    [engagementEvents, focusId],
  );
  const l2 = useBranchLevel(relays, reactionIdsKey(l1));
  const l3 = useBranchLevel(relays, reactionIdsKey(l2));
  return useMemo(() => buildReactionTree(l1, [...l2, ...l3]), [l1, l2, l3]);
}
