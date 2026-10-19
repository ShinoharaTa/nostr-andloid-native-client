import { IDBFactory, IDBKeyRange } from "fake-indexeddb";
import { vi } from "vitest";
import { resetNip46ForTest } from "../signer/nip46";
import { resetNostrConnectRelayForTest } from "../signer/nip46RelayPref";
import { createNip46Store, setNip46StoreForTest } from "../signer/nip46Store";
import { createPasskeyVault, setPasskeyVaultForTest } from "../signer/passkeyVault";
import { useSession } from "../signer/session";
import {
  createKeyVault,
  createVaultDatabase,
  type KeyVaultDb,
  setKeyVaultForTest,
} from "../signer/webKeyVault";

export const PUBKEY = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d";
export const OTHER_PUBKEY = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2";

/** window.nostr（NIP-07）のフェイクを入れる。reject なら getPublicKey が拒否する */
export function installFakeNostr(opts: { pubkey?: string; reject?: boolean } = {}) {
  const getPublicKey = vi.fn(async () => {
    if (opts.reject) throw new Error("user rejected");
    return opts.pubkey ?? PUBKEY;
  });
  window.nostr = {
    getPublicKey,
    signEvent: vi.fn(async () => {
      throw new Error("not used in tests");
    }),
  };
  return { getPublicKey };
}

/**
 * 鍵の保管庫（NIP-46 の接続情報・パスキー保護(#543)も同じ DB）を fake-indexeddb の使い捨ての DB で動かす
 * （resetSession で保管先なしに戻す）
 */
export async function installTestVault(): Promise<KeyVaultDb> {
  const database = createVaultDatabase({
    name: `vault-${crypto.randomUUID()}`,
    indexedDB: new IDBFactory(),
    IDBKeyRange,
  });
  await database.open();
  setKeyVaultForTest(createKeyVault({ database: async () => database }));
  setNip46StoreForTest(createNip46Store({ database: async () => database }));
  setPasskeyVaultForTest(createPasskeyVault({ database: async () => database }));
  return database;
}

/** テスト間でフェイク・保存・ストアを初期状態へ戻す */
export function resetSession() {
  delete window.nostr;
  localStorage.clear();
  useSession.setState({ status: "loading", method: null, pubkey: null });
  setKeyVaultForTest(createKeyVault({ database: async () => null }));
  resetNip46ForTest();
  resetNostrConnectRelayForTest();
  setNip46StoreForTest(createNip46Store({ database: async () => null }));
  setPasskeyVaultForTest(createPasskeyVault({ database: async () => null }));
}
