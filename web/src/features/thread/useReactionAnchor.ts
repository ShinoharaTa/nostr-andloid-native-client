import type { EventPointer } from "applesauce-core/helpers/pointers";
import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useMemo, useState } from "react";
import { filter, take, timeout } from "rxjs";
import { useEventByPointer } from "../../nostr/loaders";
import { eventStore } from "../../nostr/store";
import { normalizeReaction } from "./engagement";
import {
  highlightOf,
  REACTION_ANCHOR_MAX_HOPS,
  type ReactionAnchorResult,
  resolveReactionAnchor,
  targetTagOf,
} from "./reactionAnchor";

/**
 * [#904] 統計行で光らせるリアクション（開いた kind:7。kind:16 を開いたならその中身。ネイティブ ThreadAnchor.kt ReactionHighlight）。
 * path は辿った kind:7 / 16 の id（開いた順。最後が起点の投稿に付いたもの）。木の中の位置と、常に開いておく枝の判定に使う。
 */
export type ReactionHighlight = {
  eventId: string;
  pubkey: string;
  display: string;
  imageUrl: string | null;
  path: string[];
};

export type ReactionAnchorState = {
  /** 実際に購読・表示する投稿（付け替え後。付け替えないときは渡された pointer のまま） */
  pointer: EventPointer | null;
  highlight: ReactionHighlight | null;
  /** 起点が kind:7 / 16 で、途中のイベントを取りに行っている間 true */
  pending: boolean;
};

/** 途中の kind:7 を取りに行って待つ上限（1 段ごと。ネイティブ ANCHOR_FETCH_MS） */
export const ANCHOR_FETCH_MS = 4_000;

const HEX64 = /^[0-9a-f]{64}$/i;

const NO_EVENTS: NostrEvent[] = [];

type Fetched = { startId: string | null; events: NostrEvent[]; gaveUp: boolean };

/**
 * [#904] pointer でスレッドを開かれたときの起点を決める（ネイティブ #901 の ui/ThreadAnchor.kt rememberThreadAnchor）。
 * kind:7 / 16 なら e タグを辿って元の投稿（P）に付け替え、辿った経路の最初の kind:7 を highlight にする。
 * 手元で辿れる分は最初から付け替え、途中のイベント（P を含む）が無ければ 1 段 4 秒・最大 6 段まで取りに行って待つ（pending）。
 * 起点そのものが手元に無いときは従来どおりその id のまま（スレッドの購読が本体を取ってきたら、そこから辿る）。
 * 辿れなかった（e タグ無し・深すぎる・循環・時間切れ）ときは従来どおりの表示に戻す。
 */
export function useReactionAnchor(pointer: EventPointer | null): ReactionAnchorState {
  const startId = pointer?.id ?? null;
  // 起点（届くまで undefined）。届いたら辿り直す
  const start = useEventByPointer(pointer);
  // 取ってきた途中のイベント（起点ごと）
  const [fetched, setFetched] = useState<Fetched>({ startId, events: [], gaveUp: false });
  const same = fetched.startId === startId;
  const fetchedEvents = same ? fetched.events : NO_EVENTS;
  const gaveUp = same && fetched.gaveUp;

  const result = useMemo<ReactionAnchorResult | null>(() => {
    if (startId === null) return null;
    const lookup = (id: string) =>
      id === start?.id ? start : (fetchedEvents.find((e) => e.id === id) ?? eventStore.getEvent(id));
    return resolveReactionAnchor(startId, lookup);
  }, [startId, start, fetchedEvents]);

  // 取りに行く途中のイベント（起点そのものはスレッドの購読が取ってくるので、経路が空のときは待たない）
  const need = result?.type === "needFetch" && result.path.length > 0 ? result : null;
  const exhausted = fetchedEvents.length >= REACTION_ANCHOR_MAX_HOPS;
  const needId = need && !gaveUp && !exhausted ? need.id : null;
  const hintTag = need ? targetTagOf(need.path[need.path.length - 1]) : null;
  const hint = hintTag?.[2]?.startsWith("wss://") ? hintTag[2] : null;

  useEffect(() => {
    if (startId === null || needId === null) return;
    const giveUp = () => setFetched((prev) => ({ ...sameStart(prev, startId), gaveUp: true }));
    if (!HEX64.test(needId)) {
      giveUp();
      return;
    }
    const sub = eventStore
      .event(hint ? { id: needId, relays: [hint] } : { id: needId })
      .pipe(
        filter((e): e is NostrEvent => e !== undefined),
        take(1),
        timeout({ first: ANCHOR_FETCH_MS }),
      )
      .subscribe({
        next: (event) =>
          setFetched((prev) => {
            const base = sameStart(prev, startId);
            return { ...base, events: [...base.events, event] };
          }),
        error: giveUp,
      });
    return () => sub.unsubscribe();
  }, [startId, needId, hint]);

  return useMemo<ReactionAnchorState>(() => {
    if (pointer === null || result === null) return { pointer, highlight: null, pending: false };
    if (result.type === "found" && result.path.length > 0) {
      const hl = highlightOf(result.path);
      const rx = hl ? normalizeReaction(hl.content, hl.tags) : null;
      return {
        pointer: { id: result.postId, relays: pointer.relays },
        highlight:
          hl && rx
            ? {
                eventId: hl.id,
                pubkey: hl.pubkey,
                display: rx.display,
                imageUrl: rx.imageUrl,
                path: result.path.map((e) => e.id),
              }
            : null,
        pending: false,
      };
    }
    return { pointer, highlight: null, pending: needId !== null };
  }, [pointer, result, needId]);
}

function sameStart(prev: Fetched, startId: string): Fetched {
  return prev.startId === startId ? prev : { startId, events: [], gaveUp: false };
}
