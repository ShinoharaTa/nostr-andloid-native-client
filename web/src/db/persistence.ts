import type { EventStore } from "applesauce-core/event-store";
import type { NostrEvent } from "nostr-tools/pure";
import { bufferTime, filter } from "rxjs";
import { addVerifiedTo, deletionRecorded$, loadDeletionMemory } from "../nostr/store";
import { EVENTS_TRIM_TO, fromRow, hydratedSymbol, PERSIST_KINDS, toRow, trimEvents } from "./events";
import type { NostrismDb } from "./schema";

/** 書き込みをまとめる時間と件数（ネイティブの INGEST_BATCH = 400） */
const WRITE_WINDOW_MS = 500;
const WRITE_BATCH = 400;
/** 容量超過のとき、今の件数のこの割合（と EVENTS_TRIM_TO の小さい方）まで削る */
const QUOTA_TRIM_RATIO = 0.75;
/** hydrate でメインスレッドを譲る間隔（件数） */
const HYDRATE_CHUNK = 500;

/**
 * EventStore の追加・削除を DB へ書く。保存する kind だけを 500ms か 400 件ごとにまとめて書き、
 * 置換で外れた旧版・kind:5 で消えたものは DB からも消す。DB から戻したもの（hydrate 分）は書き戻さない。
 * @param me 自分の pubkey。容量超過で削るときに自分の行を残す
 * @returns 両方の購読を解除する関数
 */
export function attachPersistence(eventStore: EventStore, db: NostrismDb, me: string | null): () => void {
  const inserts = eventStore.insert$
    .pipe(
      filter((e) => PERSIST_KINDS.has(e.kind) && !(e as unknown as Record<symbol, unknown>)[hydratedSymbol]),
      bufferTime(WRITE_WINDOW_MS, undefined, WRITE_BATCH),
      filter((batch) => batch.length > 0),
    )
    .subscribe((batch) => {
      void putEvents(db, batch, me);
    });

  const removes = eventStore.remove$
    .pipe(
      bufferTime(WRITE_WINDOW_MS),
      filter((batch) => batch.length > 0),
    )
    .subscribe((batch) => {
      db.events.bulkDelete(batch.map((e) => e.id)).catch((e: unknown) => {
        console.warn("[db] Failed to delete the event", e);
      });
    });

  return () => {
    inserts.unsubscribe();
    removes.unsubscribe();
  };
}

/**
 * まとめて書く。容量超過なら件数が cap 以下でも古いものを削り、そのバッチを 1 度だけ書き直す
 * （それも失敗したら捨てる）。他の失敗は警告だけ出して続ける。
 */
async function putEvents(db: NostrismDb, batch: NostrEvent[], me: string | null) {
  const rows = batch.map(toRow);
  try {
    await db.events.bulkPut(rows);
    return;
  } catch (e) {
    if (!isQuotaExceeded(e)) {
      console.warn("[db] Failed to save the event", e);
      return;
    }
  }
  try {
    const trimTo = Math.min(Math.floor((await db.events.count()) * QUOTA_TRIM_RATIO), EVENTS_TRIM_TO);
    // cap = trimTo にして、上限に届いていなくても trimTo まで削る
    await trimEvents(db, { me, cap: trimTo, trimTo });
    await db.events.bulkPut(rows);
  } catch (e) {
    console.warn("[db] Storage quota exceeded; dropping the data", e);
  }
}

/** 容量超過か。IndexedDB はトランザクションの中止として返すので、Dexie では AbortError の inner に入る */
function isQuotaExceeded(e: unknown): boolean {
  const error = e as { name?: unknown; inner?: { name?: unknown } | null } | null;
  return error?.name === "QuotaExceededError" || error?.inner?.name === "QuotaExceededError";
}

/**
 * DB のイベントを EventStore へ戻す。500 件ごとにメインスレッドを譲る。
 * @returns 追加した件数
 */
export async function hydrate(eventStore: EventStore, db: NostrismDb): Promise<number> {
  const rows = await db.events.toArray();
  let added = 0;
  for (const row of rows) {
    addVerifiedTo(eventStore, fromRow(row));
    added++;
    if (added % HYDRATE_CHUNK === 0) await new Promise((r) => setTimeout(r));
  }
  return added;
}

/**
 * 削除の記録（deletedEvents / deletedAddrs。#579）を DB からメモリへ読み込む。
 * イベントの hydrate より先に呼ぶこと（先に呼ばないと、記録済みの行がストアへ戻ってしまう）。
 */
export async function hydrateDeletionMemory(db: NostrismDb): Promise<void> {
  const [events, addrs] = await Promise.all([db.deletedEvents.toArray(), db.deletedAddrs.toArray()]);
  loadDeletionMemory(events, addrs);
}

/**
 * 新しく確定した削除の記録を deletedEvents / deletedAddrs へ書く（#579）。
 * @returns 購読を解除する関数
 */
export function attachDeletionPersistence(db: NostrismDb): () => void {
  const subscription = deletionRecorded$.subscribe((record) => {
    const put =
      record.type === "event"
        ? db.deletedEvents.put({ id: record.id, deletedAt: record.deletedAt })
        : db.deletedAddrs.put({ coord: record.coord, deletedAt: record.deletedAt });
    put.catch((e: unknown) => {
      console.warn("[db] Failed to save the deletion record", e);
    });
  });
  return () => subscription.unsubscribe();
}
