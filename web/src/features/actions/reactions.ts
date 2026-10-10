import { getReplaceableAddress } from "applesauce-core/helpers/event";
import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { distinctUntilChanged, map, type Observable, type Subscription, shareReplay } from "rxjs";
import { subscribe } from "../../nostr/pool";
import { publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { type RelayHintLookup, storeRelayHints } from "../compose/relayHints";
import { fillRelayHints } from "../compose/tags";
import { normalizeReaction } from "../thread/engagement";
import { recordUsedEmoji, useDefaultReaction } from "./reactionPrefs";

/**
 * 投稿への反応（ネイティブ EventRepository.kt の publishReaction / reactWithDefault / publishRepost /
 * requestDelete / reportNote の Web 版）。発行はすべて publishEvent を通す。
 */

/** 照合用のリアクション内容（"+" と空は ❤️。ネイティブ publishSigned の保存時の正規化） */
export function reactionKey(content: string): string {
  return content === "+" || content === "" ? "❤️" : content;
}

/** 表示用（ネイティブ normalizeReaction: trim、+ / 空 → ❤️、- → 👎、:code: は同名の emoji タグの画像） */
export function reactionDisplay(event: NostrEvent): { text: string; imageUrl: string | null } {
  const { display, imageUrl } = normalizeReaction(event.content, event.tags);
  return { text: display, imageUrl };
}

/** kind:7 のタグ: e → p →（カスタム絵文字なら）emoji。e / p のヒントは埋める */
export function buildReactionTags(
  target: NostrEvent,
  content: string,
  imageUrl: string | null,
  hints: RelayHintLookup,
): string[][] {
  const custom = imageUrl && content.length >= 2 && content.startsWith(":") && content.endsWith(":");
  return fillRelayHints(
    [["e", target.id], ["p", target.pubkey], ...(custom ? [["emoji", content.slice(1, -1), imageUrl]] : [])],
    hints.eventHint,
    hints.pubkeyHint,
  );
}

/** リアクション（kind:7）を送り、"+" 以外は「最近」に記録する */
export async function publishReaction(
  target: NostrEvent,
  content = "+",
  imageUrl: string | null = null,
): Promise<void> {
  const hints = storeRelayHints(useSession.getState().pubkey);
  await publishEvent({ kind: 7, content, tags: buildReactionTags(target, content, imageUrl, hints) });
  recordUsedEmoji(content, imageUrl);
}

/** ストアにある自分の kind:7 のうち、targetId への content と同じもの（照合は reactionKey） */
export function findMyReaction(targetId: string, me: string, content: string): NostrEvent | undefined {
  const key = reactionKey(content);
  return eventStore
    .getByFilters({ kinds: [7], authors: [me], "#e": [targetId] })
    .find((e) => reactionKey(e.content) === key);
}

/** ♡ ボタン: 既定リアクションが付いていれば取り消し（kind:5）、無ければ送る */
export async function reactWithDefault(target: NostrEvent): Promise<void> {
  const me = useSession.getState().pubkey;
  if (me === null) return;
  const { content, image } = useDefaultReaction.getState();
  const mine = findMyReaction(target.id, me, content);
  if (mine) {
    await publishEvent({
      kind: 5,
      content: "",
      tags: [
        ["e", mine.id],
        ["k", "7"],
      ],
    });
    return;
  }
  await publishReaction(target, content, image);
}

/**
 * リポストのタグ。e → p（ヒントは埋める）。[#810] kind:1 以外（kind:16 で出す）は、置き換え可能なら a（座標）、
 * 最後に k（元の kind）を付ける（NIP-18）
 */
export function buildRepostTags(target: NostrEvent, hints: RelayHintLookup): string[][] {
  const tags = fillRelayHints(
    [
      ["e", target.id],
      ["p", target.pubkey],
    ],
    hints.eventHint,
    hints.pubkeyHint,
  );
  if (target.kind === 1) return tags;
  const address = getReplaceableAddress(target);
  if (address !== null) tags.push(["a", address]);
  tags.push(["k", String(target.kind)]);
  return tags;
}

/**
 * リポスト（取り消しは無い = ネイティブと同じ）。[#810] NIP-18 では kind:6 は kind:1 のリポスト専用なので、
 * それ以外（パブリックチャットの発言 kind:42・記事 kind:30023 など）は汎用リポストの kind:16 で出す
 */
export async function publishRepost(target: NostrEvent): Promise<void> {
  const hints = storeRelayHints(useSession.getState().pubkey);
  await publishEvent({
    kind: target.kind === 1 ? 6 : 16,
    content: "",
    tags: buildRepostTags(target, hints),
  });
}

/**
 * 削除リクエスト（NIP-09。自分のイベントだけ）。発行した時点で手元からは消える（publishEvent がストアに
 * kind:5 を入れる）。他人のイベント・発行の失敗は false。
 */
export async function requestDelete(event: NostrEvent, reason = ""): Promise<boolean> {
  if (event.pubkey !== useSession.getState().pubkey) return false;
  const tags = [
    ["e", event.id],
    ["k", String(event.kind)],
  ];
  if (event.kind >= 30000 && event.kind <= 39999) {
    const d = event.tags.find((t) => t[0] === "d")?.[1] ?? "";
    tags.push(["a", `${event.kind}:${event.pubkey}:${d}`]);
  }
  try {
    await publishEvent({ kind: 5, content: reason, tags });
    return true;
  } catch (e) {
    console.warn("[actions] Failed to send the deletion request", e);
    return false;
  }
}

/** 通報（NIP-56 kind:1984）。type = illegal / nudity / spam / impersonation / profanity / other */
export async function reportNote(event: NostrEvent, type: string): Promise<void> {
  await publishEvent({
    kind: 1984,
    content: "",
    tags: [
      ["e", event.id, type],
      ["p", event.pubkey],
    ],
  });
}

/** ユーザーの通報（NIP-56 kind:1984。ネイティブ reportUser）。e タグは付けない（投稿ではなく人への通報） */
export async function reportUser(pubkey: string, type: string): Promise<void> {
  await publishEvent({
    kind: 1984,
    content: "",
    tags: [["p", pubkey, type]],
  });
}

// ---- 押下状態（ストアにある自分の kind:7 / kind:6 から。ベストエフォート） ----

const reactionIndexes = new Map<string, Observable<ReadonlyMap<string, NostrEvent[]>>>();
const repostTargets = new Map<string, Observable<ReadonlySet<string>>>();

/** e タグの値（重複なし） */
function eTagValues(event: NostrEvent): Set<string> {
  const ids = new Set<string>();
  for (const t of event.tags) if (t[0] === "e" && typeof t[1] === "string") ids.add(t[1]);
  return ids;
}

/** 自分の kind:7 を対象 id（どれかの e タグ）ごとにまとめたもの。me ごとに 1 つを共有する */
export function myReactionIndex$(me: string): Observable<ReadonlyMap<string, NostrEvent[]>> {
  let index$ = reactionIndexes.get(me);
  if (!index$) {
    index$ = eventStore.timeline({ kinds: [7], authors: [me] }).pipe(
      map((events) => {
        const index = new Map<string, NostrEvent[]>();
        for (const event of events) {
          for (const id of eTagValues(event)) {
            const list = index.get(id);
            if (list) list.push(event);
            else index.set(id, [event]);
          }
        }
        return index;
      }),
      shareReplay({ bufferSize: 1, refCount: true }),
    );
    reactionIndexes.set(me, index$);
  }
  return index$;
}

/** 自分のリポスト（kind:6 と、[#810] kind:1 以外の汎用リポスト kind:16）の e タグの値。me ごとに 1 つを共有する */
export function myRepostTargets$(me: string): Observable<ReadonlySet<string>> {
  let targets$ = repostTargets.get(me);
  if (!targets$) {
    targets$ = eventStore.timeline({ kinds: [6, 16], authors: [me] }).pipe(
      map((events) => {
        const ids = new Set<string>();
        for (const event of events) for (const id of eTagValues(event)) ids.add(id);
        return ids;
      }),
      shareReplay({ bufferSize: 1, refCount: true }),
    );
    repostTargets.set(me, targets$);
  }
  return targets$;
}

/** この投稿に既定リアクションと同じ自分の kind:7 があるか */
export function useIsReacted(eventId: string): boolean {
  const me = useSession((s) => s.pubkey);
  const content = useDefaultReaction((s) => s.content);
  const reacted = use$(() => {
    if (!me) return undefined;
    const key = reactionKey(content);
    return myReactionIndex$(me).pipe(
      map((index) => index.get(eventId)?.some((e) => reactionKey(e.content) === key) ?? false),
      distinctUntilChanged(),
    );
  }, [me, eventId, content]);
  return reacted ?? false;
}

/** この投稿を自分がリポストしているか */
export function useIsReposted(eventId: string): boolean {
  const me = useSession((s) => s.pubkey);
  const reposted = use$(
    () =>
      me
        ? myRepostTargets$(me).pipe(
            map((ids) => ids.has(eventId)),
            distinctUntilChanged(),
          )
        : undefined,
    [me, eventId],
  );
  return reposted ?? false;
}

// ---- 自分の kind:7 の購読（ネイティブ起動時の myreactions） ----

let myReactions: { me: string; sub: Subscription } | null = null;

/** 自分の kind:7 を 100 件購読する。同じ me なら何もしない、別の me なら張り替える */
export function ensureMyReactionsSubscribed(me: string): void {
  if (myReactions?.me === me) return;
  myReactions?.sub.unsubscribe();
  myReactions = { me, sub: subscribe({ kinds: [7], authors: [me], limit: 100 }).subscribe() };
}
