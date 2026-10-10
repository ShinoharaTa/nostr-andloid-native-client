import Dexie from "dexie";
import { eventStore } from "../nostr/store";
import { SESSION_KEY } from "../signer/session";
import { evictOnOpen } from "./events";
import { attachDeletionPersistence, attachPersistence, hydrate, hydrateDeletionMemory } from "./persistence";
import { createDatabase, DB_NAME, type NostrismDb } from "./schema";

/** 開いた DB。開けなかったら null（メモリのみで動く） */
export let db: NostrismDb | null = null;

/** これらで開けなかったら DB を消して作り直す（版の不一致・壊れた DB） */
const RECREATE_ON: ReadonlySet<string> = new Set([
  Dexie.errnames.Version,
  Dexie.errnames.Upgrade,
  Dexie.errnames.InvalidState,
  Dexie.errnames.DatabaseClosed,
  Dexie.errnames.OpenFailed,
]);

/**
 * DB を開く。版の不一致や壊れた DB で開けなければ 1 度だけ消して作り直す。それでも駄目なら null。
 * 別タブが版を上げたら閉じる（相手の更新を止めない）。
 */
export async function openDatabase(create = createDatabase): Promise<NostrismDb | null> {
  try {
    return await openOnce(create);
  } catch (e) {
    if (!RECREATE_ON.has((e as { name?: string } | null)?.name ?? "")) {
      console.error("[db] Can't open the database; running in memory only", e);
      return null;
    }
  }
  try {
    await Dexie.delete(DB_NAME);
    return await openOnce(create);
  } catch (e) {
    console.error("[db] Still can't open it after recreating; running in memory only", e);
    return null;
  }
}

async function openOnce(create: () => NostrismDb): Promise<NostrismDb> {
  const instance = create();
  await instance.open();
  instance.on("versionchange", () => instance.close());
  return instance;
}

/**
 * ブラウザに保存領域を消さないよう頼む。ログイン済みの復帰のときだけ（初訪問者に Firefox の許可を出さない）。
 * @returns 永続化されているか。API が無い・失敗は false
 */
export async function requestPersistentStorage(): Promise<boolean> {
  try {
    if (!localStorage.getItem(SESSION_KEY)) return false;
    const storage = navigator.storage;
    if (!storage?.persisted || !storage.persist) return false;
    if (await storage.persisted()) return true;
    return await storage.persist();
  } catch {
    return false;
  }
}

/**
 * 起動時に 1 度: DB を開き、掃除し、書き込みを張ってから DB のイベントをメモリへ戻す。
 * どの段で失敗しても起動は止めない（描画はこれを待たない）。
 */
export async function startPersistence(): Promise<void> {
  db = await openDatabase();
  if (!db) return;
  const me = savedPubkey();
  try {
    await evictOnOpen(db, { now: Math.floor(Date.now() / 1000), me });
  } catch (e) {
    console.warn("[db] Failed to clean up at startup", e);
  }
  // 削除記録（#579）はイベントの hydrate より先に読み込む（先に読まないと記録済みの行が戻ってしまう）
  try {
    await hydrateDeletionMemory(db);
  } catch (e) {
    console.warn("[db] Failed to restore deletion records", e);
  }
  // hydrate より先に張る（hydrate 分は hydratedSymbol で書き戻し対象外）
  try {
    attachPersistence(eventStore, db, me);
  } catch (e) {
    console.warn("[db] Failed to subscribe to writes", e);
  }
  try {
    attachDeletionPersistence(db);
  } catch (e) {
    console.warn("[db] Failed to subscribe to deletion records", e);
  }
  try {
    await hydrate(eventStore, db);
  } catch (e) {
    console.warn("[db] Failed to restore events", e);
  }
  void requestPersistentStorage();
}

/** 保存済みセッションの pubkey（restore の完了を待たずに読む） */
function savedPubkey(): string | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const { pubkey } = JSON.parse(raw) as { pubkey?: unknown };
    return typeof pubkey === "string" ? pubkey : null;
  } catch {
    return null;
  }
}
