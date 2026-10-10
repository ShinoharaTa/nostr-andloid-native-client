import { create } from "zustand";
import type { StatusType } from "../features/status/statusModel";
import {
  buildColumn,
  type ColumnSpec,
  type ColumnWidth,
  DEFAULT_COLUMNS,
  decodeDeckColumns,
  encodeDeckColumns,
  sortByOrder,
} from "../lib/columns";
import { unixNow } from "../lib/time";

/** 固定カラムの構成（NIP-78 の content と同じ DeckColumnDto 配列の JSON。並び順） */
export const COLUMNS_KEY = "nostrism.deck.columns";
/** カラム幅（{"<id>":"S"|"L"}。M は書かない） */
export const WIDTHS_KEY = "nostrism.deck.widths";
/** ミュートを表示するカラム（id の配列。ネイティブ col_reveal_muted:<id>） */
export const REVEAL_MUTED_KEY = "nostrism.deck.revealMuted";
/** フォロー中カラムで隠す混在の種別（{"<id>":["REACTIONS",…]}。空のカラムは書かない。ネイティブ col_feedcat_hidden:<id>） */
export const FEED_CAT_HIDDEN_KEY = "nostrism.deck.feedCatHidden";
/** ステータスカラムで出す種類（{"<id>":"music"|"general"}。「すべて」は書かない。Web だけ・同期しない #767） */
export const STATUS_TYPE_KEY = "nostrism.deck.statusType";

/**
 * フォロー中カラムに混ぜる行の種別（ネイティブ FeedNoticeCategory。⋯ メニューの並び順）。
 * [#796] CHAT = フォロー中の人（と自分）のパブリックチャットの発言（kind:42）。既定は表示（隠す集合に入れたときだけ隠す）
 */
export type FeedCategory = "REACTIONS" | "REPLIES" | "REPOSTS" | "MY_REACTIONS" | "DMS" | "CHAT";
export const FEED_CATEGORIES: readonly FeedCategory[] = [
  "REACTIONS",
  "REPLIES",
  "REPOSTS",
  "MY_REACTIONS",
  "DMS",
  "CHAT",
];

export type DeckState = {
  /** 固定 + 一時カラム。並び順 = 表示順（order は常に 0..n-1）。デッキの SSOT */
  columns: ColumnSpec[];
  widths: Record<string, ColumnWidth>;
  /** ミュートを表示する（フィルタしない）カラムの id */
  revealMuted: string[];
  /** カラムごとに隠す混在の種別（フォロー中カラム。隠すものが無いカラムはキーを持たない） */
  feedCatHidden: Record<string, FeedCategory[]>;
  /** ステータスカラムで出す種類（「すべて」のカラムはキーを持たない） */
  statusType: Record<string, StatusType>;
  /** ジャンプ要求のカラム id（デッキが消費して null に戻す） */
  jumpTarget: string | null;
  /** コンパクト表示で見えているカラム id */
  visibleColumnId: string | null;
  /** フィルター編集ダイアログの対象 */
  editingColumnId: string | null;
  showAddColumn: boolean;

  /** 末尾に追加してジャンプ（固定なら保存） */
  addColumn(spec: ColumnSpec): void;
  /** 一時カラムを開く（同じ id があればそこへジャンプ）。originId を渡すと back() でそこへ戻る */
  openTransient(spec: ColumnSpec, originId?: string): string;
  /** ハッシュタグカラムを一時で開く（同じタグのカラムがあればジャンプ）。タグが空なら null */
  openHashtag(tag: string): string | null;
  pin(id: string): void;
  unpin(id: string): void;
  /** 一時カラムだけ閉じる */
  close(id: string): void;
  /** 固定でも取り除く（⋯ メニューの削除）。幅の設定も消す */
  removeColumn(id: string): void;
  /** ⋯ メニューの ◀ ▶。端を越える移動は無視 */
  moveColumn(id: string, delta: -1 | 1): void;
  /** フィルター編集。id / pinned / order は維持する */
  updateColumn(id: string, newSpec: ColumnSpec): void;
  setWidth(id: string, w: ColumnWidth): void;
  /** カラムでミュートを表示するか（⋯ メニューの「ミュートを表示 / 隠す」） */
  setRevealMuted(id: string, reveal: boolean): void;
  /** フォロー中カラムに混ぜる種別を隠すか（⋯ メニューの「タイムラインに混ぜる表示」） */
  setFeedCatHidden(id: string, category: FeedCategory, hidden: boolean): void;
  /** ステータスカラムで出す種類（⋯ メニューの「表示」。null = すべて） */
  setStatusType(id: string, type: StatusType | null): void;
  jumpTo(id: string): void;
  consumeJump(): void;
  setVisibleColumn(id: string | null): void;
  setEditing(id: string | null): void;
  setShowAddColumn(show: boolean): void;
  /** 最後に開いた一時カラムを閉じて元のカラムへ戻る。閉じるものが無ければ false */
  back(): boolean;
  /** 固定カラムを丸ごと置き換える（開いている一時カラムは残す）。同期（#468）が使う */
  applyPinnedColumns(specs: ColumnSpec[]): void;
};

function readItem(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeItem(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 保存できない環境（容量超過・プライベートモード等）はメモリ上の状態だけで続ける
  }
}

/** order を並び順（0..n-1）に揃える */
function reindex(columns: ColumnSpec[]): ColumnSpec[] {
  return columns.map((c, i) => (c.order === i ? c : { ...c, order: i }));
}

/** 固定カラムだけを保存する */
export function saveColumns(columns: readonly ColumnSpec[]) {
  writeItem(COLUMNS_KEY, encodeDeckColumns(columns.filter((c) => c.pinned)));
}

/**
 * 保存済みの固定カラム。無い・壊れている・空なら既定カラムを返し、その場で保存する（ネイティブの初回 seed と同じ）。
 */
export function loadColumns(): ColumnSpec[] {
  const saved = readItem(COLUMNS_KEY);
  const decoded = saved === null ? null : decodeDeckColumns(saved);
  if (decoded && decoded.length > 0) return reindex(sortByOrder(decoded));
  saveColumns(DEFAULT_COLUMNS);
  return [...DEFAULT_COLUMNS];
}

export function saveWidths(widths: Record<string, ColumnWidth>) {
  writeItem(WIDTHS_KEY, JSON.stringify(widths));
}

/** 保存済みのカラム幅（S / L だけを拾う。壊れていれば空） */
export function loadWidths(): Record<string, ColumnWidth> {
  const widths: Record<string, ColumnWidth> = {};
  try {
    const value: unknown = JSON.parse(readItem(WIDTHS_KEY) ?? "{}");
    if (typeof value === "object" && value !== null && !Array.isArray(value)) {
      for (const [id, w] of Object.entries(value)) {
        if (w === "S" || w === "L") widths[id] = w;
      }
    }
  } catch {
    // 壊れた保存値は既定（すべて M）へ
  }
  return widths;
}

export function saveRevealMuted(ids: readonly string[]) {
  writeItem(REVEAL_MUTED_KEY, JSON.stringify(ids));
}

/** 保存済みの「ミュートを表示する」カラム（文字列だけを拾う。壊れていれば空） */
export function loadRevealMuted(): string[] {
  try {
    const value: unknown = JSON.parse(readItem(REVEAL_MUTED_KEY) ?? "[]");
    if (Array.isArray(value)) return [...new Set(value.filter((v): v is string => typeof v === "string"))];
  } catch {
    // 壊れた保存値は既定（すべて隠す）へ
  }
  return [];
}

export function saveFeedCatHidden(hidden: Record<string, FeedCategory[]>) {
  writeItem(FEED_CAT_HIDDEN_KEY, JSON.stringify(hidden));
}

/** 保存済みの隠す種別（知っている種別だけを拾い、空のカラムは落とす。壊れていれば空） */
export function loadFeedCatHidden(): Record<string, FeedCategory[]> {
  const hidden: Record<string, FeedCategory[]> = {};
  try {
    const value: unknown = JSON.parse(readItem(FEED_CAT_HIDDEN_KEY) ?? "{}");
    if (typeof value === "object" && value !== null && !Array.isArray(value)) {
      for (const [id, list] of Object.entries(value)) {
        if (!Array.isArray(list)) continue;
        const categories = FEED_CATEGORIES.filter((c) => list.includes(c));
        if (categories.length > 0) hidden[id] = categories;
      }
    }
  } catch {
    // 壊れた保存値は既定（すべて表示）へ
  }
  return hidden;
}

export function saveStatusType(types: Record<string, StatusType>) {
  writeItem(STATUS_TYPE_KEY, JSON.stringify(types));
}

/** 保存済みのステータスカラムの種類（"music" / "general" だけを拾う。壊れていれば空 = すべて） */
export function loadStatusType(): Record<string, StatusType> {
  const types: Record<string, StatusType> = {};
  try {
    const value: unknown = JSON.parse(readItem(STATUS_TYPE_KEY) ?? "{}");
    if (typeof value === "object" && value !== null && !Array.isArray(value)) {
      for (const [id, type] of Object.entries(value)) {
        if (type === "music" || type === "general") types[id] = type;
      }
    }
  } catch {
    // 壊れた保存値は既定（すべて）へ
  }
  return types;
}

// (一時カラム id, 開いた元のカラム id) の戻りスタック。back() の戻り先に使う
const originStack: [string, string | null][] = [];

function dropOrigin(id: string) {
  for (let i = originStack.length - 1; i >= 0; i--) {
    if (originStack[i][0] === id) originStack.splice(i, 1);
  }
}

/**
 * デッキの状態（ネイティブの DeckState の写し）。保存は各 action の中で同期的に行う。
 * jumpTo は宛先（URL）を変えない。デッキ以外から呼ぶ側が / へ navigate する。
 */
export const useDeck = create<DeckState>()((set, get) => {
  /** カラム列を差し替える。persist なら固定カラムを保存する */
  const commit = (columns: ColumnSpec[], persist: boolean) => {
    const next = reindex(columns);
    set({ columns: next });
    if (persist) saveColumns(next);
  };
  const replace = (id: string, transform: (c: ColumnSpec) => ColumnSpec) =>
    get().columns.map((c) => (c.id === id ? transform(c) : c));

  return {
    columns: loadColumns(),
    widths: loadWidths(),
    revealMuted: loadRevealMuted(),
    feedCatHidden: loadFeedCatHidden(),
    statusType: loadStatusType(),
    jumpTarget: null,
    visibleColumnId: null,
    editingColumnId: null,
    showAddColumn: false,

    addColumn(spec) {
      commit([...get().columns, spec], spec.pinned);
      get().jumpTo(spec.id);
    },

    openTransient(spec, originId) {
      const { columns } = get();
      if (!columns.some((c) => c.id === spec.id)) commit([...columns, { ...spec, pinned: false }], false);
      dropOrigin(spec.id);
      originStack.push([spec.id, originId ?? null]);
      get().jumpTo(spec.id);
      return spec.id;
    },

    openHashtag(tag) {
      const clean = tag.replace(/^#/, "").toLowerCase();
      if (clean.trim() === "") return null;
      const { columns } = get();
      const existing = columns.find((c) => c.kind === "HASHTAG" && c.filter.hashtags[0] === clean);
      if (existing) {
        get().jumpTo(existing.id);
        return existing.id;
      }
      const spec = buildColumn("HASHTAG", { text: clean }, new Set(columns.map((c) => c.id)), unixNow());
      return spec ? get().openTransient(spec) : null;
    },

    pin(id) {
      commit(
        replace(id, (c) => ({ ...c, pinned: true })),
        true,
      );
    },

    unpin(id) {
      commit(
        replace(id, (c) => ({ ...c, pinned: false })),
        true,
      );
    },

    close(id) {
      commit(
        get().columns.filter((c) => c.id !== id || c.pinned),
        false,
      );
    },

    removeColumn(id) {
      const { columns, widths, revealMuted, feedCatHidden, statusType } = get();
      const wasPinned = columns.some((c) => c.id === id && c.pinned);
      commit(
        columns.filter((c) => c.id !== id),
        wasPinned,
      );
      // 幅の設定も一緒に消す
      if (Object.hasOwn(widths, id)) {
        const { [id]: _removed, ...rest } = widths;
        set({ widths: rest });
        saveWidths(rest);
      }
      if (revealMuted.includes(id)) get().setRevealMuted(id, false);
      if (Object.hasOwn(feedCatHidden, id)) {
        const { [id]: _removed, ...rest } = feedCatHidden;
        set({ feedCatHidden: rest });
        saveFeedCatHidden(rest);
      }
      if (Object.hasOwn(statusType, id)) get().setStatusType(id, null);
    },

    moveColumn(id, delta) {
      const columns = [...get().columns];
      const from = columns.findIndex((c) => c.id === id);
      const to = from + delta;
      if (from < 0 || to < 0 || to >= columns.length) return;
      const [moved] = columns.splice(from, 1);
      columns.splice(to, 0, moved);
      commit(columns, true);
    },

    updateColumn(id, newSpec) {
      const old = get().columns.find((c) => c.id === id);
      if (!old) return;
      commit(
        replace(id, (c) => ({ ...newSpec, id: c.id, pinned: c.pinned, order: c.order })),
        old.pinned,
      );
    },

    setWidth(id, w) {
      const widths = { ...get().widths };
      if (w === "M") delete widths[id];
      else widths[id] = w;
      set({ widths });
      saveWidths(widths);
    },

    setRevealMuted(id, reveal) {
      const rest = get().revealMuted.filter((c) => c !== id);
      const revealMuted = reveal ? [...rest, id] : rest;
      set({ revealMuted });
      saveRevealMuted(revealMuted);
    },

    setFeedCatHidden(id, category, hidden) {
      const current = get().feedCatHidden[id] ?? [];
      const next = FEED_CATEGORIES.filter((c) => (c === category ? hidden : current.includes(c)));
      const { [id]: _old, ...rest } = get().feedCatHidden;
      // 空になったカラムはキーごと消す
      const feedCatHidden = next.length > 0 ? { ...rest, [id]: next } : rest;
      set({ feedCatHidden });
      saveFeedCatHidden(feedCatHidden);
    },

    setStatusType(id, type) {
      const { [id]: _old, ...rest } = get().statusType;
      // 「すべて」はキーごと消す
      const statusType = type === null ? rest : { ...rest, [id]: type };
      set({ statusType });
      saveStatusType(statusType);
    },

    jumpTo(id) {
      set({ jumpTarget: id });
    },

    consumeJump() {
      set({ jumpTarget: null });
    },

    setVisibleColumn(id) {
      set({ visibleColumnId: id });
    },

    setEditing(id) {
      set({ editingColumnId: id });
    },

    setShowAddColumn(show) {
      set({ showAddColumn: show });
    },

    back() {
      // 開いた順に積んだスタックから最後の一時カラムを閉じ、元のカラムへ戻る
      const top = originStack.pop();
      if (top) {
        const [transientId, originId] = top;
        const columns = get().columns.filter((c) => c.id !== transientId || c.pinned);
        commit(columns, false);
        const target =
          (originId !== null && columns.some((c) => c.id === originId) ? originId : null) ??
          columns.findLast((c) => c.pinned)?.id;
        if (target) get().jumpTo(target);
        return true;
      }
      // スタックに無い一時カラムを末尾から閉じる
      const columns = get().columns;
      const index = columns.findLastIndex((c) => !c.pinned);
      if (index < 0) return false;
      const rest = columns.filter((_, i) => i !== index);
      commit(rest, false);
      const target = rest[index - 1] ?? rest.at(-1);
      if (target) get().jumpTo(target.id);
      return true;
    },

    applyPinnedColumns(specs) {
      const transients = get().columns.filter((c) => !c.pinned && !specs.some((s) => s.id === c.id));
      commit([...sortByOrder(specs), ...transients], true);
    },
  };
});

/** 固定カラム（並び順）。配列を作り直すので、購読するときは useShallow で包む */
export function pinnedColumns(s: DeckState): ColumnSpec[] {
  return s.columns.filter((c) => c.pinned);
}

/** 通知カラム（あれば）の id */
export function notificationsColumnId(s: DeckState): string | null {
  return s.columns.find((c) => c.kind === "NOTIFICATIONS")?.id ?? null;
}

/** 戻る操作で閉じられる一時カラムがあるか */
export function hasTransient(s: DeckState): boolean {
  return s.columns.some((c) => !c.pinned);
}

export function widthOf(s: DeckState, id: string): ColumnWidth {
  return s.widths[id] ?? "M";
}

/** このカラムでミュートを表示するか */
export function isMutedRevealed(s: DeckState, id: string): boolean {
  return s.revealMuted.includes(id);
}

const NO_CATEGORIES: readonly FeedCategory[] = [];

/** このカラムで隠す混在の種別（無ければ同じ空配列） */
export function feedCatHiddenOf(s: DeckState, id: string): readonly FeedCategory[] {
  return s.feedCatHidden[id] ?? NO_CATEGORIES;
}

/** このステータスカラムで出す種類（null = すべて） */
export function statusTypeOf(s: DeckState, id: string): StatusType | null {
  return s.statusType[id] ?? null;
}
