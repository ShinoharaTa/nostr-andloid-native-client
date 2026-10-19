import type { EventPointer } from "applesauce-core/helpers/pointers";
import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useMemo, useState } from "react";
import { map } from "rxjs";
import { LOADING_TIMEOUT_MS } from "../../lib/columnRequest";
import { useEventByPointer } from "../../nostr/loaders";
import { subscribeTo, useReadRelays } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { isNoteMuted, useMuteMatcher } from "../mute/muteList";
import type { ReactionTreeData } from "./reactionTree";
import {
  anchorsKey,
  buildThread,
  ENGAGEMENT_LIMIT,
  type ThreadAnchors,
  type ThreadEntry,
  threadAnchors,
  threadRequestFilters,
  threadViewFilters,
} from "./threadTree";
import { useReactionTree } from "./useReactionTree";

/** URL のリレーヒントから足すのは先頭この件数まで */
const MAX_HINT_RELAYS = 3;

const NO_EVENTS: NostrEvent[] = [];
const NO_ENTRIES: ThreadEntry[] = [];
/** pointer が無い間（#534。naddr の解決待ち）の anchors */
const EMPTY_ANCHORS: ThreadAnchors = { focusId: "", ids: [], rootId: "", address: null };

const resolveEvent = (id: string) => eventStore.getEvent(id);

export type Thread = {
  /** 開いた投稿（未取得の間は undefined） */
  focus: NostrEvent | undefined;
  anchors: ThreadAnchors;
  /** root から深さ優先の順の行（ミュート対象の行は除く。起点は残す） */
  entries: ThreadEntry[];
  /** 起点を e タグで指す投稿・リポスト・リアクション（集計は engagement.ts） */
  engagementEvents: NostrEvent[];
  /** [#904] 起点のリアクションの木（L1 → L2 → L3。useReactionTree） */
  reactionTree: ReactionTreeData;
  /** 最初の EOSE（または 8 秒経過）まで true。張り直しでは戻さない */
  loading: boolean;
};

/**
 * スレッドの購読と表示（ネイティブの EventRepository.kt subscribeThread / subscribeNoteEngagement / threadFeed）。
 * 起点が届いて root が決まったら返信の REQ を張り直す（URL 直開きで起点が未取得のまま開いた場合）。
 * pointer が null（#534。naddr を addressLoader で解決している間）は何も購読せず空を返す。
 */
export function useThread(pointer: EventPointer | null): Thread {
  const focus = useEventByPointer(pointer);
  const pointerId = pointer?.id ?? null;
  const latest = useMemo(
    () => (pointerId ? threadAnchors(pointerId, focus) : EMPTY_ANCHORS),
    [pointerId, focus],
  );
  // ids と address が同じ間は前のオブジェクトを使い続ける（起点が届いても root が同じなら張り直さない）
  const [anchors, setAnchors] = useState(latest);
  const key = anchorsKey(latest);
  if (key !== anchorsKey(anchors)) setAnchors(latest);

  // read リレー + URL のリレーヒント（wss:// のみ）。ヒントは中身で比べる
  const relays = useReadRelays();
  const hintKey = JSON.stringify(
    (pointer?.relays ?? []).filter((url) => url.startsWith("wss://")).slice(0, MAX_HINT_RELAYS),
  );
  const threadRelays = useMemo(
    () => [...new Set([...relays, ...(JSON.parse(hintKey) as string[])])],
    [relays, hintKey],
  );

  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const timer = setTimeout(() => setLoading(false), LOADING_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, []);

  // 返信の REQ。root が決まるなど条件が変わったら前の REQ を CLOSE して張り直す
  useEffect(() => {
    if (pointerId === null) return;
    const sub = subscribeTo(threadRelays, threadRequestFilters(anchors)).subscribe(() => setLoading(false));
    return () => sub.unsubscribe();
  }, [pointerId, threadRelays, anchors]);

  // 起点への反応の REQ（起点の id と read リレーで決まる）
  useEffect(() => {
    if (pointerId === null) return;
    const sub = subscribeTo(relays, [
      { kinds: [7, 6, 16], "#e": [pointerId], limit: ENGAGEMENT_LIMIT },
    ]).subscribe();
    return () => sub.unsubscribe();
  }, [relays, pointerId]);

  const allEntries =
    use$(
      () =>
        pointerId
          ? eventStore
              .timeline(threadViewFilters(anchors))
              .pipe(map((list) => buildThread(list, pointerId, anchors.rootId)))
          : undefined,
      [anchors, pointerId],
    ) ?? NO_ENTRIES;

  // ミュート（#465）。起点は残し、それ以外のミュート対象の行を隠す（ネイティブ ThreadColumn と同じ）
  const me = useSession((s) => s.pubkey);
  const matcher = useMuteMatcher();
  const entries = useMemo(
    () =>
      matcher.isEmpty
        ? allEntries
        : allEntries.filter(
            (entry) => entry.event.id === pointerId || !isNoteMuted(matcher, entry.event, me, resolveEvent),
          ),
    [allEntries, matcher, me, pointerId],
  );

  const engagementEvents =
    use$(
      () =>
        pointerId ? eventStore.timeline([{ kinds: [1, 1111, 6, 16, 7], "#e": [pointerId] }]) : undefined,
      [pointerId],
    ) ?? NO_EVENTS;

  // [#904] リアクションへのリアクション（L2・L3）の購読と組み立て
  const reactionTree = useReactionTree(pointerId, engagementEvents, relays);

  return { focus, anchors, entries, engagementEvents, reactionTree, loading };
}
