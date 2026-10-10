import type { Filter } from "applesauce-core/helpers/filter";
import { IDBFactory, IDBKeyRange } from "fake-indexeddb";
import * as nip04 from "nostr-tools/nip04";
import * as nip44 from "nostr-tools/nip44";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, type Observable, Subject } from "rxjs";
import { afterEach, beforeEach, describe, expect, it, type Mock, vi } from "vitest";
import { createDatabase, type DmMessageRow, type NostrismDb } from "../../db/schema";
import { requestOnce, subscribeUnstored } from "../../nostr/pool";
import type { Signer } from "../../nostr/signer";
import { addVerified, eventStore } from "../../nostr/store";
import { currentSigner, type SessionMethod, useSession } from "../../signer/session";
import { VaultError } from "../../signer/webKeyVault";
import { createCipherSigner } from "../../test/cipherSigner";
import { giftWrap, makeRumor, makeWrap } from "../../test/giftWrap";
import { useDmSeen } from "./dmSeen";
import { recordSentDm, removeSentDm, resumeDecrypting, startDecrypting, startDm } from "./dmService";
import { conversationsOf, useDm } from "./dmStore";

// リレーには繋がない（購読はテストから流す）
vi.mock("../../nostr/pool", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/pool")>()),
  requestOnce: vi.fn(),
  subscribeUnstored: vi.fn(),
}));

// 署名者はテストごとに決める
vi.mock("../../signer/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../signer/session")>()),
  currentSigner: vi.fn(),
}));

const aliceKey = generateSecretKey();
const ALICE = getPublicKey(aliceKey);

let signer: Signer;
let me: string;
let myKey: Uint8Array;
let decrypt44: Mock<(peer: string, ciphertext: string) => Promise<string>>;
let feed: Subject<NostrEvent | "EOSE">;
let relays$: Observable<string[]> | null;
let filters: Filter[] | null;
const stops: (() => void)[] = [];
const databases: NostrismDb[] = [];

beforeEach(() => {
  ({ signer, pubkey: me, secretKey: myKey } = createCipherSigner());
  const inner = signer.nip44;
  decrypt44 = vi.fn(async (peer: string, ciphertext: string) => {
    if (!inner) throw new Error("no nip44");
    return inner.decrypt(peer, ciphertext);
  });
  if (signer.nip44) signer.nip44 = { encrypt: signer.nip44.encrypt, decrypt: decrypt44 };
  vi.mocked(currentSigner).mockImplementation(() => signer);
  vi.mocked(requestOnce).mockReset();
  vi.mocked(requestOnce).mockReturnValue(EMPTY);
  relays$ = null;
  filters = null;
  feed = new Subject();
  vi.mocked(subscribeUnstored).mockReset();
  vi.mocked(subscribeUnstored).mockImplementation((relays, f) => {
    relays$ = relays;
    filters = f;
    return feed;
  });
});

afterEach(() => {
  for (const stop of stops.splice(0)) stop();
  vi.useRealTimers();
  useSession.setState({ status: "loading", method: null, pubkey: null });
  useDm.getState().reset(null);
  useDmSeen.setState({ me: null, first: 0, peers: {} });
  localStorage.clear();
  for (const database of databases.splice(0)) database.close();
});

async function openDb(): Promise<NostrismDb> {
  const database = createDatabase({
    name: `dm-${crypto.randomUUID()}`,
    indexedDB: new IDBFactory(),
    IDBKeyRange,
  });
  await database.open();
  databases.push(database);
  return database;
}

/** 時間を止める。fake-indexeddb は setImmediate で進むので、それは本物のまま残す */
function fakeTimers() {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"] });
}

function login(method: SessionMethod, pubkey = me) {
  useSession.setState({ status: "in", method, pubkey });
}

function start(database: NostrismDb | null) {
  const stop = startDm({ database });
  stops.push(stop);
  return stop;
}

/** 購読が張られるまで待つ（DB の読み込みの後） */
async function subscribed() {
  await vi.waitFor(() => expect(vi.mocked(subscribeUnstored)).toHaveBeenCalled());
}

function wrapFromAlice(content: string, createdAt = 1_700_000_000): NostrEvent {
  const rumor = makeRumor(aliceKey, { content, tags: [["p", me]], created_at: createdAt });
  return giftWrap(rumor, aliceKey, me, createdAt);
}

function contents(): string[] {
  return Object.values(useDm.getState().messages)
    .map((m) => m.content)
    .sort();
}

describe("購読", () => {
  it("NIP-44 と NIP-04 が使えれば 1059 と 4（受信・送信）を購読する", async () => {
    login("local");
    start(await openDb());
    await subscribed();
    expect(filters).toEqual([
      { kinds: [1059], "#p": [me] },
      { kinds: [4], "#p": [me] },
      { kinds: [4], authors: [me] },
    ]);
    expect(useDm.getState()).toMatchObject({ owner: me, nip17: "ok", nip04: "ok", loaded: false });

    feed.next("EOSE");
    expect(useDm.getState().loaded).toBe(true);
  });

  it("NIP-44 の無い署名者では 1059 を購読しない（4 だけ）", async () => {
    ({ signer, pubkey: me } = createCipherSigner({ nip44: false }));
    login("nip07");
    start(await openDb());
    await subscribed();
    expect(filters).toEqual([
      { kinds: [4], "#p": [me] },
      { kinds: [4], authors: [me] },
    ]);
    expect(useDm.getState().nip17).toBe("no-nip44");
  });

  it("暗号を使えない署名者では購読せず、読み込み済みにする", async () => {
    ({ signer, pubkey: me } = createCipherSigner({ nip44: false, nip04: false }));
    login("nip07");
    start(await openDb());
    await vi.waitFor(() => expect(useDm.getState().loaded).toBe(true));
    expect(vi.mocked(subscribeUnstored)).not.toHaveBeenCalled();
  });

  it("自分の kind:10050 が EventStore に入ると、その relay が購読先に加わる", async () => {
    login("local");
    start(await openDb());
    await subscribed();
    let current: string[] = [];
    const sub = relays$?.subscribe((r) => {
      current = r;
    });
    expect(current).not.toContain("wss://dm-inbox.example/");
    expect(vi.mocked(requestOnce)).toHaveBeenCalledWith(
      expect.arrayContaining(["wss://purplepag.es"]),
      [{ kinds: [10050], authors: [me], limit: 1 }],
      10_000,
    );

    addVerified(
      finalizeEvent(
        { kind: 10050, created_at: 1_700_000_000, tags: [["relay", "wss://dm-inbox.example"]], content: "" },
        myKey,
      ),
    );
    expect(current).toContain("wss://dm-inbox.example/");
    sub?.unsubscribe();
  });
});

describe("復号と保存", () => {
  it("gift wrap と kind:4 を復号して一覧に出し、DB に保存する。EventStore には入れない", async () => {
    const database = await openDb();
    login("local");
    start(database);
    await subscribed();

    const wrap = wrapFromAlice("nip17 hello");
    const legacy = finalizeEvent(
      {
        kind: 4,
        created_at: 1_700_000_100,
        tags: [["p", me]],
        content: nip04.encrypt(aliceKey, me, "nip04 hello"),
      },
      aliceKey,
    );
    feed.next(wrap);
    feed.next(legacy);
    await vi.waitFor(() => expect(contents()).toEqual(["nip04 hello", "nip17 hello"]));

    await vi.waitFor(async () => expect(await database.dmProcessed.count()).toBe(2));
    const rows = await database.dmMessages.toArray();
    expect(rows.map((r) => [r.owner, r.peer, r.proto]).sort()).toEqual([
      [me, ALICE, "nip04"],
      [me, ALICE, "nip17"],
    ]);
    expect(eventStore.getByFilters({ kinds: [1059, 4, 14] })).toEqual([]);
  });

  it("署名が不正なイベントは捨て、壊れた gift wrap は ok: false で記録する", async () => {
    const database = await openDb();
    login("local");
    start(database);
    await subscribed();

    const good = wrapFromAlice("good");
    // リレーから届くイベントは JSON から作られる（finalizeEvent の検証済みの印は付いていない）
    feed.next(JSON.parse(JSON.stringify({ ...good, sig: "0".repeat(128) })) as NostrEvent);
    const broken = makeWrap("{not json", me);
    feed.next(broken);
    await vi.waitFor(async () => expect(await database.dmProcessed.get([me, broken.id])).toBeDefined());
    expect(await database.dmProcessed.get([me, broken.id])).toEqual({
      owner: me,
      eventId: broken.id,
      ok: false,
    });
    expect(await database.dmProcessed.count()).toBe(1);
    expect(contents()).toEqual([]);
  });

  it("処理済みの id（DB の dmProcessed）は 2 回目の起動で署名者を呼ばず、保存分をすぐ出す", async () => {
    const database = await openDb();
    login("local");
    const stop = start(database);
    await subscribed();
    const wrap = wrapFromAlice("saved");
    feed.next(wrap);
    await vi.waitFor(async () => expect(await database.dmProcessed.count()).toBe(1));
    expect(decrypt44).toHaveBeenCalledTimes(2);
    stop();
    useDm.getState().reset(null);

    // 再読み込み
    decrypt44.mockClear();
    vi.mocked(subscribeUnstored).mockClear();
    feed = new Subject();
    start(database);
    await subscribed();
    expect(contents()).toEqual(["saved"]);
    feed.next(wrap);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(decrypt44).not.toHaveBeenCalled();
  });

  it("NIP-07 では startDecrypting() まで署名者を呼ばない（保存済みの分は出す）", async () => {
    const database = await openDb();
    fakeTimers();
    login("nip07");
    start(database);
    await subscribed();
    feed.next(wrapFromAlice("later"));
    await vi.waitFor(() => expect(useDm.getState().pending).toBe(1));
    // 購読の読み込み待ち・プロフィールのまとめ・kind:10050 の待ちを越えても、時間では始まらない
    await vi.advanceTimersByTimeAsync(10_000);
    expect(decrypt44).not.toHaveBeenCalled();
    expect(useDm.getState()).toMatchObject({ pending: 1, decrypting: false });

    startDecrypting();
    expect(useDm.getState().decrypting).toBe(true);
    // 一覧に出るのは DB への記録より先（pending が 0 になるのは記録の後）なので両方を待つ
    await vi.waitFor(() => {
      expect(contents()).toEqual(["later"]);
      expect(useDm.getState().pending).toBe(0);
    });
  });

  it("nsec（local）ではログイン直後から復号する", async () => {
    login("local");
    start(await openDb());
    await subscribed();
    expect(useDm.getState().decrypting).toBe(true);
    feed.next(wrapFromAlice("now"));
    await vi.waitFor(() => expect(contents()).toEqual(["now"]));
  });

  it("DB が無くてもメモリだけで動く", async () => {
    login("local");
    start(null);
    await subscribed();
    feed.next(wrapFromAlice("memory"));
    await vi.waitFor(() => expect(contents()).toEqual(["memory"]));
  });

  it("署名者の拒否は記録せず、3 回続いたら一時停止し、再開で続ける", async () => {
    const database = await openDb();
    fakeTimers();
    decrypt44.mockRejectedValue(new Error("rejected"));
    login("nip07");
    start(database);
    await subscribed();
    for (let n = 0; n < 4; n++) feed.next(wrapFromAlice(`m${n}`, 1_700_000_000 + n));
    startDecrypting();
    await vi.waitFor(() => expect(useDm.getState()).toMatchObject({ paused: true, pending: 1 }));
    expect(decrypt44).toHaveBeenCalledTimes(3);
    expect(await database.dmProcessed.count()).toBe(0);

    // 次は許可する（本物の鍵で復号する）
    decrypt44.mockReset();
    decrypt44.mockImplementation(async (peer: string, ciphertext: string) =>
      nip44.decrypt(ciphertext, nip44.getConversationKey(myKey, peer)),
    );
    resumeDecrypting();
    await vi.waitFor(() => {
      expect(contents()).toEqual(["m0"]);
      expect(useDm.getState()).toMatchObject({ paused: false, pending: 0 });
    });
  });

  it("nsec でも鍵の保管庫の失敗（VaultError）は invalid と記録せず、一時停止 → 再開で復号する", async () => {
    const database = await openDb();
    fakeTimers();
    decrypt44.mockRejectedValue(new VaultError("unavailable"));
    login("local");
    start(database);
    await subscribed();
    for (let n = 0; n < 4; n++) feed.next(wrapFromAlice(`v${n}`, 1_700_000_000 + n));
    await vi.waitFor(() => expect(useDm.getState()).toMatchObject({ paused: true, pending: 1 }));
    expect(decrypt44).toHaveBeenCalledTimes(3);
    expect(await database.dmProcessed.count()).toBe(0);
    expect(contents()).toEqual([]);

    // 保管庫が戻った
    decrypt44.mockReset();
    decrypt44.mockImplementation(async (peer: string, ciphertext: string) =>
      nip44.decrypt(ciphertext, nip44.getConversationKey(myKey, peer)),
    );
    resumeDecrypting();
    await vi.waitFor(() => {
      expect(contents()).toHaveLength(1);
      expect(useDm.getState()).toMatchObject({ paused: false, pending: 0 });
    });
    await vi.waitFor(async () => expect(await database.dmProcessed.count()).toBe(1));
    expect((await database.dmProcessed.toArray())[0].ok).toBe(true);
  });
});

describe("ログアウト・アカウントの切り替え", () => {
  it("ログアウトで購読を止め、DB の dm 2 表を空にする", async () => {
    const database = await openDb();
    login("local");
    start(database);
    await subscribed();
    feed.next(wrapFromAlice("bye"));
    await vi.waitFor(async () => expect(await database.dmMessages.count()).toBe(1));

    useSession.setState({ status: "out", method: null, pubkey: null });
    expect(feed.observed).toBe(false);
    expect(useDm.getState()).toMatchObject({ owner: null, messages: {} });
    await vi.waitFor(async () => {
      expect(await database.dmMessages.count()).toBe(0);
      expect(await database.dmProcessed.count()).toBe(0);
    });
  });

  it("別アカウントでログインすると前のアカウントの行が消える", async () => {
    const database = await openDb();
    await database.dmMessages.put({
      owner: ALICE,
      id: "old",
      peer: me,
      sender: me,
      content: "alice's dm",
      tags: [],
      createdAt: 1,
      proto: "nip17",
    });
    await database.dmProcessed.put({ owner: ALICE, eventId: "w", ok: true });

    login("local");
    start(database);
    await subscribed();
    expect(contents()).toEqual([]);
    expect(await database.dmMessages.count()).toBe(0);
    expect(await database.dmProcessed.count()).toBe(0);
  });

  it("ログインで既読を読み込み（初回は今が基準 = 過去の DM は未読にしない）、ログアウトで既読の保存値を消す", async () => {
    const key = `nostrism.dm.seen.${me}`;
    login("local");
    start(await openDb());
    expect(useDmSeen.getState().me).toBe(me);
    expect(localStorage.getItem(key)).not.toBeNull();
    await subscribed();
    feed.next(wrapFromAlice("old"));
    await vi.waitFor(() => expect(contents()).toEqual(["old"]));
    const seen = useDmSeen.getState();
    expect(conversationsOf(Object.values(useDm.getState().messages), me, seen)[0].unread).toBe(0);

    useSession.setState({ status: "out", method: null, pubkey: null });
    expect(localStorage.getItem(key)).toBeNull();
    expect(useDmSeen.getState().me).toBeNull();
  });

  it("アカウントを切り替えると前のアカウントの既読を消し、新しいアカウントの既読を読み込む", async () => {
    login("local");
    start(await openDb());
    await subscribed();
    expect(localStorage.getItem(`nostrism.dm.seen.${me}`)).not.toBeNull();

    login("local", ALICE);
    expect(localStorage.getItem(`nostrism.dm.seen.${me}`)).toBeNull();
    expect(localStorage.getItem(`nostrism.dm.seen.${ALICE}`)).not.toBeNull();
    expect(useDmSeen.getState().me).toBe(ALICE);
  });

  it("起動時の未ログイン（復元中）では DB を消さない", async () => {
    const database = await openDb();
    await database.dmProcessed.put({ owner: me, eventId: "w", ok: true });
    start(database);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(await database.dmProcessed.count()).toBe(1);
    expect(vi.mocked(subscribeUnstored)).not.toHaveBeenCalled();
  });
});

describe("送信の記録（recordSentDm / removeSentDm）", () => {
  function sentRow(content: string): { row: DmMessageRow; echo: NostrEvent } {
    const rumor = makeRumor(myKey, { content, tags: [["p", ALICE]] });
    const row: DmMessageRow = {
      owner: me,
      id: rumor.id,
      peer: ALICE,
      sender: me,
      content,
      tags: rumor.tags,
      createdAt: rumor.created_at,
      proto: "nip17",
    };
    return { row, echo: giftWrap(rumor, myKey, me) };
  }

  it("送った DM を出して保存し、送った gift wrap（自分宛ての控え）は返ってきても復号しない。取り消すと消える", async () => {
    const database = await openDb();
    login("local");
    start(database);
    await subscribed();
    const { row, echo } = sentRow("sent");

    await recordSentDm(row, [echo.id]);
    expect(useDm.getState().messages[row.id]).toEqual(row);
    expect(await database.dmMessages.get([me, row.id])).toEqual(row);
    expect(await database.dmProcessed.get([me, echo.id])).toEqual({ owner: me, eventId: echo.id, ok: true });

    // 控えの後に届いた相手の DM が出た時点で、控えは署名者を呼んでいない（順に処理する）
    feed.next(echo);
    feed.next(wrapFromAlice("after"));
    await vi.waitFor(() => expect(contents()).toEqual(["after", "sent"]));
    expect(decrypt44).toHaveBeenCalledTimes(2);

    await removeSentDm(me, row.id);
    expect(useDm.getState().messages[row.id]).toBeUndefined();
    expect(await database.dmMessages.get([me, row.id])).toBeUndefined();
  });

  it("別のアカウント（ログアウト・切り替えの後）の行は記録しない", async () => {
    const database = await openDb();
    login("local");
    start(database);
    await subscribed();
    const { row, echo } = sentRow("other");

    await recordSentDm({ ...row, owner: ALICE }, [echo.id]);
    expect(contents()).toEqual([]);
    expect(await database.dmMessages.count()).toBe(0);
  });
});
