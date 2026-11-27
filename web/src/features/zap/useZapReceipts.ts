import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useState } from "react";
import { distinctUntilChanged, map } from "rxjs";
import { subscribeTo, useReadRelays } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { NO_ZAPS, type NoteZaps, zapTotals } from "./zapTotals";

/** 購読する投稿 id の上限（先頭から。ネイティブ subscribeZaps と同じ） */
export const ZAP_NOTE_LIMIT = 300;
/** receipt の REQ の limit（ネイティブと同じ） */
export const ZAP_RECEIPT_LIMIT = 500;

/**
 * 一覧のイベント → ⚡ を出す投稿の id（並びのまま・重複なし）。
 * リポスト（kind 6 / 16）は元投稿（最初の e タグ）、リアクション（7）と Zap 受領（9735）はアクション行が無いので除く。
 */
export function zapTargetIds(events: readonly NostrEvent[]): string[] {
  const ids = new Set<string>();
  for (const event of events) {
    if (event.kind === 7 || event.kind === 9735) continue;
    if (event.kind === 6 || event.kind === 16) {
      const target = event.tags.find((t) => t[0] === "e")?.[1];
      if (target) ids.add(target);
    } else {
      ids.add(event.id);
    }
  }
  return [...ids];
}

/**
 * 表示中の投稿への Zap 受領（kind:9735）を read リレーへ購読する（ネイティブ DeckScreen.kt SubscribeZaps）。
 * 先頭 300 件の id の集合が変わったら張り直し、アンマウントで CLOSE。受けた receipt は EventStore へ入る。
 */
export function useZapReceipts(noteIds: readonly string[]): void {
  const relays = useReadRelays();
  // 集合で比べる（並べ替えただけでは張り直さない）
  const key = [...new Set(noteIds)].slice(0, ZAP_NOTE_LIMIT).sort().join(",");
  useEffect(() => {
    if (key === "") return;
    const sub = subscribeTo(relays, [
      { kinds: [9735], "#e": key.split(","), limit: ZAP_RECEIPT_LIMIT },
    ]).subscribe();
    return () => sub.unsubscribe();
  }, [relays, key]);
}

/** ストアにあるこの投稿への Zap（合計と新しい順の一覧） */
export function useNoteZaps(noteId: string): NoteZaps {
  return (
    use$(
      () =>
        eventStore
          .timeline({ kinds: [9735], "#e": [noteId] })
          .pipe(map((receipts) => zapTotals(receipts).get(noteId) ?? NO_ZAPS)),
      [noteId],
    ) ?? NO_ZAPS
  );
}

/** ストアにあるこの投稿への Zap の合計 sats（変わったときだけ描き直す） */
export function useZapSats(noteId: string): number {
  return (
    use$(
      () =>
        eventStore.timeline({ kinds: [9735], "#e": [noteId] }).pipe(
          map((receipts) => zapTotals(receipts).get(noteId)?.totalSats ?? 0),
          distinctUntilChanged(),
        ),
      [noteId],
    ) ?? 0
  );
}

/**
 * [#1012] この請求書（bolt11）の receipt がストアに届いたか。投稿への Zap だけ見る（届くのは表示中の投稿への
 * 既存の購読 useZapReceipts の分。プロフィール Zap は購読が無いので見ない）。noteId か invoice が無ければ false
 */
export function useInvoicePaid(noteId: string | undefined, invoice: string | null): boolean {
  // 届いた請求書（invoice が変わったら外れる）
  const [paidInvoice, setPaidInvoice] = useState<string | null>(null);
  useEffect(() => {
    if (noteId === undefined || invoice === null) return;
    const target = invoice.toLowerCase();
    const sub = eventStore.timeline({ kinds: [9735], "#e": [noteId] }).subscribe((receipts) => {
      const hit = receipts.some((r) =>
        r.tags.some((t) => t[0] === "bolt11" && typeof t[1] === "string" && t[1].toLowerCase() === target),
      );
      if (hit) setPaidInvoice(invoice);
    });
    return () => sub.unsubscribe();
  }, [noteId, invoice]);
  return invoice !== null && paidInvoice === invoice;
}
