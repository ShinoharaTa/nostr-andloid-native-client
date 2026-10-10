import { EventStore } from "applesauce-core/event-store";
import Dexie from "dexie";
import { IDBFactory, IDBKeyRange } from "fake-indexeddb";
import { finalizeEvent, generateSecretKey, getPublicKey, verifyEvent } from "nostr-tools/pure";
import { afterEach, expect, it, vi } from "vitest";
import { addVerifiedTo, resetDeletionMemoryForTest } from "../nostr/store";
import { toRow } from "./events";
import { attachDeletionPersistence, attachPersistence, hydrate, hydrateDeletionMemory } from "./persistence";
import { createDatabase, type NostrismDb } from "./schema";

const NOW = 1_800_000_000;

const cleanups: (() => void)[] = [];
afterEach(() => {
  vi.useRealTimers();
  for (const cleanup of cleanups.splice(0)) cleanup();
  resetDeletionMemoryForTest();
});

async function setup(me: string | null = null) {
  const db = createDatabase({
    name: `persist-${crypto.randomUUID()}`,
    indexedDB: new IDBFactory(),
    IDBKeyRange,
  });
  await db.open();
  // fake-indexeddb は setImmediate で進むので、それは本物のまま残す（bufferTime は setInterval）
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"] });
  const store = new EventStore({ verifyEvent });
  const detach = attachPersistence(store, db, me);
  cleanups.push(() => {
    detach();
    db.close();
  });
  return { db, store };
}

function signed(kind: number, key = generateSecretKey(), created_at = NOW, content = "") {
  return finalizeEvent({ kind, created_at, tags: [], content }, key);
}

function countKind(db: NostrismDb, kind: number) {
  return db.events.where("kind").equals(kind).count();
}

/** 次の bulkPut を 1 度だけ容量超過で落とす。返す関数で、書き直し（2 回目）が終わるまで待つ */
function failNextPutWithQuota(db: NostrismDb, error: Error = new Dexie.QuotaExceededError("full")) {
  const bulkPut = vi.spyOn(db.events, "bulkPut").mockRejectedValueOnce(error);
  return async () => {
    await vi.waitFor(() => expect(bulkPut).toHaveBeenCalledTimes(2));
    await bulkPut.mock.results[1]?.value;
  };
}

it("保存する kind だけを 500ms ごとにまとめて書く", async () => {
  const { db, store } = await setup();
  for (let i = 0; i < 3; i++) store.add(signed(0));
  store.add(signed(1));

  await vi.advanceTimersByTimeAsync(499);
  expect(await db.events.count()).toBe(0);

  await vi.advanceTimersByTimeAsync(1);
  expect(await countKind(db, 0)).toBe(3);
  expect(await countKind(db, 1)).toBe(0);
});

// 400 件の署名（鍵ごとに別の kind:0）で CPU を使うので、並列で走らせたときの既定の 5 秒では足りないことがある
it("400 件たまったら 500ms を待たずに書く", { timeout: 30_000 }, async () => {
  const events = Array.from({ length: 400 }, () => signed(0));
  const { db, store } = await setup();
  const bulkPut = vi.spyOn(db.events, "bulkPut");
  for (const event of events.slice(0, 399)) store.add(event);
  expect(bulkPut).not.toHaveBeenCalled();

  // 時間を進めないまま、400 件目で書き始める
  store.add(events[399]);
  expect(bulkPut).toHaveBeenCalledTimes(1);
  await bulkPut.mock.results[0]?.value;
  expect(await db.events.count()).toBe(400);
});

it.each([
  ["QuotaExceededError", () => new Dexie.QuotaExceededError("full")],
  [
    "AbortError の inner",
    () => new Dexie.AbortError("aborted", new DOMException("full", "QuotaExceededError")),
  ],
])("容量超過（%s）なら cap 以下でも 3/4 まで削り、そのバッチを 1 度だけ書き直す", async (_, error) => {
  const { db, store } = await setup();
  const others = Array.from({ length: 8 }, (_, i) => toRow(signed(0, generateSecretKey(), NOW + i)));
  await db.events.bulkPut(others);
  const retried = failNextPutWithQuota(db, error());

  const next = signed(0, generateSecretKey(), NOW + 100);
  store.add(next);
  await vi.advanceTimersByTimeAsync(500);
  await retried();

  // 8 件 → floor(8 * 0.75) = 6 件まで古い順に削ってから 1 件書く
  expect(await db.events.count()).toBe(7);
  expect(await db.events.get(next.id)).toBeDefined();
  expect(await db.events.bulkGet([others[0].id, others[1].id])).toEqual([undefined, undefined]);
  expect(await db.events.get(others[2].id)).toBeDefined();
});

it("容量超過で削るとき、自分の行は古くても残す", async () => {
  const meKey = generateSecretKey();
  const { db, store } = await setup(getPublicKey(meKey));
  const mine = [toRow(signed(0, meKey, NOW)), toRow(signed(3, meKey, NOW))];
  const others = Array.from({ length: 6 }, (_, i) => toRow(signed(0, generateSecretKey(), NOW + 1 + i)));
  await db.events.bulkPut([...mine, ...others]);
  const retried = failNextPutWithQuota(db);

  store.add(signed(0));
  await vi.advanceTimersByTimeAsync(500);
  await retried();

  for (const row of mine) expect(await db.events.get(row.id)).toBeDefined();
  expect(await db.events.bulkGet([others[0].id, others[1].id])).toEqual([undefined, undefined]);
  expect(await db.events.count()).toBe(7);
});

it("置換で外れた旧版は DB からも消す", async () => {
  const { db, store } = await setup();
  const key = generateSecretKey();
  const old = signed(0, key, NOW, '{"name":"old"}');
  store.add(old);
  await vi.advanceTimersByTimeAsync(500);
  expect(await db.events.get(old.id)).toBeDefined();

  const next = signed(0, key, NOW + 1, '{"name":"new"}');
  store.add(next);
  await vi.advanceTimersByTimeAsync(500);

  expect(await db.events.get(old.id)).toBeUndefined();
  expect(await db.events.get(next.id)).toBeDefined();
});

it("hydrate した分はストアに入り、DB へ書き戻さない", async () => {
  const { db, store } = await setup();
  const rows = [signed(0), signed(3)].map(toRow);
  await db.events.bulkPut(rows);
  const bulkPut = vi.spyOn(db.events, "bulkPut");

  expect(await hydrate(store, db)).toBe(2);
  for (const row of rows) expect(store.getEvent(row.id)).toBeDefined();

  await vi.advanceTimersByTimeAsync(600);
  expect(await db.events.count()).toBe(2);
  expect(bulkPut).not.toHaveBeenCalled();
});

it("DB の行は検証済みとして扱い、署名が壊れていても hydrate で入る", async () => {
  const { db, store } = await setup();
  const event = signed(0);
  const broken = { ...toRow(event), sig: "0".repeat(128) };
  await db.events.put(broken);

  // 同じ中身をリレーから受けたなら検証で落ちる
  const { id, pubkey, kind, created_at, content, tags, sig } = broken;
  expect(
    new EventStore({ verifyEvent }).add({ id, pubkey, kind, created_at, content, tags, sig }),
  ).toBeNull();

  expect(await hydrate(store, db)).toBe(1);
  expect(store.getEvent(event.id)).toBeDefined();
});

// ---- #579: 削除の記憶（deletedEvents / deletedAddrs） ----

async function deletionDb() {
  const db = createDatabase({
    name: `persist-${crypto.randomUUID()}`,
    indexedDB: new IDBFactory(),
    IDBKeyRange,
  });
  await db.open();
  const detach = attachDeletionPersistence(db);
  cleanups.push(() => {
    detach();
    db.close();
  });
  return db;
}

it("kind:5 で消した id は deletedEvents に残り、再読み込み相当（メモリを空にして読み直す）でも入らない", async () => {
  const db = await deletionDb();
  const key = generateSecretKey();
  const store = new EventStore({ verifyEvent });
  const note = finalizeEvent({ kind: 1, created_at: NOW, tags: [], content: "x" }, key);
  addVerifiedTo(store, note);
  const deletion = finalizeEvent(
    {
      kind: 5,
      created_at: NOW + 1,
      tags: [
        ["e", note.id],
        ["k", "1"],
      ],
      content: "",
    },
    key,
  );
  addVerifiedTo(store, deletion);

  await vi.waitFor(async () => {
    expect(await db.deletedEvents.get(note.id)).toBeDefined();
  });

  // 再読み込み相当: メモリを空にしてから DB の削除記録を読み直す
  resetDeletionMemoryForTest();
  await hydrateDeletionMemory(db);

  const store2 = new EventStore({ verifyEvent });
  expect(addVerifiedTo(store2, note)).toBeNull();
  expect(store2.getEvent(note.id)).toBeUndefined();
});

it("座標（addressable。kind:30023）の削除は deletedAddrs に残り、再読み込み相当でも古い版は入らず新しい版は入る", async () => {
  const db = await deletionDb();
  const key = generateSecretKey();
  const me = getPublicKey(key);
  const store = new EventStore({ verifyEvent });
  const deletion = finalizeEvent(
    { kind: 5, created_at: NOW + 1, tags: [["a", `30023:${me}:x`]], content: "" },
    key,
  );
  addVerifiedTo(store, deletion);

  await vi.waitFor(async () => {
    expect(await db.deletedAddrs.get(`30023:${me}:x`)).toBeDefined();
  });

  resetDeletionMemoryForTest();
  await hydrateDeletionMemory(db);

  const store2 = new EventStore({ verifyEvent });
  const old = finalizeEvent({ kind: 30023, created_at: NOW, tags: [["d", "x"]], content: "旧" }, key);
  expect(addVerifiedTo(store2, old)).toBeNull();

  const fresh = finalizeEvent({ kind: 30023, created_at: NOW + 2, tags: [["d", "x"]], content: "新" }, key);
  expect(addVerifiedTo(store2, fresh)).not.toBeNull();
  expect(store2.getEvent(fresh.id)).toBeDefined();
});

it("hydrate で読み込む行が deletedEvents に記録済みなら、起動時の復元でストアへ戻さない", async () => {
  const db = await deletionDb();
  const key = generateSecretKey();
  const profile = finalizeEvent({ kind: 0, created_at: NOW, tags: [], content: "{}" }, key);
  await db.events.put(toRow(profile));
  await db.deletedEvents.put({ id: profile.id, deletedAt: NOW + 1 });
  await hydrateDeletionMemory(db);

  const store = new EventStore({ verifyEvent });
  await hydrate(store, db);
  expect(store.getEvent(profile.id)).toBeUndefined();
});
