import { useMemo } from "react";
import { create } from "zustand";
import type { DmMessageRow } from "../../db/schema";
import { useMuteMatcher } from "../mute/muteList";
import { useDmSeen } from "./dmSeen";

type DmState = {
  /** ログイン中の自分。null = 未ログイン */
  owner: string | null;
  /** 復号した DM（id → 行） */
  messages: Record<string, DmMessageRow>;
  /** 最初の EOSE か LOADING_TIMEOUT_MS の早い方 */
  loaded: boolean;
  /** 復号待ちの件数（処理中の 1 件を含む） */
  pending: number;
  /** 復号を始めたか（NIP-07 / NIP-46 はメッセージ画面か DM カラムを開くまで始めない） */
  decrypting: boolean;
  /** 署名者の失敗が続いて復号を止めた（「再開」で続ける） */
  paused: boolean;
  /** no-nip44 = 署名者が NIP-44 を使えず NIP-17 の DM を読めない */
  nip17: "ok" | "no-nip44";
  /** no-nip04 = 署名者が NIP-04 を使えず NIP-04 の DM を読めない */
  nip04: "ok" | "no-nip04";
  /** 同じ id は上書き */
  upsertMessages(rows: DmMessageRow[]): void;
  removeMessage(id: string): void;
  /** 空に戻す（ログイン・ログアウト・アカウントの切り替え） */
  reset(owner: string | null): void;
};

const initial = {
  owner: null,
  messages: {},
  loaded: false,
  pending: 0,
  decrypting: false,
  paused: false,
  nip17: "ok",
  nip04: "ok",
} as const satisfies Partial<DmState>;

/** DM（メモリ上）。書き込みは dmService、DB（dmMessages）はその写し */
export const useDm = create<DmState>()((set) => ({
  ...initial,
  upsertMessages(rows) {
    if (rows.length === 0) return;
    set((state) => {
      const messages = { ...state.messages };
      for (const row of rows) messages[row.id] = row;
      return { messages };
    });
  },
  removeMessage(id) {
    set((state) => {
      if (!(id in state.messages)) return state;
      const { [id]: _removed, ...messages } = state.messages;
      return { messages };
    });
  },
  reset(owner) {
    set({ ...initial, owner });
  },
}));

/**
 * 会話 1 つ（相手と最新の 1 件）。unread = 既読より新しい相手の発言の数、
 * lastIncomingAt = 相手の発言の最新の時刻（無ければ 0）
 */
export type DmConversation = { peer: string; last: DmMessageRow; unread: number; lastIncomingAt: number };

/** 既読（dmSeen）。記録の無い相手は first を基準にする */
export type DmSeenTimes = { first: number; peers: Record<string, number> };

/** 古い順（同時刻は id の昇順） */
function byTimeAsc(a: DmMessageRow, b: DmMessageRow): number {
  if (a.createdAt !== b.createdAt) return a.createdAt - b.createdAt;
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

/**
 * 相手ごとに最新の 1 件と未読（ネイティブ dmConversationsFlow）。新しい順、同時刻は id の昇順。
 * 相手の中で同時刻のものは、会話で一番下に並ぶ方（id の大きい方）を最新とする。
 * 未読 = 相手の発言で既読（記録が無ければ seen.first）より新しいもの。未読の判定はここだけに置く
 */
export function conversationsOf(
  messages: Iterable<DmMessageRow>,
  me: string | null,
  seen: DmSeenTimes,
): DmConversation[] {
  const byPeer = new Map<string, DmConversation>();
  for (const message of messages) {
    let conversation = byPeer.get(message.peer);
    if (!conversation) {
      conversation = { peer: message.peer, last: message, unread: 0, lastIncomingAt: 0 };
      byPeer.set(message.peer, conversation);
    } else if (byTimeAsc(conversation.last, message) < 0) {
      conversation.last = message;
    }
    if (message.sender === me) continue;
    if (message.createdAt > (seen.peers[message.peer] ?? seen.first)) conversation.unread++;
    conversation.lastIncomingAt = Math.max(conversation.lastIncomingAt, message.createdAt);
  }
  return [...byPeer.values()].sort((a, b) => {
    if (a.last.createdAt !== b.last.createdAt) return b.last.createdAt - a.last.createdAt;
    return a.last.id < b.last.id ? -1 : a.last.id > b.last.id ? 1 : 0;
  });
}

/** 相手との DM（ネイティブ dmMessagesFlow）。古い順、同時刻は id の昇順 */
export function messagesWith(messages: Iterable<DmMessageRow>, peer: string): DmMessageRow[] {
  const result: DmMessageRow[] = [];
  for (const message of messages) if (message.peer === peer) result.push(message);
  return result.sort(byTimeAsc);
}

/**
 * 会話の一覧（messages か既読かミュートが変わったときだけ計算し直す）。
 * [#957] ブロック（ミュート）中の相手（保存待ちを含む）の会話は出さない（未読数にも数えない。ネイティブ #951）
 */
export function useConversations(): DmConversation[] {
  const messages = useDm((s) => s.messages);
  const me = useDm((s) => s.owner);
  const first = useDmSeen((s) => s.first);
  const peers = useDmSeen((s) => s.peers);
  const blocked = useMuteMatcher().users;
  return useMemo(
    () => conversationsOf(Object.values(messages), me, { first, peers }).filter((c) => !blocked.has(c.peer)),
    [messages, me, first, peers, blocked],
  );
}

/** 全会話の未読の合計（ナビのバッジ。ネイティブ dmUnreadFlow） */
export function useDmUnreadTotal(): number {
  const conversations = useConversations();
  return useMemo(() => conversations.reduce((sum, c) => sum + c.unread, 0), [conversations]);
}

/** 相手との DM（messages か相手が変わったときだけ計算し直す） */
export function useMessagesWith(peer: string): DmMessageRow[] {
  const messages = useDm((s) => s.messages);
  return useMemo(() => messagesWith(Object.values(messages), peer), [messages, peer]);
}
