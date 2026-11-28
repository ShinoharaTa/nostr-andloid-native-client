import { getSeenRelays } from "applesauce-core/helpers/relays";
import { type NostrEvent, verifyEvent } from "nostr-tools/pure";
import type { Observable, Subscription } from "rxjs";
import { create } from "zustand";
import { t } from "../../i18n";
import { INDEXER_RELAYS } from "../../lib/columnRequest";
import { unixNow } from "../../lib/time";
import { writeRelaysOf } from "../../nostr/outbox";
import { readRelays, requestOnce } from "../../nostr/pool";
import { publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { showToast } from "../../ui/toast";
import { storeRelayHints } from "../compose/relayHints";
import { isPublicRelay, normalizeRelayUrl } from "../compose/tags";
import { yoEmojiUrl } from "./yoEmoji";
import {
  latestProfileOf,
  YO_FETCH_SETTLE_MS,
  YO_FETCH_TIMEOUT_MS,
  yoDraft,
  yoRecipientOf,
  yoReplyCreatedAt,
} from "./yoModel";

/**
 * [#1025] Yo（nostr-yo）を送る・返す（ネイティブ #1024 の EventRepository.sendYo / sendYoNow / publishYo の Web 版）。
 * 押したときだけ送る（自動では返さない）。
 */

/** sent = 署名して送信キューに積んだ / no-profile = 相手の kind:0 が見つからず送らなかった / failed = 署名できない等 */
export type YoResult = "sent" | "no-profile" | "failed";

/**
 * sending = いま Yo を送っている相手（ボタンを押せなくする）。
 * sentAt = このセッションで Yo を送った相手 → その Yo の `created_at`。「返しました」は通知の組み直しを待って付くので、
 * 送った直後は間が空く。その間に「Yo を返す」がまた押せると二重に返せてしまうので、ボタンはこれも見る（ネイティブ yoSentAtFlow）
 */
export const useYoSending = create<{ sending: ReadonlySet<string>; sentAt: ReadonlyMap<string, number> }>(
  () => ({ sending: new Set<string>(), sentAt: new Map<string, number>() }),
);

/** アカウントが変わったら、前のアカウントで送った Yo を「返しました」に数えない */
useSession.subscribe((state, prev) => {
  if (state.pubkey !== prev.pubkey) useYoSending.setState({ sentAt: new Map() });
});

/** 対象（相手の kind:0）を取りに行くとき、相手の書き込みリレーから足す本数の上限 */
const YO_AUTHOR_RELAYS = 3;

/**
 * recipient に Yo を送る（通知の「Yo を返す」・プロフィールの「Yo を送る」）。結果はトーストで知らせる
 * （通知の行は仮想リストから外れることがあるので、行では待たない）。同じ相手へ送っている間の 2 回目は無視する。
 */
export function sendYo(recipient: string): void {
  const { sending } = useYoSending.getState();
  if (sending.has(recipient)) return;
  useYoSending.setState({ sending: new Set([...sending, recipient]) });
  sendYoNow(recipient)
    .catch((e): YoResult => {
      console.warn("[yo] Failed to send a Yo", e);
      return "failed";
    })
    .then((result) => {
      const next = new Set(useYoSending.getState().sending);
      next.delete(recipient);
      useYoSending.setState({ sending: next });
      showToast(yoResultMessage(result));
    });
}

function yoResultMessage(result: YoResult): string {
  switch (result) {
    case "sent":
      return t("yo_sent");
    case "no-profile":
      return t("yo_no_profile");
    case "failed":
      return t("yo_failed");
  }
}

/**
 * 相手の最新の kind:0 を取りに行き、それへのリアクションとして Yo を発行する（SPEC: 対象は「署名の通る最新の kind:0」）。
 * 手元（EventStore）に相手の kind:0 があっても古いことがあるので、送るたびに取り直す。
 * 発行はほかのリアクションと同じ経路（publishEvent: ストアに入れ、送信キューに積む。client タグも付く）。
 *  - リレーヒントは、対象の kind:0 を受け取ったリレー（公開のリレーのときだけ）。無ければ相手の書き込みリレー
 *  - `created_at` は、その人から届いているいちばん新しい Yo より前にしない（yoReplyCreatedAt）。相手の時計が進んでいる
 *    ときだけ未来の時刻になる（最大 10 分）。未来の時刻を受け付けないリレーでは、未送信の再送に回る
 */
export async function sendYoNow(recipient: string, timeoutMs = YO_FETCH_TIMEOUT_MS): Promise<YoResult> {
  const me = useSession.getState().pubkey;
  if (me === null || recipient === me) return "failed";
  const profile = await fetchLatestProfile(recipient, timeoutMs);
  if (profile === null) return "no-profile";
  // 取りに行っている間（数秒）にログアウト・アカウントの切り替えがあったら送らない（別の鍵で署名しない）
  if (useSession.getState().pubkey !== me) return "failed";
  const now = unixNow();
  const seen = [...(getSeenRelays(profile) ?? [])].map(normalizeRelayUrl).find(isPublicRelay);
  const hint = seen ?? storeRelayHints(me).pubkeyHint(recipient);
  const signed = await publishEvent(
    // 絵文字の画像は、設定の「Yo の絵文字」で選んだもの（標準は nostr-yo の絵）
    yoDraft(
      recipient,
      profile.id,
      hint,
      yoReplyCreatedAt(now, latestYoFrom(recipient, me, now)),
      yoEmojiUrl(),
    ),
  );
  useYoSending.setState((s) => ({ sentAt: new Map(s.sentAt).set(recipient, signed.created_at) }));
  return "sent";
}

/** その人から自分あてに届いている Yo のうち、いちばん新しい時刻（無ければ null） */
function latestYoFrom(sender: string, me: string, now: number): number | null {
  let latest: number | null = null;
  for (const event of eventStore.getByFilters({ kinds: [7], authors: [sender], "#p": [me] })) {
    if (yoRecipientOf(event, now) !== me) continue;
    if (latest === null || event.created_at > latest) latest = event.created_at;
  }
  return latest;
}

/**
 * pubkey の最新の kind:0（署名を確かめたもの）。見つからなければ null。
 * 送り先は 自分の読み込みリレー ∪ インデクサ ∪ 相手の書き込みリレー（手元に kind:10002 があれば）。
 * 1 件届いたら YO_FETCH_SETTLE_MS だけ他のリレーを待ち、全リレーの EOSE が揃うか timeoutMs で打ち切る。
 * 届いた版は EventStore にも入る（プロフィールの表示も新しくなる）。
 */
async function fetchLatestProfile(pubkey: string, timeoutMs: number): Promise<NostrEvent | null> {
  const relays = [
    ...new Set([...readRelays(), ...INDEXER_RELAYS, ...writeRelaysOf(pubkey).slice(0, YO_AUTHOR_RELAYS)]),
  ];
  // 「1 件届いた」は、その人の kind:0 の形をしたものだけ数える（関係ないイベントを返すリレーで早く打ち切らない）
  const events = await collectUntilSettled(
    requestOnce(relays, [{ kinds: [0], authors: [pubkey], limit: 1 }], timeoutMs),
    YO_FETCH_SETTLE_MS,
    (event) => event.kind === 0 && event.pubkey === pubkey,
  );
  return latestProfileOf(events, pubkey, verifyEvent);
}

/** source が終わるか、counts に合う 1 件目が届いてから settleMs 経つまでに届いたイベント（失敗しても、届いた分を返す） */
function collectUntilSettled(
  source: Observable<NostrEvent>,
  settleMs: number,
  counts: (event: NostrEvent) => boolean,
): Promise<NostrEvent[]> {
  return new Promise((resolve) => {
    const events: NostrEvent[] = [];
    let settle: ReturnType<typeof setTimeout> | undefined;
    let finished = false;
    let sub: Subscription | undefined;
    const done = () => {
      if (finished) return;
      finished = true;
      if (settle !== undefined) clearTimeout(settle);
      sub?.unsubscribe();
      resolve(events);
    };
    sub = source.subscribe({
      next: (event) => {
        events.push(event);
        if (counts(event)) settle ??= setTimeout(done, settleMs);
      },
      error: done,
      complete: done,
    });
    // 購読した瞬間に終わっていたら、ここで解除する（done の時点では sub がまだ無い）
    if (finished) sub.unsubscribe();
  });
}
