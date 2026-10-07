import { type EventPointer, getEventPointerFromETag } from "applesauce-core/helpers/pointers";
import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { combineLatest, map, type Subscription, startWith } from "rxjs";
import { useT } from "../../i18n";
import {
  type Ctx,
  LOADING_TIMEOUT_MS,
  mixViewsFor,
  OLDER_TIMEOUT_MS,
  outboxAuthorsFor,
  requestFor,
  viewFor,
} from "../../lib/columnRequest";
import { type ColumnSpec, encodeReqFilter } from "../../lib/columns";
import { authorOutbox$ } from "../../nostr/outbox";
import { requestOnce, subscribeTo, useReadRelays } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { feedCatHiddenOf, isMutedRevealed, useDeck } from "../../store/deck";
import { muteVisibleFilter, useMuteMatcher } from "../mute/muteList";
import { useDmNotices } from "../notifications/useDmNotices";
import { type FeedRow, mixFollowingFeed } from "./followingMix";
import { useFollows } from "./useFollows";

/** following = フォロー + 自分、column = フォロー中以外のカラム */
export type FeedMode = "following" | "column";

export type ColumnFeed = {
  mode: FeedMode;
  /** 最初の EOSE（または 8 秒経過。フォロー中は kind:3 未受信の間も）まで true */
  loading: boolean;
  /** フォロー中・ステータスのカラムで REQ を出していない（フォロー 0 件・未ログイン）ときの空表示の文言 */
  emptyText?: string;
  /** 新しい順（ミュート対象は除く。カラムで「ミュートを表示」中なら除かない） */
  events: NostrEvent[];
  /**
   * フォロー中カラムの行（events に自分への反応・自分のリアクション・未読 DM を混ぜたもの。新しい順。
   * ミュート対象と ⋯ で隠した種別は除く）。フォロー中カラム以外・未ログインは null
   */
  rows: FeedRow[] | null;
  /** 過去読みの最中 */
  loadingOlder: boolean;
  /** いま出ている最古より古いものを 1 回だけ取りに行く */
  loadOlder(): void;
  /** REQ を張り直す */
  refresh(): void;
};

const NO_EVENTS: NostrEvent[] = [];

const resolveEvent = (id: string) => eventStore.getEvent(id);

/** リアクションの対象（最後の e タグ。NIP-25）。読めなければ null */
function reactionTargetOf(reaction: NostrEvent): EventPointer | null {
  const tag = reaction.tags.findLast((t) => t[0] === "e");
  return tag ? getEventPointerFromETag(tag) : null;
}

const NO_IDS: ReadonlySet<string> = new Set();

/**
 * 1 カラムの購読と表示。カラムの REQ を張ったままにし（アンマウントで CLOSE）、EventStore から条件に合う投稿を読む。
 */
export function useColumnFeed(spec: ColumnSpec): ColumnFeed {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  // フック呼び出しの順を変えないよう常に呼ぶ（フォロー中・ステータス以外は me を渡さず購読しない）
  const follows = useFollows(spec.kind === "FOLLOWING" || spec.kind === "STATUS" ? me : null);
  const relays = useReadRelays();

  // フィルター・フォロー・read リレーの中身が変わったときだけ張り直す（同じ中身で配列が作り直されても据え置く）
  const filterKey = encodeReqFilter(spec.filter);
  const followKey = follows?.join(",");
  // biome-ignore lint/correctness/useExhaustiveDependencies: spec と follows は中身のキー（id・filterKey・followKey）で比べる
  const { plan, view } = useMemo(() => {
    const ctx: Ctx = { me, follows, relays };
    return { plan: requestFor(spec, ctx), view: viewFor(spec, ctx) };
  }, [spec.id, filterKey, me, followKey, relays]);

  // 著者 1〜3 人のカラムは、その人たちの書き込みリレーへも張る（アウトボックス購読）
  const outboxKey = outboxAuthorsFor(spec)?.join(",") ?? "";

  const [epoch, setEpoch] = useState(0);
  const [loading, setLoading] = useState(plan !== null);
  // biome-ignore lint/correctness/useExhaustiveDependencies: epoch は refresh() で張り直すためのキー
  useEffect(() => {
    if (plan === null) {
      setLoading(false);
      return;
    }
    setLoading(true);
    const done = () => setLoading(false);
    const timer = setTimeout(done, LOADING_TIMEOUT_MS);
    const sub = subscribeTo(plan.relays, plan.filters).subscribe(done);
    const outbox = outboxKey !== "" ? authorOutbox$(outboxKey.split(","), plan.filters).subscribe() : null;
    return () => {
      clearTimeout(timer);
      sub.unsubscribe();
      outbox?.unsubscribe();
    };
  }, [plan, epoch, outboxKey]);

  const events =
    use$(
      () =>
        eventStore
          .timeline(view.filters)
          .pipe(map((list) => (view.predicate ? list.filter(view.predicate) : list))),
      [view],
    ) ?? NO_EVENTS;

  // ミュート（#465）。表示する一覧だけから除く（過去読みの起点は除く前の最古）
  const matcher = useMuteMatcher();
  const revealed = useDeck((s) => isMutedRevealed(s, spec.id));
  const visible = useMemo(() => {
    const keep = revealed ? null : muteVisibleFilter(spec.kind, matcher, me, resolveEvent);
    return keep ? events.filter(keep) : events;
  }, [events, matcher, revealed, spec.kind, me]);

  const rows = useFollowingRows(spec, me, follows, visible, revealed);

  const [loadingOlder, setLoadingOlder] = useState(false);
  const older = useRef<{ oldest: number | null; sub: Subscription | null }>({ oldest: null, sub: null });
  useEffect(() => () => older.current.sub?.unsubscribe(), []);

  const loadOlder = useCallback(() => {
    const oldest = events.at(-1)?.created_at;
    const state = older.current;
    if (oldest === undefined || oldest === state.oldest || state.sub !== null || plan === null) return;
    state.oldest = oldest;
    setLoadingOlder(true);
    const finish = () => {
      state.sub = null;
      setLoadingOlder(false);
    };
    const filters = plan.filters.map((f) => ({ ...f, until: oldest }));
    const sub = requestOnce(plan.relays, filters, OLDER_TIMEOUT_MS).subscribe({
      complete: finish,
      error: finish,
    });
    // 同期的に終わった場合は finish 済みなので控えない
    if (!sub.closed) state.sub = sub;
  }, [events, plan]);

  const refresh = useCallback(() => setEpoch((e) => e + 1), []);

  const mode: FeedMode = spec.kind !== "FOLLOWING" ? "column" : "following";
  // フォロー中・ステータスのカラムで REQ を出していない（未ログインでフォローも無い）ときだけ空表示の文言を出す
  // （#583。ネイティブ feed_empty。ステータスはログインを促す #767）
  const emptyText =
    plan === null && spec.kind === "FOLLOWING"
      ? t("feed_empty")
      : plan === null && spec.kind === "STATUS"
        ? t("web_status_login_needed")
        : undefined;
  return { mode, loading, emptyText, events: visible, rows, loadingOlder, loadOlder, refresh };
}

/**
 * フォロー中カラムに混ぜる行（REQ は requestFor が投稿と一緒に張る）。ミュートは種別ごとに既存の判定
 * （通知 = 相手、自分のリアクション = 対象、DM = 相手）で除き、revealed なら除かない。
 */
function useFollowingRows(
  spec: ColumnSpec,
  me: string | null,
  follows: readonly string[] | null,
  posts: NostrEvent[],
  revealed: boolean,
): FeedRow[] | null {
  const views = useMemo(() => mixViewsFor(spec.kind, me), [spec.kind, me]);
  const notifications =
    use$(() => {
      if (!views) return undefined;
      const { filters, predicate } = views.notifications;
      return eventStore.timeline(filters).pipe(map((list) => (predicate ? list.filter(predicate) : list)));
    }, [views]) ?? NO_EVENTS;
  const myReactions =
    use$(() => (views ? eventStore.timeline(views.myReactions.filters) : undefined), [views]) ?? NO_EVENTS;

  // 自分のリアクションの対象。ストアに無いものは eventLoader が取りに行き（ネイティブ requestEvent）、届いたら混ぜる
  const targets = useMemo(() => {
    const byId = new Map<string, EventPointer>();
    for (const reaction of myReactions) {
      const pointer = reactionTargetOf(reaction);
      if (pointer && !byId.has(pointer.id)) byId.set(pointer.id, pointer);
    }
    return [...byId.values()];
  }, [myReactions]);
  const resolved =
    use$(
      () =>
        targets.length === 0
          ? undefined
          : combineLatest(targets.map((p) => eventStore.event(p).pipe(startWith(undefined)))).pipe(
              map((list) => new Set(list.flatMap((e) => (e ? [e.id] : [])))),
            ),
      [targets],
    ) ?? NO_IDS;

  const matcher = useMuteMatcher();
  const dms = useDmNotices(views !== null, revealed);
  const hidden = useDeck((s) => feedCatHiddenOf(s, spec.id));

  return useMemo(() => {
    if (!views) return null;
    const keepNotice = revealed ? null : muteVisibleFilter("NOTIFICATIONS", matcher, me, resolveEvent);
    const keepReaction = revealed ? null : muteVisibleFilter("FAVS", matcher, me, resolveEvent);
    return mixFollowingFeed({
      posts,
      notifications: keepNotice ? notifications.filter(keepNotice) : notifications,
      myReactions: keepReaction ? myReactions.filter(keepReaction) : myReactions,
      dms,
      follows,
      me,
      hidden,
      hasTarget: (reaction) => {
        const pointer = reactionTargetOf(reaction);
        return pointer !== null && resolved.has(pointer.id);
      },
    });
  }, [views, posts, notifications, myReactions, resolved, dms, follows, me, hidden, matcher, revealed]);
}
