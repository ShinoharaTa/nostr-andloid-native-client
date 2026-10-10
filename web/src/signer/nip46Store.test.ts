import { IDBFactory, IDBKeyRange } from "fake-indexeddb";
import { generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createNip46Store, NIP46_ROW_ID, type Nip46Saved, type Nip46VaultRow } from "./nip46Store";
import {
  createKeyVault,
  createVaultDatabase,
  type KeyVaultDb,
  VAULT_ROW_ID,
  VaultError,
} from "./webKeyVault";

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

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function sample(): Nip46Saved {
  return {
    pubkey: getPublicKey(generateSecretKey()),
    remote: getPublicKey(generateSecretKey()),
    relays: ["wss://relay.example/"],
    clientKey: generateSecretKey(),
  };
}

async function rowOf(db: KeyVaultDb): Promise<Nip46VaultRow> {
  const row = await db.vault.get(NIP46_ROW_ID);
  if (!row) throw new Error("no row");
  return row as Nip46VaultRow;
}

describe("save / load", () => {
  it("行 nip46 に取り出せない鍵で暗号化して置き、load で同じ値に戻る。clientKey は 0 で埋めない", async () => {
    const db = await openDb();
    const store = createNip46Store({ database: async () => db });
    const saved = sample();
    const copy = Uint8Array.from(saved.clientKey);

    await store.save(saved);

    expect(Array.from(saved.clientKey)).toEqual(Array.from(copy));
    const row = await rowOf(db);
    expect(row).toMatchObject({
      id: "nip46",
      version: 1,
      pubkey: saved.pubkey,
      remote: saved.remote,
      relays: saved.relays,
    });
    expect(row.key).toBeInstanceOf(CryptoKey);
    expect(row.key.extractable).toBe(false);
    expect(row.iv.length).toBe(12);
    expect(row.ct.length).toBe(48);

    const loaded = await store.load();
    expect(loaded).toMatchObject({ pubkey: saved.pubkey, remote: saved.remote, relays: saved.relays });
    expect(Array.from(loaded?.clientKey ?? [])).toEqual(Array.from(copy));
  });

  it("行にクライアント鍵の平文（32 byte・hex）が無い", async () => {
    const db = await openDb();
    const store = createNip46Store({ database: async () => db });
    const saved = sample();
    await store.save(saved);

    const row = await rowOf(db);
    const text = hex(saved.clientKey);
    for (const value of Object.values(row)) {
      if (typeof value === "string") expect(value).not.toContain(text);
      if (ArrayBuffer.isView(value)) expect(hex(value as Uint8Array)).not.toContain(text);
    }
    expect(JSON.stringify(row.relays)).not.toContain(text);
  });

  it("DB も WebCrypto も無ければ load は null、save は VaultError(unavailable)", async () => {
    const noDb = createNip46Store({ database: async () => null });
    expect(await noDb.load()).toBeNull();
    await expect(noDb.save(sample())).rejects.toMatchObject({ name: "VaultError", reason: "unavailable" });

    const db = await openDb();
    const noSubtle = createNip46Store({ database: async () => db, subtle: () => undefined });
    expect(await noSubtle.load()).toBeNull();
    await expect(noSubtle.save(sample())).rejects.toBeInstanceOf(VaultError);
  });

  it("行が無ければ load は null", async () => {
    const db = await openDb();
    expect(await createNip46Store({ database: async () => db }).load()).toBeNull();
  });
});

describe("壊れた行", () => {
  const cases: [string, (row: Nip46VaultRow) => Record<string, unknown>][] = [
    ["pubkey が hex でない", (row) => ({ ...row, pubkey: "xyz" })],
    ["remote が hex でない", (row) => ({ ...row, remote: "A".repeat(64) })],
    ["relays が空", (row) => ({ ...row, relays: [] })],
    ["relays に wss:// 以外", (row) => ({ ...row, relays: ["wss://ok.example/", "ws://plain.example/"] })],
    ["key が CryptoKey でない", (row) => ({ ...row, key: "not a key" })],
    ["iv が Uint8Array でない", (row) => ({ ...row, iv: [1, 2, 3] })],
    ["ct が Uint8Array でない", (row) => ({ ...row, ct: "abc" })],
    ["復号できない", (row) => ({ ...row, iv: new Uint8Array(12) })],
  ];

  it.each(cases)("%s → load は null で行を消す", async (_name, broken) => {
    const db = await openDb();
    const store = createNip46Store({ database: async () => db });
    await store.save(sample());
    await db.vault.put({ ...broken(await rowOf(db)), id: NIP46_ROW_ID });

    expect(await store.load()).toBeNull();
    expect(await db.vault.get(NIP46_ROW_ID)).toBeUndefined();
  });

  it("長さが 32 でない → load は null で行を消す", async () => {
    const db = await openDb();
    const store = createNip46Store({ database: async () => db });
    await store.save(sample());
    const row = await rowOf(db);
    const ct = new Uint8Array(
      await crypto.subtle.encrypt({ name: "AES-GCM", iv: row.iv }, row.key, new Uint8Array(31)),
    );
    await db.vault.put({ ...row, ct });

    expect(await store.load()).toBeNull();
    expect(await db.vault.get(NIP46_ROW_ID)).toBeUndefined();
  });
});

describe("local 行との独立", () => {
  it("local 行の clear で nip46 行は消えず、nip46 の clear で local 行は消えない", async () => {
    const db = await openDb();
    const vault = createKeyVault({ database: async () => db });
    const store = createNip46Store({ database: async () => db });

    await vault.generate();
    await store.save(sample());
    await vault.clear();
    expect(await db.vault.get(VAULT_ROW_ID)).toBeUndefined();
    expect(await store.load()).not.toBeNull();

    await vault.generate();
    await store.clear();
    expect(await db.vault.get(NIP46_ROW_ID)).toBeUndefined();
    expect(await vault.storedPubkey()).not.toBeNull();
  });

  it("clear は失敗しても投げず、行や鍵をログに出さない", async () => {
    const db = await openDb();
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(db.vault, "delete").mockRejectedValue(new Error("boom"));

    await expect(createNip46Store({ database: async () => db }).clear()).resolves.toBeUndefined();
    expect(warn).toHaveBeenCalledWith("[vault] Failed to delete");
  });
});
