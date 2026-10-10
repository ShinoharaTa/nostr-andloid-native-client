import { decode, npubEncode } from "nostr-tools/nip19";
import { t } from "../i18n";
import {
  CANONICAL_SUBTITLE,
  CANONICAL_TITLE,
  columnDisplaySubtitle,
  columnDisplayTitle,
} from "../i18n/columnTitles";

/**
 * デッキのカラム定義（ネイティブの Models.kt の ColumnSpec / ReqFilter と ColumnTemplate.kt の写し）。
 * 保存・同期する JSON はネイティブ（kotlinx.serialization, encodeDefaults = false）とバイト単位で同じにする。
 */

export type ColumnKind =
  | "FOLLOWING"
  | "HASHTAG"
  | "NOTIFICATIONS"
  | "DM"
  | "GLOBAL"
  | "PROFILE"
  | "FAVS"
  | "LIST"
  | "THREAD"
  | "CHANNEL_LIST"
  | "CHANNEL_ROOM"
  /** NIP-38 のステータス（Web だけの種別。ネイティブは知らない種別の行として捨てる） */
  | "STATUS";

export type ColumnRenderer = "FEED" | "THREAD" | "CHANNEL_LIST" | "ROOM";

/** カラム幅（S=280 / M=340 / L=460）。M が既定 */
export type ColumnWidth = "S" | "M" | "L";

const COLUMN_KINDS: ReadonlySet<string> = new Set<ColumnKind>([
  "FOLLOWING",
  "HASHTAG",
  "NOTIFICATIONS",
  "DM",
  "GLOBAL",
  "PROFILE",
  "FAVS",
  "LIST",
  "THREAD",
  "CHANNEL_LIST",
  "CHANNEL_ROOM",
  "STATUS",
]);
const COLUMN_RENDERERS: ReadonlySet<string> = new Set<ColumnRenderer>([
  "FEED",
  "THREAD",
  "CHANNEL_LIST",
  "ROOM",
]);

/** Nostr REQ のフィルタ（ネイティブの ReqFilter。プロパティの順 = 宣言順 = JSON のキー順） */
export type ReqFilter = {
  kinds: number[];
  authors: string[];
  hashtags: string[];
  relays: string[];
  since: number | null;
  /** NIP-28: kind:42 を #e でチャンネルに絞る */
  channelId: string | null;
  /** NIP-50 全文検索（旧形式の検索カラム） */
  search: string | null;
  /** NIP-10 スレッドの起点 */
  eventId: string | null;
  /** キーワード・タグフィードの単語（1 語 = 1 フィルタ） */
  words: string[];
};

export function defaultFilter(): ReqFilter {
  return {
    kinds: [1],
    authors: [],
    hashtags: [],
    relays: [],
    since: null,
    channelId: null,
    search: null,
    eventId: null,
    words: [],
  };
}

/** デッキの 1 カラム = 1 つの REQ。pinned = 永続（保存・同期の対象）、false = 一時カラム */
export type ColumnSpec = {
  id: string;
  title: string;
  subtitle: string;
  kind: ColumnKind;
  renderer: ColumnRenderer;
  filter: ReqFilter;
  pinned: boolean;
  order: number;
};

/** NIP-78（kind:30078, d=app.nostrdeck:deck-columns）の content の 1 件。既定と等しい値は省く */
export type DeckColumnDto = {
  id: string;
  title: string;
  subtitle?: string;
  kind: ColumnKind;
  renderer: ColumnRenderer;
  filter?: Partial<ReqFilter>;
  order?: number;
};

/** 既定と等しいプロパティを除いたフィルタ（宣言順） */
function filterFields(f: ReqFilter): Partial<ReqFilter> {
  const fields: Partial<ReqFilter> = {};
  if (!(f.kinds.length === 1 && f.kinds[0] === 1)) fields.kinds = f.kinds;
  if (f.authors.length > 0) fields.authors = f.authors;
  if (f.hashtags.length > 0) fields.hashtags = f.hashtags;
  if (f.relays.length > 0) fields.relays = f.relays;
  if (f.since !== null) fields.since = f.since;
  if (f.channelId !== null) fields.channelId = f.channelId;
  if (f.search !== null) fields.search = f.search;
  if (f.eventId !== null) fields.eventId = f.eventId;
  if (f.words.length > 0) fields.words = f.words;
  return fields;
}

/** ReqFilter の JSON（ネイティブの filter_json と同じ。既定のフォロー中は "{}"） */
export function encodeReqFilter(f: ReqFilter): string {
  return JSON.stringify(filterFields(f));
}

function isIntArray(value: unknown): value is number[] {
  return Array.isArray(value) && value.every((v) => Number.isInteger(v));
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((v) => typeof v === "string");
}

/**
 * JSON から読んだ値を ReqFilter へ。未知のキーは無視し、欠けたキーは既定で埋める。
 * 型が違う（kotlinx が例外を投げる）場合は null。
 */
export function decodeReqFilter(json: unknown): ReqFilter | null {
  if (typeof json !== "object" || json === null || Array.isArray(json)) return null;
  const o = json as Record<string, unknown>;
  const f = defaultFilter();
  for (const key of ["authors", "hashtags", "relays", "words"] as const) {
    if (!Object.hasOwn(o, key)) continue;
    const value = o[key];
    if (!isStringArray(value)) return null;
    f[key] = [...value];
  }
  if (Object.hasOwn(o, "kinds")) {
    if (!isIntArray(o.kinds)) return null;
    f.kinds = [...o.kinds];
  }
  if (Object.hasOwn(o, "since")) {
    const since = o.since;
    if (since !== null && !Number.isInteger(since)) return null;
    f.since = since as number | null;
  }
  for (const key of ["channelId", "search", "eventId"] as const) {
    if (!Object.hasOwn(o, key)) continue;
    const value = o[key];
    if (value !== null && typeof value !== "string") return null;
    f[key] = value;
  }
  return f;
}

/** order の昇順（同じ order は元の順を保つ） */
export function sortByOrder(specs: readonly ColumnSpec[]): ColumnSpec[] {
  return [...specs].sort((a, b) => a.order - b.order);
}

/**
 * カラム構成の JSON（NIP-78 の content と同じ DeckColumnDto 配列）。
 * order で並べて 0 から振り直し、既定と等しい subtitle / filter / order は省く。
 */
export function encodeDeckColumns(specs: readonly ColumnSpec[]): string {
  const dtos = sortByOrder(specs).map((s, i) => {
    // キーの順 = DeckColumnDto の宣言順（id, title, subtitle, kind, renderer, filter, order）
    const dto: Record<string, unknown> = { id: s.id, title: s.title };
    if (s.subtitle !== "") dto.subtitle = s.subtitle;
    dto.kind = s.kind;
    dto.renderer = s.renderer;
    const filter = filterFields(s.filter);
    if (Object.keys(filter).length > 0) dto.filter = filter;
    if (i !== 0) dto.order = i;
    return dto as DeckColumnDto;
  });
  return JSON.stringify(dtos);
}

function decodeDeckColumn(row: unknown): ColumnSpec | null {
  if (typeof row !== "object" || row === null || Array.isArray(row)) return null;
  const o = row as Record<string, unknown>;
  const { id, title, kind, renderer } = o;
  if (typeof id !== "string" || typeof title !== "string") return null;
  if (typeof kind !== "string" || !COLUMN_KINDS.has(kind)) return null;
  if (typeof renderer !== "string" || !COLUMN_RENDERERS.has(renderer)) return null;
  const subtitle = Object.hasOwn(o, "subtitle") ? o.subtitle : "";
  if (typeof subtitle !== "string") return null;
  const filter = Object.hasOwn(o, "filter") ? decodeReqFilter(o.filter) : defaultFilter();
  if (filter === null) return null;
  const order = Object.hasOwn(o, "order") ? o.order : 0;
  if (!Number.isInteger(order)) return null;
  return {
    id,
    title,
    subtitle,
    kind: kind as ColumnKind,
    renderer: renderer as ColumnRenderer,
    filter,
    pinned: true,
    order: order as number,
  };
}

/**
 * カラム構成の JSON を読む。全体が壊れていれば null。
 * 行は 1 件ずつ検証し、未知の kind / renderer・不正な filter などの行だけを捨てる（ネイティブと同じ）。
 */
export function decodeDeckColumns(text: string): ColumnSpec[] | null {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (!Array.isArray(value)) return null;
  return value.map(decodeDeckColumn).filter((spec): spec is ColumnSpec => spec !== null);
}

/** 追加できるテンプレ */
export type TemplateId =
  | "FOLLOWING"
  | "GLOBAL"
  | "NOTIFICATIONS"
  | "DM"
  | "PROFILE"
  | "SEARCH"
  | "HASHTAG"
  | "FAVS"
  | "CHANNEL_LIST"
  | "STATUS";

/** テンプレの設定種別 */
export type ColumnConfig = "NONE" | "TEXT" | "NOTIF_FILTER" | "RELAY_SET";

export type ColumnTemplate = {
  template: TemplateId;
  config: ColumnConfig;
  /** 一覧のアイコンに使う種別 */
  iconKind: ColumnKind;
};

/**
 * カラム追加の一覧（ネイティブの ColumnTemplate.entries と同じ順。表示名は templateLabel / templateHint）。
 */
export const TEMPLATES: readonly ColumnTemplate[] = [
  { template: "FOLLOWING", config: "NONE", iconKind: "FOLLOWING" },
  { template: "GLOBAL", config: "RELAY_SET", iconKind: "GLOBAL" },
  { template: "NOTIFICATIONS", config: "NOTIF_FILTER", iconKind: "NOTIFICATIONS" },
  { template: "DM", config: "NONE", iconKind: "DM" },
  { template: "PROFILE", config: "TEXT", iconKind: "PROFILE" },
  { template: "SEARCH", config: "TEXT", iconKind: "GLOBAL" },
  { template: "HASHTAG", config: "TEXT", iconKind: "HASHTAG" },
  { template: "FAVS", config: "NONE", iconKind: "FAVS" },
  { template: "CHANNEL_LIST", config: "NONE", iconKind: "CHANNEL_LIST" },
  { template: "STATUS", config: "NONE", iconKind: "STATUS" },
];

/** テンプレの表示名（ネイティブの tpl_* / nav_dm / nav_public_chat） */
export function templateLabel(template: TemplateId): string {
  switch (template) {
    case "FOLLOWING":
      return t("tpl_following");
    case "GLOBAL":
      return t("tpl_global");
    case "NOTIFICATIONS":
      return t("tpl_notifications");
    case "DM":
      return t("nav_dm");
    case "PROFILE":
      return t("tpl_profile");
    case "SEARCH":
      return t("tpl_search");
    case "HASHTAG":
      return t("tpl_hashtag");
    case "FAVS":
      return t("tpl_favs");
    case "CHANNEL_LIST":
      return t("nav_public_chat");
    case "STATUS":
      return t("web_tpl_status");
  }
}

/** テンプレの入力欄のヒント（無ければ undefined） */
export function templateHint(template: TemplateId): string | undefined {
  switch (template) {
    case "GLOBAL":
      return t("tpl_global_hint");
    case "PROFILE":
      return t("tpl_profile_hint");
    case "SEARCH":
      return t("tpl_search_hint");
    case "HASHTAG":
      return t("tpl_hashtag_hint");
    case "FAVS":
      return t("tpl_favs_hint");
    case "CHANNEL_LIST":
      return t("tpl_channel_list_hint");
    case "STATUS":
      return t("web_tpl_status_hint");
    default:
      return undefined;
  }
}

/** 通知カラムで選べる種別（この順）。表示名は notifKindLabel */
export const NOTIF_KINDS: readonly { kind: number }[] = [
  { kind: 1 },
  { kind: 7 },
  { kind: 9735 },
  { kind: 6 },
];

/** 通知種別の表示名 */
export function notifKindLabel(kind: number): string {
  switch (kind) {
    case 1:
      return t("notif_mention");
    case 7:
      return t("notif_reaction");
    case 9735:
      return t("notif_zap");
    case 6:
      return t("notif_repost");
    default:
      return String(kind);
  }
}

/** カラムの表示名（保存したタイトルを言語に合わせて引く。ユーザー入力のタイトルはそのまま） */
export function columnLabel(spec: Pick<ColumnSpec, "title">): string {
  return columnDisplayTitle(spec.title);
}

/** 新しいカラムの id（col_<prefix>_<unix秒>）。同じ秒に作って衝突したら秒を進める */
export function newColumnId(prefix: string, existing: ReadonlySet<string>, nowSec: number): string {
  let sec = nowSec;
  while (existing.has(`col_${prefix}_${sec}`)) sec++;
  return `col_${prefix}_${sec}`;
}

function feedColumn(
  id: string,
  title: string,
  subtitle: string,
  kind: ColumnKind,
  filter: Partial<ReqFilter>,
) {
  const spec: ColumnSpec = {
    id,
    title,
    subtitle,
    kind,
    renderer: "FEED",
    filter: { ...defaultFilter(), ...filter },
    pinned: true,
    order: 0,
  };
  return spec;
}

/**
 * キーワード・タグフィード。単語と #タグを 1 カラムに OR で集める（例: rally #wrc #rally）。
 * タイトルは条件の要約。
 */
export function buildSearchColumn(
  words: string[],
  hashtags: string[],
  existing: ReadonlySet<string>,
  nowSec: number,
): ColumnSpec {
  const summary = [...words, ...hashtags.map((h) => `#${h.replace(/^#/, "")}`)].join(" ");
  return feedColumn(
    newColumnId("search", existing, nowSec),
    summary.trim() === "" ? CANONICAL_TITLE.search : summary,
    CANONICAL_SUBTITLE.search,
    "GLOBAL",
    { words, hashtags: hashtags.map((h) => h.replace(/^#/, "").toLowerCase()) },
  );
}

/** npub / nprofile / 64 桁 hex を hex の公開鍵へ。どれでもなければ null */
function profileHex(text: string): string | null {
  if (/^[0-9a-f]{64}$/.test(text)) return text;
  if (!text.startsWith("npub1") && !text.startsWith("nprofile1")) return null;
  try {
    const decoded = decode(text);
    const hex =
      decoded.type === "npub" ? decoded.data : decoded.type === "nprofile" ? decoded.data.pubkey : null;
    return typeof hex === "string" && /^[0-9a-f]{64}$/.test(hex) ? hex : null;
  } catch {
    return null;
  }
}

/** 長い npub はタイトル用に縮める（ネイティブの npubShort） */
function npubShort(text: string): string {
  return text.length > 14 ? `${text.slice(0, 10)}…` : text;
}

/**
 * テンプレ + 入力 → カラム（ネイティブの ColumnTemplate.build）。ここで入れる日本語タイトルは保存・同期される。
 * PROFILE の入力が npub / nprofile / hex として読めなければ null。
 */
export function buildColumn(
  template: TemplateId,
  input: { text?: string; notifKinds?: number[]; relays?: string[] },
  existing: ReadonlySet<string>,
  nowSec: number,
): ColumnSpec | null {
  const id = newColumnId(template.toLowerCase(), existing, nowSec);
  const text = (input.text ?? "").trim();
  switch (template) {
    case "FOLLOWING":
      return feedColumn(id, CANONICAL_TITLE.following, "following", "FOLLOWING", {});
    case "GLOBAL": {
      const relays = input.relays ?? [];
      const subtitle = relays.length === 0 ? "all relays" : `${relays.length} relays`;
      return feedColumn(id, CANONICAL_TITLE.global, subtitle, "GLOBAL", { relays });
    }
    case "NOTIFICATIONS": {
      const kinds = input.notifKinds ?? [];
      return feedColumn(id, CANONICAL_TITLE.notifications, "mentions/zaps…", "NOTIFICATIONS", {
        kinds: kinds.length > 0 ? kinds : [1, 7, 9735, 6],
      });
    }
    case "DM":
      return feedColumn(id, "DM", "NIP-17", "DM", { kinds: [14] });
    case "PROFILE": {
      const hex = profileHex(text);
      if (hex === null) return null;
      return feedColumn(id, npubShort(text), "profile", "PROFILE", { authors: [hex] });
    }
    case "SEARCH": {
      const tokens = text.split(/\s+/).filter((t) => t.trim() !== "");
      return buildSearchColumn(
        tokens.filter((t) => !t.startsWith("#")),
        tokens.filter((t) => t.startsWith("#")),
        existing,
        nowSec,
      );
    }
    case "HASHTAG": {
      const tag = text.replace(/^#/, "");
      return feedColumn(id, `#${tag}`, "hashtag", "HASHTAG", { hashtags: [tag] });
    }
    case "FAVS":
      return feedColumn(id, CANONICAL_TITLE.favs, CANONICAL_SUBTITLE.myReactions, "FAVS", { kinds: [7] });
    case "CHANNEL_LIST":
      // [#799] パブリックチャット画面の一覧（ネイティブ SampleData.channelListColumn）と同じタイトル・サブタイトル・filter。
      // 一覧は REQ を張らずに取るので、filter は同期の形を揃えるためだけに持つ
      return {
        ...feedColumn(id, CANONICAL_TITLE.publicChat, "NIP-28 · channels", "CHANNEL_LIST", {
          kinds: [40, 41],
        }),
        renderer: "CHANNEL_LIST",
      };
    case "STATUS":
      // 対象（フォロー + 自分）は実行時に決めるので authors は持たない。種類の絞り込みはカラムの表示設定（同期しない）
      return feedColumn(id, CANONICAL_TITLE.status, "NIP-38", "STATUS", { kinds: [30315] });
  }
}

/**
 * NIP-28 のルームのカラム（ネイティブ SampleData.roomColumnFor）。一覧の「ピン留め」・一覧カラムから開くときに使う。
 * subtitle はチャンネルの説明、空なら "NIP-28 · kind:42"（保存・同期される）。
 */
export function roomColumnFor(channel: { id: string; name: string; about: string }): ColumnSpec {
  return {
    id: `room_${channel.id}`,
    title: channel.name,
    subtitle: channel.about.trim() === "" ? "NIP-28 · kind:42" : channel.about,
    kind: "CHANNEL_ROOM",
    renderer: "ROOM",
    filter: { ...defaultFilter(), kinds: [42], channelId: channel.id },
    pinned: false,
    order: 100,
  };
}

/** リストカラムに載せる著者数の上限（ネイティブの LIST_COLUMN_AUTHOR_CAP と同じ 500） */
export const LIST_COLUMN_AUTHOR_CAP = 500;

/**
 * NIP-51 フォローセット（kind:30000）のメンバーのタイムラインを 1 カラムにする（ネイティブの buildListColumn）。
 * 投稿 + リポストをそのメンバーの authors で集める既存のカラム機構にそのまま載せる。
 * 一時カラムとして開く（pinned: false。ヘッダの📌で固定できる）。著者数は LIST_COLUMN_AUTHOR_CAP で頭打ち
 * （重複は除き、先に見えた順を保つ）。
 */
export function buildListColumn(title: string, members: readonly string[], nowSec: number): ColumnSpec {
  return {
    id: `col_list_${nowSec}`,
    title: title.trim() === "" ? CANONICAL_TITLE.list : title,
    subtitle: "list",
    kind: "LIST",
    renderer: "FEED",
    filter: {
      ...defaultFilter(),
      kinds: [1, 6, 16],
      authors: [...new Set(members)].slice(0, LIST_COLUMN_AUTHOR_CAP),
    },
    pinned: false,
    order: 0,
  };
}

/** 「フィルターを編集」に使うテンプレ（設定を持たないカラムは null） */
export function editTemplate(spec: ColumnSpec): TemplateId | null {
  switch (spec.kind) {
    case "HASHTAG":
      return "HASHTAG";
    case "PROFILE":
      return "PROFILE";
    case "NOTIFICATIONS":
      return "NOTIFICATIONS";
    case "GLOBAL":
      return spec.filter.words.length > 0 || spec.filter.search !== null ? "SEARCH" : "GLOBAL";
    default:
      return null;
  }
}

/** 今のテキスト設定値（TEXT のプリフィル用） */
export function editText(spec: ColumnSpec): string {
  const f = spec.filter;
  switch (spec.kind) {
    case "HASHTAG":
      return f.hashtags[0] ?? "";
    case "PROFILE": {
      // hex は npub にして出す（同期で入ってきた生の入力文字列などはそのまま）
      const author = f.authors[0] ?? "";
      return /^[0-9a-f]{64}$/.test(author) ? npubEncode(author) : author;
    }
    case "GLOBAL":
      // キーワード・タグフィードはトークン列へ戻す（旧形式は search をそのまま）
      if (f.words.length > 0 || f.hashtags.length > 0) {
        return [...f.words, ...f.hashtags.map((h) => `#${h}`)].join(" ");
      }
      return f.search ?? "";
    default:
      return "";
  }
}

/** GLOBAL カラムの今の配信先リレー（RELAY_SET のプリフィル用） */
export function editRelays(spec: ColumnSpec): string[] {
  return spec.kind === "GLOBAL" ? spec.filter.relays : [];
}

/** カラムヘッダのサブタイトル（ネイティブの columnSubtitleFor。種別から導出し、辞書を引く） */
export function columnSubtitleFor(spec: ColumnSpec): string {
  switch (spec.kind) {
    case "FOLLOWING":
      return "following";
    case "GLOBAL":
      return spec.filter.words.length > 0 || spec.filter.hashtags.length > 0 ? t("tpl_search") : "global";
    case "HASHTAG":
      return "hashtag";
    case "NOTIFICATIONS":
      return t("notif_subtitle");
    case "DM":
      return "NIP-17";
    case "PROFILE":
      return t("profile_section");
    case "FAVS":
      return t("sub_my_reactions");
    case "LIST":
      return t("tab_lists");
    case "STATUS":
      return "NIP-38";
    default:
      return columnDisplaySubtitle(spec.subtitle);
  }
}

/** 初回起動の既定カラム（ネイティブの SampleData.columns から NIP-28 を除いたもの。id も同じ） */
export const DEFAULT_COLUMNS: readonly ColumnSpec[] = [
  feedColumn("c_following", CANONICAL_TITLE.following, "following", "FOLLOWING", {}),
  { ...feedColumn("c_hashtag", "#nostr", "hashtag", "HASHTAG", { hashtags: ["nostr"] }), order: 1 },
  {
    ...feedColumn("c_notif", CANONICAL_TITLE.notifications, "mentions/zaps…", "NOTIFICATIONS", {
      kinds: [1, 7, 9735],
    }),
    order: 2,
  },
];
