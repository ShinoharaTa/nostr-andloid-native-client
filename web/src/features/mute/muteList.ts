import type { EventTemplate, NostrEvent } from "nostr-tools/pure";
import { create } from "zustand";
import type { ColumnKind } from "../../lib/columns";
import { quotePointerOf } from "../../lib/content/tags";
import { zapSenderOf } from "../../lib/nip57";
import type { Signer } from "../../nostr/signer";
import { plainTextOf } from "../actions/noteLinks";

/**
 * NIP-51 のミュートリスト（kind:10000）の解析・判定・再発行の中身（ネイティブの MuteList.kt / MuteMatcher.kt /
 * EventRepository.kt updateMuteList・publishMuteList の写し）と、自分のミュートリストのストア。
 * リレーとの行き来は muteSync.ts。
 */

/** ミュート対象の種別（kind:10000 のタグ名。ネイティブ MuteCategory） */
export type MuteCategory = "p" | "word" | "t" | "e";

const CATEGORIES: ReadonlySet<string> = new Set<MuteCategory>(["p", "word", "t", "e"]);

/** ミュート 1 件。isPublic = tags 由来、isPrivate = 暗号化した content 由来（両方 true もある） */
export type MuteEntry = { category: MuteCategory; value: string; isPublic: boolean; isPrivate: boolean };

/** 解析したミュートリスト（公開 tags + 復号した非公開を 1 つにまとめたもの） */
export type MuteList = {
  /** 元の kind:10000 の id（まだ無ければ null） */
  eventId: string | null;
  createdAt: number;
  entries: MuteEntry[];
  /** 非公開部分を復号できなかった（編集すると失うので編集しない。ネイティブ nip44Locked） */
  locked: boolean;
  /** 元の公開タグ（p / word / t / e 以外の未知タグも含む。再発行で保つ） */
  publicTags: string[][];
  /** 復号した非公開タグ（未知タグも含む）。locked なら [] */
  privateTags: string[][];
  /** 元の content（非公開部分を変えないときはそのまま戻す） */
  content: string;
};

/** kind:10000 がまだ無い（リレーが応答したが見つからない）ときのリスト */
export const EMPTY_MUTE_LIST: MuteList = {
  eventId: null,
  createdAt: 0,
  entries: [],
  locked: false,
  publicTags: [],
  privateTags: [],
  content: "",
};

/** ミュートの種別（p / word / t / e で値が文字列のタグ）。それ以外は null */
export function categoryOf(tag: readonly string[]): MuteCategory | null {
  if (tag.length < 2 || typeof tag[1] !== "string" || !CATEGORIES.has(tag[0])) return null;
  return tag[0] as MuteCategory;
}

function keyOf(category: MuteCategory, value: string): string {
  return `${category}\n${value}`;
}

/** 公開・非公開のタグを (種別, 値) で 1 件にまとめる（ネイティブ updateMuteList の ingestTags） */
export function mergeMuteEntries(
  publicTags: readonly string[][],
  privateTags: readonly string[][],
): MuteEntry[] {
  const merged = new Map<string, MuteEntry>();
  const ingest = (tags: readonly string[][], isPrivate: boolean) => {
    for (const tag of tags) {
      const category = categoryOf(tag);
      if (!category) continue;
      const key = keyOf(category, tag[1]);
      const current = merged.get(key);
      merged.set(key, {
        category,
        value: tag[1],
        isPublic: (current?.isPublic ?? false) || !isPrivate,
        isPrivate: (current?.isPrivate ?? false) || isPrivate,
      });
    }
  };
  ingest(publicTags, false);
  ingest(privateTags, true);
  return [...merged.values()];
}

/** 復号した content（タグの配列の JSON）。形が違えば null */
export function parseMuteContent(json: string): string[][] | null {
  try {
    const value: unknown = JSON.parse(json);
    if (!Array.isArray(value)) return null;
    if (!value.every((t) => Array.isArray(t) && t.every((v) => typeof v === "string"))) return null;
    return value as string[][];
  } catch {
    return null;
  }
}

// 復号済みの非公開タグ（アカウント + content → タグ）。NIP-07 で同じ content の復号を何度も頼まないため
const decrypted = new Map<string, string[][]>();

function cacheKey(pubkey: string, content: string): string {
  return `${pubkey}\n${content}`;
}

/** 自分で暗号化した content の中身を覚えておく（発行した版を読み直すときに復号を頼まない） */
export function rememberPrivateTags(pubkey: string, content: string, tags: string[][]): void {
  decrypted.set(cacheKey(pubkey, content), tags);
}

/** テスト専用: 復号の控えを消す */
export function clearMuteDecryptCacheForTest(): void {
  decrypted.clear();
}

/**
 * 非公開部分を復号する。content が空なら []。"?iv=" を含めば NIP-04（レガシー）、それ以外は NIP-44。
 * 署名者が無い・その方式を使えない・拒否された・中身が壊れている場合は null（= ロック中）。
 */
export async function decryptMuteContent(
  event: NostrEvent,
  signer: Signer | null,
): Promise<string[][] | null> {
  if (event.content.trim() === "") return [];
  const key = cacheKey(event.pubkey, event.content);
  const cached = decrypted.get(key);
  if (cached) return cached;
  const cipher = event.content.includes("?iv=") ? signer?.nip04 : signer?.nip44;
  if (!cipher) return null;
  try {
    const tags = parseMuteContent(await cipher.decrypt(event.pubkey, event.content));
    if (tags) decrypted.set(key, tags);
    return tags;
  } catch {
    return null;
  }
}

/** kind:10000 を解析する（null = まだ無い）。復号できなければ locked で、非公開の項目は含めない */
export async function readMuteList(event: NostrEvent | null, signer: Signer | null): Promise<MuteList> {
  if (!event) return EMPTY_MUTE_LIST;
  const privateTags = await decryptMuteContent(event, signer);
  return {
    eventId: event.id,
    createdAt: event.created_at,
    entries: mergeMuteEntries(event.tags, privateTags ?? []),
    locked: privateTags === null,
    publicTags: event.tags,
    privateTags: privateTags ?? [],
    content: event.content,
  };
}

/**
 * 公開 / 非公開の片側のタグを作り直す。元のタグ（未知タグ・余分な要素を含む）は、ミュートの項目なら
 * entries のその側に残っているものだけ残し、足りない項目を末尾に足す。
 */
export function rebuildMuteTags(
  original: readonly string[][],
  entries: readonly MuteEntry[],
  side: "isPublic" | "isPrivate",
): string[][] {
  const wanted = new Set(entries.filter((e) => e[side]).map((e) => keyOf(e.category, e.value)));
  const tags = original.filter((tag) => {
    const category = categoryOf(tag);
    return category === null || wanted.has(keyOf(category, tag[1]));
  });
  const present = new Set(
    tags.flatMap((tag) => {
      const category = categoryOf(tag);
      return category ? [keyOf(category, tag[1])] : [];
    }),
  );
  for (const entry of entries) {
    const key = keyOf(entry.category, entry.value);
    if (!entry[side] || present.has(key)) continue;
    tags.push([entry.category, entry.value]);
    present.add(key);
  }
  return tags;
}

function sameTags(a: readonly string[][], b: readonly string[][]): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * 発行する kind:10000（ネイティブ publishMuteList）。base（取り直した最新版）の未知タグ・非公開の未知タグを保つ。
 * 非公開部分が変わるときだけ encrypt で暗号化し直す（空なら content は ""）。encrypt が null（署名者が暗号を
 * 使えない）なら非公開部分は元の content のまま。privateTags は暗号化した中身（変えていなければ null）。
 */
export async function buildMuteListTemplate(
  base: MuteList,
  entries: readonly MuteEntry[],
  encrypt: ((plaintext: string) => Promise<string>) | null,
  nowSec: number,
): Promise<{ template: EventTemplate; privateTags: string[][] | null }> {
  const tags = rebuildMuteTags(base.publicTags, entries, "isPublic");
  let content = base.content;
  let privateTags: string[][] | null = null;
  if (encrypt) {
    const next = rebuildMuteTags(base.privateTags, entries, "isPrivate");
    if (!sameTags(next, base.privateTags)) {
      content = next.length === 0 ? "" : await encrypt(JSON.stringify(next));
      privateTags = next;
    }
  }
  return {
    template: {
      kind: 10000,
      content,
      tags,
      // 同じ秒に続けて変えても、置換可能イベントの新旧が崩れないように
      created_at: Math.max(nowSec, base.createdAt + 1),
    },
    privateTags,
  };
}

// ---- 判定（ネイティブ MuteMatcher） ----

export type MuteMatcher = {
  isEmpty: boolean;
  users: ReadonlySet<string>;
  /** 小文字にした部分一致のワード */
  wordSubs: readonly string[];
  /** /.../ で指定した正規表現（大文字小文字を無視） */
  wordRegex: readonly RegExp[];
  /** 小文字にしたハッシュタグ */
  hashtags: ReadonlySet<string>;
  threads: ReadonlySet<string>;
};

export const EMPTY_MUTE_MATCHER: MuteMatcher = {
  isEmpty: true,
  users: new Set(),
  wordSubs: [],
  wordRegex: [],
  hashtags: new Set(),
  threads: new Set(),
};

/** ミュートリストから判定器を作る。ワードは /.../ を正規表現（作れなければ捨てる）、それ以外を部分一致に振り分ける */
export function muteMatcherFrom(list: MuteList | null): MuteMatcher {
  if (!list || list.entries.length === 0) return EMPTY_MUTE_MATCHER;
  const values = (category: MuteCategory) =>
    list.entries.filter((e) => e.category === category).map((e) => e.value);
  const wordSubs: string[] = [];
  const wordRegex: RegExp[] = [];
  for (const word of values("word")) {
    if (word.length >= 3 && word.startsWith("/") && word.endsWith("/")) {
      try {
        wordRegex.push(new RegExp(word.slice(1, -1), "i"));
      } catch {
        // 壊れた正規表現は使わない
      }
    } else if (word.trim() !== "") {
      wordSubs.push(word.toLowerCase());
    }
  }
  const users = new Set(values("p"));
  const hashtags = new Set(values("t").map((t) => t.toLowerCase()));
  const threads = new Set(values("e"));
  return {
    isEmpty:
      users.size === 0 &&
      wordSubs.length === 0 &&
      wordRegex.length === 0 &&
      hashtags.size === 0 &&
      threads.size === 0,
    users,
    wordSubs,
    wordRegex,
    hashtags,
    threads,
  };
}

/** id でストアのイベントを引く（見つからなければ undefined） */
export type ResolveEvent = (id: string) => NostrEvent | undefined;

const noResolve: ResolveEvent = () => undefined;

/** 本文がミュートワード（部分一致・/正規表現/）に当たるか */
export function matchesWord(m: MuteMatcher, text: string): boolean {
  if (text === "") return false;
  const lower = text.toLowerCase();
  if (m.wordSubs.some((w) => w !== "" && lower.includes(w))) return true;
  return m.wordRegex.some((r) => r.test(text));
}

function firstTagValue(event: NostrEvent, name: string): string | undefined {
  return event.tags.find((t) => t[0] === name && typeof t[1] === "string")?.[1];
}

function lastTagValue(event: NostrEvent, name: string): string | undefined {
  return event.tags.findLast((t) => t[0] === name && typeof t[1] === "string")?.[1];
}

/** リポスト元。content の JSON（e タグの id と一致するもの）、無ければストアから。取れなければ undefined */
function repostTargetOf(repost: NostrEvent, resolve: ResolveEvent): NostrEvent | undefined {
  const id = firstTagValue(repost, "e");
  try {
    const value: unknown = repost.content ? JSON.parse(repost.content) : null;
    if (typeof value === "object" && value !== null) {
      const e = value as Record<string, unknown>;
      if (
        typeof e.id === "string" &&
        (id === undefined || e.id === id) &&
        typeof e.pubkey === "string" &&
        typeof e.content === "string" &&
        Array.isArray(e.tags)
      ) {
        return value as NostrEvent;
      }
    }
  } catch {
    // 壊れた JSON は e タグで引く
  }
  return id ? resolve(id) : undefined;
}

/** 引用元の著者（nevent / q タグの著者、無ければストアの引用元） */
function quotedAuthorOf(event: NostrEvent, resolve: ResolveEvent): string | undefined {
  const quote = quotePointerOf(event);
  if (!quote) return undefined;
  return quote.pointer.author ?? resolve(quote.pointer.id)?.pubkey;
}

function isPostMuted(m: MuteMatcher, note: NostrEvent, resolve: ResolveEvent): boolean {
  if (m.users.has(note.pubkey)) return true;
  if (m.users.size > 0) {
    const quoted = quotedAuthorOf(note, resolve);
    if (quoted !== undefined && m.users.has(quoted)) return true;
  }
  const hasWords = m.wordSubs.length > 0 || m.wordRegex.length > 0;
  for (const tag of note.tags) {
    if (tag.length < 2 || typeof tag[1] !== "string") continue;
    if (tag[0] === "t") {
      if (m.hashtags.has(tag[1].toLowerCase())) return true;
      if (hasWords && matchesWord(m, tag[1])) return true;
    } else if (tag[0] === "e" && m.threads.has(tag[1])) {
      return true;
    }
  }
  // ワードは表示用の本文（ネイティブ note.text ?: content。plainTextOf と同じ）で判定する（挙動3.2）
  if (hasWords && matchesWord(m, plainTextOf(note))) return true;
  return m.threads.has(note.id);
}

/**
 * 投稿がミュート対象か（ネイティブ MuteMatcher.muted(NoteUi)）: 著者・リポストした人・引用元の著者・
 * 本文とハッシュタグのワード・ハッシュタグ・スレッド（e タグ / 自身の id）。自分（me）の投稿は対象にしない。
 * リポストは元投稿で判定する（取れない間は e / p タグで判定）。
 */
export function isNoteMuted(
  m: MuteMatcher,
  event: NostrEvent,
  me: string | null,
  resolve: ResolveEvent = noResolve,
): boolean {
  if (m.isEmpty) return false;
  if (me !== null && event.pubkey === me) return false;
  if (event.kind === 6 || event.kind === 16) {
    if (m.users.has(event.pubkey)) return true;
    const target = repostTargetOf(event, resolve);
    if (target) return isNoteMuted(m, target, me, resolve);
    const author = firstTagValue(event, "p");
    const id = firstTagValue(event, "e");
    return (author !== undefined && m.users.has(author)) || (id !== undefined && m.threads.has(id));
  }
  return isPostMuted(m, event, resolve);
}

/** 通知がミュート対象か（ネイティブ MuteMatcher.muted(NotificationUi): 相手 = Zap は送った人） */
export function isNotificationMuted(m: MuteMatcher, event: NostrEvent): boolean {
  if (m.isEmpty) return false;
  const actor = event.kind === 9735 ? (zapSenderOf(event.tags) ?? event.pubkey) : event.pubkey;
  return m.users.has(actor);
}

/**
 * 自分のリアクション（ふぁぼ欄）の対象がミュート対象か（ネイティブ favs の muted(target)）。
 * 対象は最後の e タグ。ストアに無い間は p タグ（対象の著者）と e タグで判定する。
 */
export function isReactionTargetMuted(
  m: MuteMatcher,
  reaction: NostrEvent,
  me: string | null,
  resolve: ResolveEvent = noResolve,
): boolean {
  if (m.isEmpty) return false;
  const id = lastTagValue(reaction, "e");
  const target = id ? resolve(id) : undefined;
  if (target) return isNoteMuted(m, target, me, resolve);
  const author = lastTagValue(reaction, "p");
  if (author !== undefined && author !== me && m.users.has(author)) return true;
  return id !== undefined && m.threads.has(id);
}

/** カラムの種別ごとの「表示する」判定（通知 = 相手、ふぁぼ欄 = 対象、それ以外 = 投稿）。ミュートが空なら null */
export function muteVisibleFilter(
  kind: ColumnKind,
  m: MuteMatcher,
  me: string | null,
  resolve: ResolveEvent = noResolve,
): ((event: NostrEvent) => boolean) | null {
  if (m.isEmpty) return null;
  switch (kind) {
    case "NOTIFICATIONS":
      return (e) => !isNotificationMuted(m, e);
    case "FAVS":
      return (e) => !isReactionTargetMuted(m, e, me, resolve);
    default:
      return (e) => !isNoteMuted(m, e, me, resolve);
  }
}

// ---- 自分のミュートリスト ----

type MuteState = {
  /** 自分のミュートリスト。null = まだ分からない（未ログイン・読み込み中）。設定のミュート編集はこれを使う */
  list: MuteList | null;
  /** [#957] ブロックしたが、まだミュートリストに保存できていない人（block.ts。ネイティブ pendingBlocks） */
  pending: ReadonlySet<string>;
  /** list ＋ pending から作った判定器（どちらかが変わったときだけ作り直す。ネイティブ muteFilterFlow） */
  matcher: MuteMatcher;
};

export const useMute = create<MuteState>()(() => ({
  list: null,
  pending: new Set<string>(),
  matcher: EMPTY_MUTE_MATCHER,
}));

/**
 * 画面で隠す判定に使うミュート（list ＋ 保存待ちのブロック。ネイティブ muteFilterFlow）。
 * 保存待ちの人は、まだ list に入っていなければ公開のユーザー（＝ブロック）として足す。
 */
export function withPendingBlocks(list: MuteList | null, pending: ReadonlySet<string>): MuteList | null {
  const extra = [...pending].filter(
    (pk) => !(list?.entries.some((e) => e.category === "p" && e.value === pk) ?? false),
  );
  if (extra.length === 0) return list;
  const base = list ?? EMPTY_MUTE_LIST;
  return {
    ...base,
    entries: [
      ...base.entries,
      ...extra.map((pk): MuteEntry => ({ category: "p", value: pk, isPublic: true, isPrivate: false })),
    ],
  };
}

/** ストアのミュートリストを置き換える */
export function setMuteList(list: MuteList | null): void {
  useMute.setState((s) => ({ list, matcher: muteMatcherFrom(withPendingBlocks(list, s.pending)) }));
}

/** [#957] 保存待ちのブロックを置き換える（block.ts から） */
export function setPendingBlocks(pending: ReadonlySet<string>): void {
  useMute.setState((s) => ({ pending, matcher: muteMatcherFrom(withPendingBlocks(s.list, pending)) }));
}

/**
 * [#963] ユーザーのミュートの状態。公開の p = ブロック、非公開（暗号化）の p だけ = ミュート。
 * 両方に入っていればブロック。保存待ちのブロックもブロックとする。
 */
export type UserMuteState = "none" | "muted" | "blocked";

/** ブロック中か（ミュートリストの公開の p、または保存待ちのブロック） */
export function isBlocked(s: Pick<MuteState, "list" | "pending">, pubkey: string): boolean {
  if (s.pending.has(pubkey)) return true;
  return s.list?.entries.some((e) => e.category === "p" && e.value === pubkey && e.isPublic) ?? false;
}

/** ミュートだけしているか（非公開の p だけ。ブロック中なら false） */
export function isMutedOnly(s: Pick<MuteState, "list" | "pending">, pubkey: string): boolean {
  if (isBlocked(s, pubkey)) return false;
  return s.list?.entries.some((e) => e.category === "p" && e.value === pubkey && e.isPrivate) ?? false;
}

export function userMuteStateOf(s: Pick<MuteState, "list" | "pending">, pubkey: string): UserMuteState {
  if (isBlocked(s, pubkey)) return "blocked";
  return isMutedOnly(s, pubkey) ? "muted" : "none";
}

/** いまのユーザーのミュートの状態（ミュートリスト・保存待ちが変わったら描き直す） */
export function useUserMuteState(pubkey: string): UserMuteState {
  return useMute((s) => userMuteStateOf(s, pubkey));
}

/** いまの判定器（ミュートリストが変わったら描き直す） */
export function useMuteMatcher(): MuteMatcher {
  return useMute((s) => s.matcher);
}
