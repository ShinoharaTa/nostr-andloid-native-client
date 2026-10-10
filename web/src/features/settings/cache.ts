import { db } from "../../db";
import { createDatabase } from "../../db/schema";

/**
 * キャッシュの DB（nostrism: イベント・送信待ち・OGP・復号済みの DM）を消す（ネイティブ purgeCache）。
 * DM は次に開いたとき復号し直す（NIP-07 / NIP-46 では再び承認を求められる）。
 * 鍵の DB（nostrism-vault）と localStorage（セッション・リレー・カラム構成など）は消さない。
 * テストでは fake-indexeddb の IDBFactory / IDBKeyRange を渡す。
 */
export async function clearCache(
  opts: { indexedDB?: IDBFactory; IDBKeyRange?: typeof IDBKeyRange } = {},
): Promise<void> {
  // 開いている接続を先に閉じる（閉じないと削除が待たされる）
  db?.close();
  await createDatabase(opts).delete();
}

/** キャッシュを消して再読み込みする（消せなくても再読み込みはする。DB は起動時に開き直す） */
export async function clearCacheAndReload(): Promise<void> {
  try {
    await clearCache();
  } catch (e) {
    console.warn("[settings] Failed to clear the cache", e);
  }
  window.location.reload();
}
