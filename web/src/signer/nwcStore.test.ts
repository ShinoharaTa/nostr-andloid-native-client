import { IDBFactory, IDBKeyRange } from "fake-indexeddb";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createNwcStore, NWC_ROW_ID, type NwcVaultRow } from "./nwcStore";
import { createVaultDatabase, type KeyVaultDb, VaultError } from "./webKeyVault";

const databases: KeyVaultDb[] = [];

afterEach(() => {
  vi.restoreAllMocks();
  for (const database of databases.splice(0)) database.close();
});

async function openDb(): Promise<KeyVaultDb> {
  const database = createVaultDatabase({
    name: `vault-${crypto.randomUUID()}`,
    indexedDB: new IDBFactory(),
    IDBKeyRange,
  });
  await database.open();
  databases.push(database);
  return database;
}

const URI = `nostr+walletconnect://${"a".repeat(64)}?relay=wss%3A%2F%2Frelay.example.com&secret=${"b".repeat(64)}`;

async function rowOf(db: KeyVaultDb): Promise<NwcVaultRow> {
  const row = await db.vault.get(NWC_ROW_ID);
  if (!row) throw new Error("no row");
  return row as NwcVaultRow;
}

describe("save / load", () => {
  it("行 nwc に取り出せない鍵で暗号化して置き、load で同じ接続文字列に戻る", async () => {
    const db = await openDb();
    const store = createNwcStore({ database: async () => db });

    await store.save(URI);

    const row = await rowOf(db);
    expect(row).toMatchObject({ id: "nwc", version: 1 });
    expect(row.key).toBeInstanceOf(CryptoKey);
    expect(row.key.extractable).toBe(false);
    expect(row.iv.length).toBe(12);

    expect(await store.load()).toBe(URI);
  });

  it("secret を含む平文をそのまま行に持たない", async () => {
    const db = await openDb();
    const store = createNwcStore({ database: async () => db });
    await store.save(URI);

    const row = await rowOf(db);
    const secret = "b".repeat(64);
    for (const value of Object.values(row)) {
      if (typeof value === "string") expect(value).not.toContain(secret);
      if (ArrayBuffer.isView(value)) {
        const hex = Array.from(value as Uint8Array, (b) => b.toString(16).padStart(2, "0")).join("");
        expect(hex).not.toContain(secret);
      }
    }
  });

  it("DB も WebCrypto も無ければ load は null、save は VaultError(unavailable)", async () => {
    const noDb = createNwcStore({ database: async () => null });
    expect(await noDb.load()).toBeNull();
    await expect(noDb.save(URI)).rejects.toMatchObject({ name: "VaultError", reason: "unavailable" });

    const db = await openDb();
    const noSubtle = createNwcStore({ database: async () => db, subtle: () => undefined });
    expect(await noSubtle.load()).toBeNull();
    await expect(noSubtle.save(URI)).rejects.toBeInstanceOf(VaultError);
  });

  it("行が無ければ load は null", async () => {
    const db = await openDb();
    expect(await createNwcStore({ database: async () => db }).load()).toBeNull();
  });
});

describe("壊れた行", () => {
  const cases: [string, (row: NwcVaultRow) => Record<string, unknown>][] = [
    ["key が CryptoKey でない", (row) => ({ ...row, key: "not a key" })],
    ["iv が Uint8Array でない", (row) => ({ ...row, iv: [1, 2, 3] })],
    ["ct が Uint8Array でない", (row) => ({ ...row, ct: "abc" })],
    ["復号できない", (row) => ({ ...row, iv: new Uint8Array(12) })],
  ];

  it.each(cases)("%s → load は null で行を消す", async (_name, broken) => {
    const db = await openDb();
    const store = createNwcStore({ database: async () => db });
    await store.save(URI);
    await db.vault.put({ ...broken(await rowOf(db)), id: NWC_ROW_ID });

    expect(await store.load()).toBeNull();
    expect(await db.vault.get(NWC_ROW_ID)).toBeUndefined();
  });
});

describe("clear", () => {
  it("消したあとは load が null", async () => {
    const db = await openDb();
    const store = createNwcStore({ database: async () => db });
    await store.save(URI);

    await store.clear();

    expect(await db.vault.get(NWC_ROW_ID)).toBeUndefined();
    expect(await store.load()).toBeNull();
  });

  it("失敗しても投げず、行や鍵をログに出さない", async () => {
    const db = await openDb();
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(db.vault, "delete").mockRejectedValue(new Error("boom"));

    await expect(createNwcStore({ database: async () => db }).clear()).resolves.toBeUndefined();
    expect(warn).toHaveBeenCalledWith("[vault] Failed to delete");
  });
});
