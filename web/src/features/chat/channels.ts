import { create } from "zustand";

/**
 * NIP-28 のチャンネル一覧（ネイティブ EventRepository.refreshChannels / channelsFlow の Web 版）。
 * 取得元は thread.nchan.vip の中継（/api/nchan/channels。同一オリジンだけが使える）。kind:40 / 41 はリレーから足さない。
 */

/** 一覧の取得元（functions/api/nchan/channels.ts） */
export const CHANNELS_ENDPOINT = "/api/nchan/channels";

export type Channel = {
  id: string;
  name: string;
  about: string;
  /** 画像の URL（空なら null） */
  picture: string | null;
  /** content.relays（購読・送信のヒント） */
  relays: string[];
  createdAt: number;
  /** 最終更新（latest_update。無ければ created_at） */
  lastAt: number;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** JSON の文字列（kotlinx の contentOrNull と同じく数値・真偽値も文字にする。null・配列・オブジェクトは null） */
function primitiveText(value: unknown): string | null {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return null;
}

/** 整数の秒（数値か数字の文字列。読めなければ null） */
function toSec(value: unknown): number | null {
  const text = primitiveText(value);
  if (text === null || !/^[+-]?\d+$/.test(text.trim())) return null;
  return Number(text.trim());
}

/** content（JSON 文字列）を読む。壊れていれば null */
function parseMeta(content: unknown): Record<string, unknown> | null {
  const text = primitiveText(content);
  if (text === null) return null;
  try {
    const value: unknown = JSON.parse(text);
    return isRecord(value) ? value : null;
  } catch {
    return null;
  }
}

function blankToNull(value: string | null): string | null {
  return value === null || value.trim() === "" ? null : value;
}

/** 1 行（ネイティブ refreshChannels の mapNotNull の中身）。id が無ければ null */
function parseChannel(row: unknown): Channel | null {
  if (!isRecord(row)) return null;
  const id = primitiveText(row.id);
  if (id === null) return null;
  const meta = parseMeta(row.content);
  // content の name が空・content が壊れていれば外側の name
  const name = blankToNull(primitiveText(meta?.name)) ?? primitiveText(row.name) ?? "";
  const about = primitiveText(meta?.about) ?? "";
  const picture = blankToNull(primitiveText(meta?.picture));
  const createdAt = toSec(row.created_at) ?? 0;
  const lastAt = toSec(row.latest_update) ?? createdAt;
  const relays = Array.isArray(meta?.relays)
    ? meta.relays.flatMap((r) => {
        const url = primitiveText(r);
        return url === null ? [] : [url];
      })
    : [];
  return { id, name, about, picture, relays, createdAt, lastAt };
}

/**
 * /api/nchan/channels の応答（{"data":[…]}）をチャンネルの一覧にする。最終更新の新しい順（ネイティブ channelsByActivity。
 * 同じ時刻は応答の順）。同じ id は後の行で上書きする（ネイティブの upsert と同じ）。data が無ければ null。
 */
export function parseChannels(body: unknown): Channel[] | null {
  if (!isRecord(body) || !Array.isArray(body.data)) return null;
  const byId = new Map<string, Channel>();
  for (const row of body.data) {
    const channel = parseChannel(row);
    if (channel) byId.set(channel.id, channel);
  }
  // Array.prototype.sort は安定なので、同じ時刻は応答の順のまま
  return [...byId.values()].sort((a, b) => b.lastAt - a.lastAt);
}

export type ChannelsState = {
  /** 一覧（null = まだ取れていない） */
  channels: Channel[] | null;
  /** 取得中 */
  loading: boolean;
  /** 最後の取得が失敗した */
  failed: boolean;
};

export const useChannels = create<ChannelsState>()(() => ({ channels: null, loading: false, failed: false }));

let inFlight: Promise<void> | null = null;

/**
 * 一覧を取り直す（画面・カラムを表示したとき。ネイティブ LaunchedEffect(repo) { refreshChannels() }）。
 * 取得中なら同じ取得を待つ。失敗しても前の一覧は残す。
 */
export function refreshChannels(
  fetchImpl: typeof fetch = (input, init) => fetch(input, init),
): Promise<void> {
  if (inFlight) return inFlight;
  useChannels.setState({ loading: true });
  inFlight = (async () => {
    try {
      const res = await fetchImpl(CHANNELS_ENDPOINT, {
        credentials: "same-origin",
        headers: { Accept: "application/json" },
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const channels = parseChannels(await res.json());
      if (channels === null) throw new Error("unexpected body");
      useChannels.setState({ channels, loading: false, failed: false });
    } catch (e) {
      console.warn("[chat] Failed to fetch the channel list", e);
      useChannels.setState({ loading: false, failed: true });
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

/** まだ一覧が無ければ取りに行く（ルームを直接開いたとき。チャンネルのリレーを知るため） */
export function ensureChannels(): void {
  const { channels, loading } = useChannels.getState();
  if (channels === null && !loading) void refreshChannels();
}

/**
 * Web で作成・編集した結果をローカルの一覧へすぐ反映する（ネイティブ createChannel / updateChannel の
 * upsertChannel と同じ。/api/nchan/channels に載るのを待たない）。無ければ先頭に足し、既にあれば
 * その場で置き換える（並び順は変えない）。
 */
export function upsertLocalChannel(channel: Channel): void {
  useChannels.setState((s) => {
    const list = s.channels ?? [];
    const index = list.findIndex((c) => c.id === channel.id);
    if (index === -1) return { channels: [channel, ...list] };
    const next = [...list];
    next[index] = channel;
    return { channels: next };
  });
}

/** 一覧のチャンネル（無ければ undefined） */
export function useChannel(id: string | null): Channel | undefined {
  return useChannels((s) => (id === null ? undefined : s.channels?.find((c) => c.id === id)));
}

/** テスト専用: 一覧を空に戻す */
export function resetChannelsForTest(): void {
  inFlight = null;
  useChannels.setState({ channels: null, loading: false, failed: false }, true);
}
