import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { useCallback, useEffect, useMemo, useState } from "react";
import { map } from "rxjs";
import { INDEXER_RELAYS, LOADING_TIMEOUT_MS } from "../../lib/columnRequest";
import { extractMedia } from "../../lib/media";
import { authorOutbox$ } from "../../nostr/outbox";
import { requestOnce, subscribeTo, useReadRelays } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { STATUS_KIND } from "../status/statusModel";

/** 開いている間に張る REQ の kind（プロフィール・投稿・リポスト・リレーリスト・記事。#534） */
export const PROFILE_REQ_KINDS = [0, 1, 6, 16, 10002, 30023];
/** 投稿タブに出す kind（返信も含む） */
export const PROFILE_FEED_KINDS = [1, 6, 16];
export const PROFILE_REQ_LIMIT = 100;
/** 投稿タブの最大件数（ネイティブ feedAuthorsWithReposts の LIMIT 150。過去読みは無い） */
export const PROFILE_FEED_MAX = 150;
/** [#821] 本人のステータス（NIP-38）の種類。general / music の各最新 1 件（addressable なので limit 2 で足りる） */
export const PROFILE_STATUS_TYPES = ["general", "music"];
/** 開いた時の kind:0 / 10002 の取り直しを待つ時間 */
export const PROFILE_OPEN_TIMEOUT_MS = 10_000;

const NO_EVENTS: NostrEvent[] = [];

/** メディアタブに出すか（kind:1 は画像あり、リポストは手元にある元投稿が画像ありの kind:1） */
export function hasProfileMedia(event: NostrEvent): boolean {
  if (event.kind === 1) return extractMedia(event).images.length > 0;
  if (event.kind === 6 || event.kind === 16) {
    const id = event.tags.find((t) => t[0] === "e")?.[1];
    if (!id) return false;
    const original = eventStore.getEvent(id);
    return original?.kind === 1 && extractMedia(original).images.length > 0;
  }
  return false;
}

/**
 * プロフィール画面の購読と投稿（ネイティブ ProfileScreen の subscribeColumn + loadProfile）。
 * 開いている間、本人の kind 0/1/6/16/10002 とステータス（kind:30315。#821）を自分のリレーと本人の書き込みリレー（アウトボックス）へ張り、
 * 開いた時に kind:0 / 10002 をインデクサと nprofile のリレーヒントからも取り直す。閉じたら CLOSE。
 */
export function useProfileFeed(
  pubkey: string,
  relayHints: readonly string[],
): {
  loading: boolean;
  posts: NostrEvent[];
  media: NostrEvent[];
  articles: NostrEvent[];
  /** REQ を張り直す（#601 引っ張って更新） */
  refresh(): void;
} {
  const hintsKey = relayHints
    .filter((url) => url.startsWith("wss://"))
    .slice(0, 3)
    .join(",");
  const relays = useReadRelays();

  const [epoch, setEpoch] = useState(0);
  const [loading, setLoading] = useState(true);
  // biome-ignore lint/correctness/useExhaustiveDependencies: epoch は refresh() で張り直すためのキー
  useEffect(() => {
    const main = [
      { kinds: PROFILE_REQ_KINDS, authors: [pubkey], limit: PROFILE_REQ_LIMIT },
      {
        kinds: [STATUS_KIND],
        authors: [pubkey],
        "#d": PROFILE_STATUS_TYPES,
        limit: PROFILE_STATUS_TYPES.length,
      },
    ];
    const hints = hintsKey === "" ? [] : hintsKey.split(",");
    setLoading(true);
    const done = () => setLoading(false);
    const timer = setTimeout(done, LOADING_TIMEOUT_MS);
    const feed = subscribeTo(relays, main).subscribe(done);
    const outbox = authorOutbox$([pubkey], main).subscribe();
    const reload = requestOnce(
      [...new Set([...relays, ...INDEXER_RELAYS, ...hints])],
      [{ kinds: [0, 10002], authors: [pubkey], limit: 4 }],
      PROFILE_OPEN_TIMEOUT_MS,
    ).subscribe({ error: () => {} });
    return () => {
      clearTimeout(timer);
      feed.unsubscribe();
      outbox.unsubscribe();
      reload.unsubscribe();
    };
  }, [pubkey, hintsKey, relays, epoch]);

  const refresh = useCallback(() => setEpoch((e) => e + 1), []);

  const posts =
    use$(
      () =>
        eventStore
          .timeline({ kinds: PROFILE_FEED_KINDS, authors: [pubkey] })
          .pipe(map((list) => list.slice(0, PROFILE_FEED_MAX))),
      [pubkey],
    ) ?? NO_EVENTS;
  const media = useMemo(() => posts.filter(hasProfileMedia), [posts]);
  // [#534] 本人の記事（kind:30023）。新しい順、同じ d タグは最新版だけ（eventStore.timeline の既定動作）
  const articles =
    use$(() => eventStore.timeline({ kinds: [30023], authors: [pubkey] }), [pubkey]) ?? NO_EVENTS;
  return { loading, posts, media, articles, refresh };
}
