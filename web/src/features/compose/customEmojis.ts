import type { AddressPointer } from "applesauce-core/helpers/pointers";
import { use$ } from "applesauce-react/hooks/use-$";
import type { EventTemplate, NostrEvent } from "nostr-tools/pure";
import { useMemo } from "react";
import { combineLatest, map, type Observable, of, switchMap } from "rxjs";
import { unixNow } from "../../lib/time";
import { refetchOwnReplaceable } from "../../nostr/ownReplaceable";
import { PublishError, type PublishFailure, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";

export type CustomEmoji = { shortcode: string; url: string };

/** kind:10030 が参照する絵文字セット（a タグの 30030:<pubkey>:<d>）。d は ":" を含んでよい */
export function emojiSetPointers(list?: NostrEvent): AddressPointer[] {
  if (!list) return [];
  const out: AddressPointer[] = [];
  for (const tag of list.tags) {
    if (tag[0] !== "a" || typeof tag[1] !== "string" || !tag[1].startsWith("30030:")) continue;
    const [, pubkey = "", ...rest] = tag[1].split(":");
    if (pubkey === "") continue;
    out.push({ kind: 30030, pubkey, identifier: rest.join(":") });
  }
  return out;
}

/**
 * 自分のカスタム絵文字（kind:10030 直下 → 参照するセットの順。同じ shortcode は先勝ち）を shortcode 昇順で。
 * 画像は https のみ（計画 6.3）。
 */
export function customEmojisFrom(
  list: NostrEvent | undefined,
  sets: readonly (NostrEvent | undefined)[],
): CustomEmoji[] {
  const byCode = new Map<string, string>();
  for (const event of [list, ...sets]) {
    if (!event) continue;
    for (const tag of event.tags) {
      if (tag[0] !== "emoji" || tag.length < 3) continue;
      const [, shortcode, url] = tag;
      if (shortcode.trim() === "" || !url.startsWith("https://") || byCode.has(shortcode)) continue;
      byCode.set(shortcode, url);
    }
  }
  return [...byCode]
    .map(([shortcode, url]) => ({ shortcode, url }))
    .sort((a, b) => (a.shortcode < b.shortcode ? -1 : a.shortcode > b.shortcode ? 1 : 0));
}

type EmojiSources = { list: NostrEvent | undefined; sets: (NostrEvent | undefined)[] };

/** 自分のカスタム絵文字（ストアに無ければローダが取りに行く） */
export function useCustomEmojis(me: string | null): CustomEmoji[] {
  const sources = use$((): Observable<EmojiSources> | undefined => {
    if (!me) return undefined;
    return eventStore.replaceable({ kind: 10030, pubkey: me }).pipe(
      switchMap((list) => {
        const pointers = emojiSetPointers(list);
        const sets$: Observable<(NostrEvent | undefined)[]> =
          pointers.length === 0 ? of([]) : combineLatest(pointers.map((p) => eventStore.replaceable(p)));
        return sets$.pipe(map((sets) => ({ list, sets })));
      }),
    );
  }, [me]);
  return useMemo(() => (sources ? customEmojisFrom(sources.list, sources.sets) : []), [sources]);
}

// ---- 編集（設定「カスタム絵文字」。#536。ネイティブ EmojiEditorSettings / publishEmojiList） ----

/** shortcode に使える文字（英数字と _ -）。前後の空白・: は落として判定する */
const SHORTCODE_RE = /^[A-Za-z0-9_-]+$/;

/** 追加欄の shortcode を検証する。使えなければ null */
export function parseEmojiShortcode(raw: string): string | null {
  const value = raw.trim().replace(/^:/, "").replace(/:$/, "");
  return value !== "" && SHORTCODE_RE.test(value) ? value : null;
}

/** 追加欄の画像 URL を検証する（https のみ）。使えなければ null */
export function parseEmojiUrl(raw: string): string | null {
  const value = raw.trim();
  if (!value.startsWith("https://")) return null;
  try {
    new URL(value);
    return value;
  } catch {
    return null;
  }
}

/**
 * 設定「カスタム絵文字」の下書きの差分。removed = 画面で削除した shortcode、added = 画面で足した絵文字（足した順）。
 * 同じ shortcode の画像を差し替えたときは removed と added の両方に入る。
 */
export type EmojiListChanges = { removed: readonly string[]; added: readonly CustomEmoji[] };

/** 編集前（before）と下書き（after）の差分。shortcode と URL の組が同じものは編集していないとみなす */
export function emojiListChanges(
  before: readonly CustomEmoji[],
  after: readonly CustomEmoji[],
): EmojiListChanges {
  const same = (a: CustomEmoji, b: CustomEmoji) => a.shortcode === b.shortcode && a.url === b.url;
  return {
    removed: before.filter((b) => !after.some((a) => same(a, b))).map((b) => b.shortcode),
    added: after.filter((a) => !before.some((b) => same(a, b))),
  };
}

/**
 * 発行する kind:10030。取り直した版（base）のタグを作り直さず土台にし、削除した shortcode の emoji タグだけ除いて
 * 追加分を末尾に足す（#762）。編集していない emoji タグ（https でない・4 要素目付きも）は要素も順序もそのまま、
 * a タグ等の 30030 セット参照・未知タグ・content も保つ。
 */
export function buildEmojiListTemplate(
  base: NostrEvent | null,
  changes: EmojiListChanges,
  nowSec: number,
): EventTemplate {
  const removed = new Set(changes.removed);
  const kept = (base?.tags ?? []).filter((t) => !(t[0] === "emoji" && removed.has(t[1])));
  return {
    kind: 10030,
    content: base?.content ?? "",
    tags: [...kept, ...changes.added.map((e) => ["emoji", e.shortcode, e.url])],
    // 同じ秒に続けて保存しても、置換可能イベントの新旧が崩れないように
    created_at: Math.max(nowSec, (base?.created_at ?? 0) + 1),
  };
}

/**
 * no-emoji-list = 直前の取り直しでどのリレーからも応答が無かった（古い版で上書きしうるので止めた）。
 * stale = 編集を始めた時点の版と、取り直した最新版が違う（別の端末・クライアントでの変更を消すので止めた）。
 * duplicate = 足そうとした shortcode が取り直した最新版に既にある（appendToEmojiList）。
 */
export type EmojiListFailure = "no-emoji-list" | "stale" | "duplicate" | PublishFailure;

export class EmojiListError extends Error {
  readonly reason: EmojiListFailure;

  constructor(reason: EmojiListFailure, options?: ErrorOptions) {
    super(`emoji list failed: ${reason}`, options);
    this.name = "EmojiListError";
    this.reason = reason;
  }
}

/**
 * カスタム絵文字リスト（kind:10030）を発行する（設定「カスタム絵文字」の「保存して公開」）。#478 の規則:
 * 発行の直前に自分の最新版を read ∪ write ∪ インデクサから取り直し、どのリレーからも応答が無ければ発行しない
 * （no-emoji-list）。編集を始めた時点の版（basedOnId）と取り直した最新版の id が違えば発行しない（stale）。
 * 発行するタグは取り直した最新版に下書きの差分（changes）だけ当てたもの（buildEmojiListTemplate）。
 */
export async function publishEmojiList(
  me: string,
  changes: EmojiListChanges,
  basedOnId: string | null,
): Promise<void> {
  let base: NostrEvent | null;
  try {
    base = await refetchOwnReplaceable(me, 10030);
  } catch (e) {
    throw new EmojiListError("no-emoji-list", { cause: e });
  }
  if ((base?.id ?? null) !== basedOnId) throw new EmojiListError("stale");
  const template = buildEmojiListTemplate(base, changes, unixNow());
  try {
    await publishEvent(template);
  } catch (e) {
    if (e instanceof PublishError) throw new EmojiListError(e.reason, { cause: e });
    throw e;
  }
}

/**
 * 自分のカスタム絵文字リスト（kind:10030）の末尾に 1 件足して発行する（絵文字作成ページ /emoji。
 * docs/emoji-maker.md §7.4）。#478 の規則: 発行の直前に自分の最新版を取り直し、どのリレーからも応答が無ければ
 * 発行しない（no-emoji-list）。取り直した最新版に足すので編集の起点は無く、stale にはならない。
 * 同じ shortcode の emoji タグが最新版にあれば発行しない（duplicate）。最新版のタグは作り直さず
 * （順序・https でない emoji タグ・4 要素目のある emoji タグも含めて）すべてそのまま保ち、末尾に 1 つ足す。content もそのまま。
 */
export async function appendToEmojiList(me: string, emoji: CustomEmoji): Promise<void> {
  let base: NostrEvent | null;
  try {
    base = await refetchOwnReplaceable(me, 10030);
  } catch (e) {
    throw new EmojiListError("no-emoji-list", { cause: e });
  }
  const tags = base?.tags ?? [];
  if (tags.some((t) => t[0] === "emoji" && t[1] === emoji.shortcode)) throw new EmojiListError("duplicate");
  const template: EventTemplate = {
    kind: 10030,
    content: base?.content ?? "",
    tags: [...tags, ["emoji", emoji.shortcode, emoji.url]],
    // 同じ秒に続けて保存しても、置換可能イベントの新旧が崩れないように
    created_at: Math.max(unixNow(), (base?.created_at ?? 0) + 1),
  };
  try {
    await publishEvent(template);
  } catch (e) {
    if (e instanceof PublishError) throw new EmojiListError(e.reason, { cause: e });
    throw e;
  }
}
