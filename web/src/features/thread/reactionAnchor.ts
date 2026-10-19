import type { NostrEvent } from "nostr-tools/pure";

/**
 * [#904] リアクション（kind:7）やそのリポスト（kind:16）の id でスレッドを開かれたとき、
 * 最後の e タグを辿って「最初の kind:7 / 16 でない投稿」（P）を見つける純関数
 * （ネイティブ #901 の data/ReactionAnchor.kt の写し）。
 *
 * スレッド画面は kind:7 を起点にしない（本文が「+」だけの投稿が 1 件出るだけで、どの投稿の話か分からない）。
 * 通知・nevent のどこから来ても、ThreadScreen の 1 か所（useReactionAnchor）でここを通して P に付け替え、
 * 辿った経路の最初の kind:7 を統計行でハイライトする。取得は呼び出し側（手元に無い id を needFetch で返す）。
 */

/** 辿る回数の上限（リアクションへのリアクションが何段も続くことは実際にはほぼ無い） */
export const REACTION_ANCHOR_MAX_HOPS = 6;

export function isReactionLike(kind: number): boolean {
  return kind === 7 || kind === 16;
}

/** 対象の e タグ = 値のある e タグのうち最後のもの（NIP-25 / NIP-18。本アプリの kind:7 は対象の e を 1 本だけ持つ） */
export function targetTagOf(event: NostrEvent): string[] | null {
  for (let i = event.tags.length - 1; i >= 0; i--) {
    const tag = event.tags[i];
    if (tag.length >= 2 && tag[0] === "e") return tag;
  }
  return null;
}

/** 対象の id（[targetTagOf] の値）。無ければ null */
export function targetOf(event: NostrEvent): string | null {
  return targetTagOf(event)?.[1] ?? null;
}

export type ReactionAnchorResult =
  /** P が決まった。path は辿った kind:7 / 16（開いた順）。開いたのが投稿なら空 */
  | { type: "found"; postId: string; path: NostrEvent[] }
  /** id が手元に無い。取ってきてから resolve をやり直す */
  | { type: "needFetch"; id: string; path: NostrEvent[] }
  /** 辿れない（e タグが無い・深すぎる・循環） */
  | { type: "unresolved"; path: NostrEvent[] };

export function resolveReactionAnchor(
  startId: string,
  lookup: (id: string) => NostrEvent | undefined | null,
  maxHops: number = REACTION_ANCHOR_MAX_HOPS,
): ReactionAnchorResult {
  const path: NostrEvent[] = [];
  const seen = new Set<string>();
  let cur = startId;
  while (true) {
    if (seen.has(cur)) return { type: "unresolved", path };
    seen.add(cur);
    const ev = lookup(cur);
    if (!ev) return { type: "needFetch", id: cur, path };
    if (!isReactionLike(ev.kind)) return { type: "found", postId: cur, path };
    if (path.length >= maxHops) return { type: "unresolved", path };
    path.push(ev);
    const next = targetOf(ev);
    if (next === null) return { type: "unresolved", path };
    cur = next;
  }
}

/** 統計行でハイライトする kind:7（経路の最初の kind:7。開いたのが kind:16 ならその中身）。無ければ null */
export function highlightOf(path: readonly NostrEvent[]): NostrEvent | null {
  return path.find((e) => e.kind === 7) ?? null;
}
