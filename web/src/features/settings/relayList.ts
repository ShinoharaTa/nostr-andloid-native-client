import { normalizeURL } from "applesauce-core/helpers/url";
import type { EventTemplate, NostrEvent } from "nostr-tools/pure";
import { INDEXER_RELAYS } from "../../lib/columnRequest";
import { unixNow } from "../../lib/time";
import { isRecognizedRelayTag, type RelayPref } from "../../nostr/outbox";
import { applyOwnRelayList, readRelays, requestOnce, writeRelays } from "../../nostr/pool";
import { PublishError, type PublishFailure, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";

/** 発行の直前に自分の kind:10002 を取り直す待ち時間（フォローの kind:3 と同じ） */
export const OWN_RELAYLIST_REFETCH_MS = 5_000;

/**
 * no-relay-list = 直前の取り直しでどのリレーからも応答が無かった（古い版で上書きしうるので止めた）。
 * stale = 編集を始めた時点の版と、取り直した最新版が違う（別の端末・クライアントでの変更や、読み込み前の
 * 既定リレーを編集していた場合。そのまま発行すると最新の内容を消すので止めた）。
 */
export type RelayListFailure = "no-relay-list" | "stale" | PublishFailure;

export class RelayListError extends Error {
  readonly reason: RelayListFailure;

  constructor(reason: RelayListFailure, options?: ErrorOptions) {
    super(`relay list failed: ${reason}`, options);
    this.name = "RelayListError";
    this.reason = reason;
  }
}

/** 追加する URL を確かめる。wss:// で URL として読めれば正規化した URL、だめなら null */
export function parseRelayInput(input: string): string | null {
  const value = input.trim();
  if (!value.startsWith("wss://")) return null;
  try {
    new URL(value);
    return normalizeURL(value);
  } catch {
    return null;
  }
}

/** 入力が ws:// のリレーか（Web 版では使えないので、追加欄で専用の文言を出す。#776） */
export function isWsRelayInput(input: string): boolean {
  return input.trim().toLowerCase().startsWith("ws://");
}

/**
 * r タグ（ネイティブ publishRelayList と同じ: read + write = マーカー無し / 片方だけ = read・write /
 * 両方オフは出さない）。URL は末尾の / を落とす。
 */
export function relayTagsOf(prefs: readonly RelayPref[]): string[][] {
  const tags: string[][] = [];
  for (const p of prefs) {
    const url = p.url.replace(/\/$/, "");
    if (p.read && p.write) tags.push(["r", url]);
    else if (p.read) tags.push(["r", url, "read"]);
    else if (p.write) tags.push(["r", url, "write"]);
  }
  return tags;
}

/**
 * 発行する kind:10002。取り直した版（base）の r 以外のタグ（他のクライアントが足した未知のタグ）と
 * content はそのまま残し、relayPrefsFromEvent が解釈した r タグ（wss://）だけを prefs で置き換える。
 * 解釈できなかった r タグ（ws:// や不正な URL）は others と同様にそのまま引き継ぐ（#580）。
 */
export function buildRelayListTemplate(
  base: NostrEvent | null,
  prefs: readonly RelayPref[],
  nowSec: number,
): EventTemplate {
  const others = (base?.tags ?? []).filter((t) => !isRecognizedRelayTag(t));
  return {
    kind: 10002,
    content: base?.content ?? "",
    tags: [...relayTagsOf(prefs), ...others],
    // 同じ秒に続けて保存しても、置換可能イベントの新旧が崩れないように
    created_at: Math.max(nowSec, (base?.created_at ?? 0) + 1),
  };
}

/**
 * リレーの一覧を NIP-65（kind:10002）として発行し、リレー集合へ反映する（ネイティブ publishRelayList）。
 * 直前に自分の kind:10002 をリレーとインデクサから取り直し、どのリレーからも応答が無ければ発行せず
 * RelayListError("no-relay-list")（#478 / toggleFollow と同じ規則）。送り先は今の write・新しい write・インデクサ。
 * 署名の失敗は同じ reason の RelayListError。
 */
export async function publishRelayList(
  me: string,
  prefs: readonly RelayPref[],
  /** 編集を始めた時点で画面に出ていた自分の kind:10002 の id（無かったら null） */
  basedOnId: string | null,
): Promise<void> {
  // complete = 少なくとも 1 つのリレーが応答した、error = どこからも応答が無かった
  const reached = await new Promise<boolean>((resolve) => {
    requestOnce(
      [...new Set([...readRelays(), ...writeRelays(), ...INDEXER_RELAYS])],
      [{ kinds: [10002], authors: [me], limit: 1 }],
      OWN_RELAYLIST_REFETCH_MS,
    ).subscribe({ complete: () => resolve(true), error: () => resolve(false) });
  });
  if (!reached) throw new RelayListError("no-relay-list");
  const base = eventStore.getReplaceable(10002, me) ?? null;
  if ((base?.id ?? null) !== basedOnId) throw new RelayListError("stale");

  const template = buildRelayListTemplate(base, prefs, unixNow());
  const targets = [
    ...new Set([...writeRelays(), ...prefs.filter((p) => p.write).map((p) => p.url), ...INDEXER_RELAYS]),
  ];
  try {
    await publishEvent(template, { relays: targets });
  } catch (e) {
    if (e instanceof PublishError) throw new RelayListError(e.reason, { cause: e });
    throw e;
  }
  applyOwnRelayList(me, prefs);
}
