import { useMemo } from "react";
import { create } from "zustand";
import type { DmMessageRow } from "../../db/schema";
import { type MuteMatcher, matchesWord, useMuteMatcher } from "../mute/muteList";
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
  /**
   * [#970] 自分のフォロー（kind:3 の p）。会話を「フォロー中」と「その他」に分ける。null = まだ分からない
   * （分かるまでは空として扱う。ネイティブ buildDmConversations の follows と同じ）。書き込みは dmService
   */
  follows: ReadonlySet<string> | null;
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
  follows: null,
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
 * lastIncomingAt = 相手の発言の最新の時刻（無ければ 0）。
 * accepted = 「フォロー中」に出す会話（#970。相手をフォローしている・自分が 1 通でも送った・自分宛てのメモ）。
 * それ以外（フォローしていない人から届いただけ）は「その他」で、通知の行と未読の合計に数えない
 */
export type DmConversation = {
  peer: string;
  last: DmMessageRow;
  unread: number;
  lastIncomingAt: number;
  accepted: boolean;
};

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
 * 未読 = 相手の発言で既読（記録が無ければ seen.first）より新しいもの。未読の判定はここだけに置く。
 * [#970] accepted = 相手が follows にいる・自分が送った発言がある・相手が自分（ネイティブ buildDmConversations と同じ）。
 * follows が null（まだ分からない）なら空として扱う
 */
export function conversationsOf(
  messages: Iterable<DmMessageRow>,
  me: string | null,
  seen: DmSeenTimes,
  follows: ReadonlySet<string> | null = null,
): DmConversation[] {
  const byPeer = new Map<string, DmConversation>();
  for (const message of messages) {
    let conversation = byPeer.get(message.peer);
    if (!conversation) {
      const accepted = message.peer === me || (follows?.has(message.peer) ?? false);
      conversation = { peer: message.peer, last: message, unread: 0, lastIncomingAt: 0, accepted };
      byPeer.set(message.peer, conversation);
    } else if (byTimeAsc(conversation.last, message) < 0) {
      conversation.last = message;
    }
    if (message.sender === me) {
      conversation.accepted = true;
      continue;
    }
    if (message.createdAt > (seen.peers[message.peer] ?? seen.first)) conversation.unread++;
    conversation.lastIncomingAt = Math.max(conversation.lastIncomingAt, message.createdAt);
  }
  return [...byPeer.values()].sort((a, b) => {
    if (a.last.createdAt !== b.last.createdAt) return b.last.createdAt - a.last.createdAt;
    return a.last.id < b.last.id ? -1 : a.last.id > b.last.id ? 1 : 0;
  });
}

/**
 * [#971] 相手から届いた DM の本文がミュートワード（部分一致・/正規表現/）に当たるか。自分が送った DM は対象外
 * （ネイティブ MuteMatcher.mutedDmText）
 */
export function isDmTextMuted(matcher: MuteMatcher, message: DmMessageRow, me: string | null): boolean {
  return message.sender !== me && matchesWord(matcher, message.content);
}

/**
 * [#971] ミュートワードに当たる相手の DM を除く。会話の一覧・会話の中・通知の行で同じものを使い、当たった DM は
 * 無かったものとして扱う（未読にも数えない。全部当たった会話は一覧から消える。ネイティブ buildDmConversations と同じ）
 */
export function withoutMutedDms(
  messages: Iterable<DmMessageRow>,
  me: string | null,
  matcher: MuteMatcher,
): DmMessageRow[] {
  const all = [...messages];
  if (matcher.wordSubs.length === 0 && matcher.wordRegex.length === 0) return all;
  return all.filter((m) => !isDmTextMuted(matcher, m, me));
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
  const follows = useDm((s) => s.follows);
  const first = useDmSeen((s) => s.first);
  const peers = useDmSeen((s) => s.peers);
  const matcher = useMuteMatcher();
  return useMemo(
    () =>
      conversationsOf(
        withoutMutedDms(Object.values(messages), me, matcher),
        me,
        { first, peers },
        follows,
      ).filter((c) => !matcher.users.has(c.peer)),
    [messages, me, first, peers, follows, matcher],
  );
}

/** 未読の合計。[#970]「その他」（accepted でない会話）は数えない（ネイティブ dmUnreadFlow と同じ） */
export function unreadTotalOf(conversations: readonly DmConversation[]): number {
  return conversations.reduce((sum, c) => (c.accepted ? sum + c.unread : sum), 0);
}

/** 全会話の未読の合計（ナビのバッジ。ネイティブ dmUnreadFlow） */
export function useDmUnreadTotal(): number {
  const conversations = useConversations();
  return useMemo(() => unreadTotalOf(conversations), [conversations]);
}

/** 相手との DM（messages か相手かミュートが変わったときだけ計算し直す。[#971] ミュートワードに当たる相手の DM は出さない） */
export function useMessagesWith(peer: string): DmMessageRow[] {
  const messages = useDm((s) => s.messages);
  const me = useDm((s) => s.owner);
  const matcher = useMuteMatcher();
  return useMemo(
    () => messagesWith(withoutMutedDms(Object.values(messages), me, matcher), peer),
    [messages, peer, me, matcher],
  );
}
