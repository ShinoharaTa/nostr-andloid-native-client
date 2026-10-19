import { PrivateKeySigner } from "applesauce-signers/signers/private-key-signer";
import { generateSecretKey, getPublicKey, verifyEvent } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FakeBunker } from "../test/fakeBunker";
import { installTestVault, resetSession } from "../test/fakeNostr";
import { useToast } from "../ui/toast";
import {
  connectBunker,
  disconnectNip46,
  NIP46_PERMISSIONS,
  Nip46Error,
  nip46Signer,
  parseBunkerInput,
  pauseNip46,
  restoreNip46,
  resumeNip46,
  StrictNostrConnectSigner,
  setNip46PoolForTest,
  startNostrConnect,
  useNip46Auth,
} from "./nip46";
import { setNostrConnectRelay } from "./nip46RelayPref";
import { getNip46Store, NIP46_ROW_ID } from "./nip46Store";

const RELAY = "wss://relay.example/";
const SECRET = "s3cret-token";
const NSEC_APP = "wss://relay.nsec.app/";

/** 届いた応答の処理（復号・JSON）を終わらせる */
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

let bunker: FakeBunker;

beforeEach(() => {
  bunker = new FakeBunker();
  setNip46PoolForTest(bunker);
});

afterEach(() => {
  vi.useRealTimers();
  resetSession();
  useToast.setState({ queue: [] });
});

describe("parseBunkerInput", () => {
  const remote = getPublicKey(generateSecretKey());

  it("remote・wss:// のリレー（正規化・重複なし）・secret を読む。前後の空白は許す", () => {
    const input = `  bunker://${remote}?relay=wss://a.example&relay=wss%3A%2F%2Fa.example%2F&relay=ws://b.example&secret=abc \n`;
    expect(parseBunkerInput(input)).toEqual({ remote, relays: ["wss://a.example/"], secret: "abc" });
  });

  it("secret は無くてもよい", () => {
    expect(parseBunkerInput(`bunker://${remote}?relay=wss://a.example`)).toEqual({
      remote,
      relays: ["wss://a.example/"],
      secret: undefined,
    });
  });

  it.each([
    ["relay が無い", `bunker://${remote}`],
    ["ws:// だけ", `bunker://${remote}?relay=ws://a.example`],
    ["hex でない", "bunker://npub1abc?relay=wss://a.example"],
    ["bunker:// で始まらない", `nostrconnect://${remote}?relay=wss://a.example`],
    ["空", "   "],
  ])("%s → null", (_name, input) => {
    expect(parseBunkerInput(input)).toBeNull();
  });
});

describe("connectBunker", () => {
  it("connect の params は [remote, secret, 権限]。get_public_key のユーザーを返し、保管庫に nip46 行を置く", async () => {
    const db = await installTestVault();

    const { pubkey } = await connectBunker(bunker.uri({ secret: SECRET }));

    expect(pubkey).toBe(bunker.user);
    expect(bunker.methods()).toEqual(["connect", "get_public_key"]);
    expect(bunker.requests[0]?.params).toEqual([bunker.remote, SECRET, NIP46_PERMISSIONS.join(",")]);
    // リレーの AUTH（NIP-42）の署名も求める
    expect(NIP46_PERMISSIONS).toContain("sign_event:22242");
    const row = await db.vault.get(NIP46_ROW_ID);
    expect(row).toMatchObject({ id: "nip46", pubkey: bunker.user, remote: bunker.remote, relays: [RELAY] });
    // secret は保存しない
    expect(JSON.stringify(row)).not.toContain(SECRET);
    const saved = await getNip46Store().load();
    expect(getPublicKey(saved?.clientKey ?? new Uint8Array(32))).toBe(bunker.requests[0]?.client);
    expect(nip46Signer()).not.toBeNull();
  });

  it("bunker:// として読めなければ invalid-uri で、何も送らない", async () => {
    await expect(connectBunker("bunker://xyz?relay=wss://a.example")).rejects.toMatchObject({
      name: "Nip46Error",
      reason: "invalid-uri",
    });
    expect(bunker.requests).toHaveLength(0);
    expect(bunker.openSubscriptions).toBe(0);
  });

  it("署名側のエラー応答は rejected。購読と AUTH の監視を閉じ、何も保存しない", async () => {
    const db = await installTestVault();
    bunker.replies.set("connect", { error: "invalid secret" });

    const error = await connectBunker(bunker.uri({ secret: SECRET })).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(Nip46Error);
    expect(error).toMatchObject({ reason: "rejected" });
    // 署名側の応答の中身はメッセージに入れない
    expect((error as Error).message).toBe("rejected");
    expect(bunker.openSubscriptions).toBe(0);
    expect(bunker.relay(RELAY).challenge$.observed).toBe(false);
    expect(await db.vault.count()).toBe(0);
    expect(nip46Signer()).toBeNull();
  });

  it("無応答なら 180 秒で timeout。購読を閉じる", async () => {
    vi.useFakeTimers();
    bunker.replies.set("connect", "silent");
    const result = connectBunker(bunker.uri()).catch((e: unknown) => e);

    await vi.advanceTimersByTimeAsync(179_999);
    expect(bunker.openSubscriptions).toBe(1);
    await vi.advanceTimersByTimeAsync(1);

    expect(await result).toMatchObject({ name: "Nip46Error", reason: "timeout" });
    expect(bunker.openSubscriptions).toBe(0);
  });

  it("get_public_key が無応答なら 60 秒で timeout", async () => {
    vi.useFakeTimers();
    bunker.replies.set("get_public_key", "silent");
    const result = connectBunker(bunker.uri()).catch((e: unknown) => e);

    await vi.advanceTimersByTimeAsync(60_000);

    expect(await result).toMatchObject({ reason: "timeout" });
    expect(bunker.openSubscriptions).toBe(0);
  });

  it("abort で cancelled。購読を閉じる", async () => {
    bunker.replies.set("connect", "silent");
    const controller = new AbortController();
    const result = connectBunker(bunker.uri(), controller.signal).catch((e: unknown) => e);
    await vi.waitFor(() => expect(bunker.requests).toHaveLength(1));

    controller.abort();

    expect(await result).toMatchObject({ reason: "cancelled" });
    expect(bunker.openSubscriptions).toBe(0);
  });

  it("保管先が使えなければ unavailable。購読を閉じる", async () => {
    await expect(connectBunker(bunker.uri())).rejects.toMatchObject({ reason: "unavailable" });
    expect(bunker.openSubscriptions).toBe(0);
    expect(nip46Signer()).toBeNull();
  });

  it("2 回目の接続は前の接続を閉じる", async () => {
    await installTestVault();
    await connectBunker(bunker.uri());
    expect(bunker.openSubscriptions).toBe(1);

    await connectBunker(bunker.uri());

    expect(bunker.openSubscriptions).toBe(1);
    // 前の接続へ logout は送らない
    expect(bunker.methods()).not.toContain("logout");
  });
});

describe("署名者", () => {
  it("restoreNip46 の後の最初の signEvent で connect を送らない（sign_event だけ）", async () => {
    await installTestVault();
    await getNip46Store().save({
      pubkey: bunker.user,
      remote: bunker.remote,
      relays: [RELAY],
      clientKey: generateSecretKey(),
    });

    expect(await restoreNip46(bunker.user)).toBe(true);
    // 張り直すだけで何も送らない
    expect(bunker.requests).toHaveLength(0);
    expect(bunker.openSubscriptions).toBe(1);

    const signer = nip46Signer();
    expect([...(signer?.caps ?? [])].sort()).toEqual(["nip04", "nip44", "sign"]);
    await expect(signer?.publicKey()).resolves.toBe(bunker.user);
    const signed = await signer?.signEvent({ kind: 1, content: "hello", tags: [], created_at: 1 });

    expect(bunker.methods()).toEqual(["sign_event"]);
    expect(JSON.parse(bunker.requests[0]?.params[0] ?? "{}")).toMatchObject({ pubkey: bunker.user, kind: 1 });
    expect(signed && verifyEvent(signed)).toBe(true);
    expect(signed?.pubkey).toBe(bunker.user);
  });

  it("保管が無い・ユーザーが違えば restoreNip46 は false", async () => {
    await installTestVault();
    expect(await restoreNip46(bunker.user)).toBe(false);

    await getNip46Store().save({
      pubkey: bunker.user,
      remote: bunker.remote,
      relays: [RELAY],
      clientKey: generateSecretKey(),
    });
    expect(await restoreNip46(getPublicKey(generateSecretKey()))).toBe(false);
    expect(nip46Signer()).toBeNull();
  });

  it("戻りの pubkey が違う署名は例外", async () => {
    await installTestVault();
    await connectBunker(bunker.uri());
    const other = generateSecretKey();
    bunker.replies.set("sign_event", (req) => ({ result: bunker.signAs(other, req.params[0] ?? "{}") }));

    await expect(
      nip46Signer()?.signEvent({ kind: 1, content: "x", tags: [], created_at: 1 }),
    ).rejects.toThrow();
  });

  it("署名が 60 秒無応答なら timeout で、トーストは 30 秒に 1 回まで", async () => {
    await installTestVault();
    await connectBunker(bunker.uri());
    bunker.replies.set("sign_event", "silent");
    vi.useFakeTimers();
    const signer = nip46Signer();
    const template = { kind: 1, content: "x", tags: [], created_at: 1 };

    const first = signer?.signEvent(template).catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(await first).toMatchObject({ reason: "timeout" });
    expect(useToast.getState().queue).toEqual([
      "署名アプリから応答がありません。アプリを開いてからもう一度お試しください",
    ]);

    // 60 秒後の 2 回目は前のトーストから 30 秒以上経っているので出す。すぐ後の 3 回目は出さない
    bunker.replies.set("nip44_encrypt", "silent");
    const second = signer?.signEvent(template).catch((e: unknown) => e);
    const third = signer?.nip44?.encrypt(bunker.remote, "x").catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(await second).toMatchObject({ reason: "timeout" });
    expect(await third).toMatchObject({ reason: "timeout" });
    expect(useToast.getState().queue).toHaveLength(2);
  });
});

describe("auth_url", () => {
  it("https なら承認待ちの URL を出す（window.open は呼ばない）", async () => {
    await installTestVault();
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    bunker.replies.set("connect", {
      authUrl: "https://signer.example/approve?t=1",
      after: { result: "ack" },
    });

    await connectBunker(bunker.uri());

    expect(useNip46Auth.getState().url).toBe("https://signer.example/approve?t=1");
    expect(open).not.toHaveBeenCalled();
    open.mockRestore();
  });

  it("https 以外は出さず、その要求は失敗する", async () => {
    await installTestVault();
    bunker.replies.set("connect", { authUrl: "javascript:alert(1)" });

    await expect(connectBunker(bunker.uri())).rejects.toMatchObject({ reason: "rejected" });
    expect(useNip46Auth.getState().url).toBeNull();
  });
});

describe("一時停止（pauseNip46 / resumeNip46）", () => {
  /** 保管した接続から張り直す（restoreNip46） */
  async function restore() {
    await installTestVault();
    await getNip46Store().save({
      pubkey: bunker.user,
      remote: bunker.remote,
      relays: [RELAY],
      clientKey: generateSecretKey(),
    });
    expect(await restoreNip46(bunker.user)).toBe(true);
  }

  it("接続が無ければ何もしない（後の接続も止めたままにしない）", async () => {
    const relay = bunker.relay(RELAY);

    pauseNip46();
    resumeNip46();

    expect(relay.keepAlive).toBe(30_000);
    expect(nip46Signer()).toBeNull();

    // 接続が無いときの pause は一時停止中にしない（後の resume が張り直さない）
    pauseNip46();
    await restore();
    resumeNip46();
    await tick();
    expect(bunker.openSubscriptions).toBe(1);
    expect(relay.keepAlive).toBe(30_000);
  });

  it("restoreNip46 の後の pause で購読と AUTH の監視を止め、使っていない接続をすぐ閉じる。署名者は残す", async () => {
    await restore();
    const relay = bunker.relay(RELAY);
    expect(bunker.openSubscriptions).toBe(1);
    expect(relay.challenge$.observed).toBe(true);

    pauseNip46();

    expect(bunker.openSubscriptions).toBe(0);
    expect(relay.challenge$.observed).toBe(false);
    // 購読をやめた接続は keepAlive を待たずに閉じる
    expect(relay.keepAlive).toBe(0);
    expect(nip46Signer()).not.toBeNull();
    expect(bunker.requests).toHaveLength(0);
  });

  it("resume で購読と AUTH の監視を張り直し、すぐ後の signEvent は connect を送らずに sign_event だけ届く", async () => {
    await restore();
    const relay = bunker.relay(RELAY);
    pauseNip46();

    // open() の購読が張られるのを遅らせる（open() はクライアント鍵の公開鍵を待ってから購読する）
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const original = PrivateKeySigner.prototype.getPublicKey;
    const getPublicKeySpy = vi
      .spyOn(PrivateKeySigner.prototype, "getPublicKey")
      .mockImplementation(async function (this: PrivateKeySigner) {
        await gate;
        return original.call(this);
      });

    resumeNip46();
    // open() を待たずに要求する
    const pending = nip46Signer()?.signEvent({ kind: 1, content: "hello", tags: [], created_at: 1 });
    await tick();
    // open() はもう呼んだので戻す（失敗しても後のテストへ持ち越さない）
    getPublicKeySpy.mockRestore();
    // 購読が張られるまで送らない（先に送ると応答を取り逃がす）
    expect(bunker.requests).toHaveLength(0);
    release();
    const signed = await pending;

    expect(bunker.methods()).toEqual(["sign_event"]);
    expect(signed && verifyEvent(signed)).toBe(true);
    expect(signed?.pubkey).toBe(bunker.user);
    expect(bunker.openSubscriptions).toBe(1);
    expect(relay.keepAlive).toBe(30_000);
    relay.challenge$.next("challenge-1");
    await vi.waitFor(() => expect(relay.authenticate).toHaveBeenCalledTimes(1));
  });

  it("二重の pause / resume は 1 回と同じ", async () => {
    await restore();
    const relay = bunker.relay(RELAY);

    pauseNip46();
    pauseNip46();
    expect(bunker.openSubscriptions).toBe(0);
    expect(relay.keepAlive).toBe(0);

    resumeNip46();
    resumeNip46();
    await tick();
    expect(bunker.openSubscriptions).toBe(1);
    expect(relay.keepAlive).toBe(30_000);
    // AUTH の監視も 1 本だけ
    relay.challenge$.next("challenge-1");
    await vi.waitFor(() => expect(relay.authenticate).toHaveBeenCalled());
    await tick();
    expect(relay.authenticate).toHaveBeenCalledTimes(1);

    await nip46Signer()?.signEvent({ kind: 1, content: "x", tags: [], created_at: 1 });
    expect(bunker.methods()).toEqual(["sign_event"]);
  });

  it("承認待ちの nostrconnect:// は止めない", async () => {
    await restore();
    const { uri, done } = startNostrConnect();
    await vi.waitFor(() => expect(bunker.openSubscriptions).toBe(2));

    pauseNip46();

    expect(bunker.openSubscriptions).toBe(1);
    bunker.acceptNostrConnect(uri);
    await expect(done).resolves.toEqual({ pubkey: bunker.user });
  });
});

describe("AUTH", () => {
  it("challenge$ に値が来たらクライアント鍵の署名者で authenticate する（ユーザーの鍵ではない）", async () => {
    await installTestVault();
    await connectBunker(bunker.uri());
    const relay = bunker.relay(RELAY);
    expect(relay.authenticate).not.toHaveBeenCalled();

    relay.challenge$.next("challenge-1");

    await vi.waitFor(() => expect(relay.authenticate).toHaveBeenCalledTimes(1));
    const signer = relay.authenticate.mock.calls[0]?.[0];
    const authPubkey = await signer?.getPublicKey();
    expect(authPubkey).toBe(bunker.requests[0]?.client);
    expect(authPubkey).not.toBe(bunker.user);
  });

  it("authenticate の失敗は無視する", async () => {
    await installTestVault();
    await connectBunker(bunker.uri());
    const relay = bunker.relay(RELAY);
    relay.authenticate.mockRejectedValueOnce(new Error("auth failed"));

    relay.challenge$.next("challenge-1");

    await vi.waitFor(() => expect(relay.authenticate).toHaveBeenCalledTimes(1));
    expect(nip46Signer()).not.toBeNull();
  });
});

describe("disconnectNip46", () => {
  it("署名側へ logout を送り、購読を閉じて nip46 行を消す", async () => {
    const db = await installTestVault();
    await connectBunker(bunker.uri());

    await disconnectNip46();

    expect(bunker.methods()).toEqual(["connect", "get_public_key", "logout"]);
    expect(bunker.openSubscriptions).toBe(0);
    expect(bunker.relay(RELAY).challenge$.observed).toBe(false);
    expect(await db.vault.get(NIP46_ROW_ID)).toBeUndefined();
    expect(nip46Signer()).toBeNull();
  });

  it("署名側が無応答でも 3 秒で終わる", async () => {
    await installTestVault();
    await connectBunker(bunker.uri());
    bunker.replies.set("logout", "silent");
    vi.useFakeTimers();
    let done = false;
    const result = disconnectNip46().then(() => {
      done = true;
    });

    await vi.advanceTimersByTimeAsync(2_999);
    expect(done).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await result;

    expect(done).toBe(true);
    expect(bunker.openSubscriptions).toBe(0);
  });

  it("接続が無くても保管した行を消す", async () => {
    const db = await installTestVault();
    await getNip46Store().save({
      pubkey: bunker.user,
      remote: bunker.remote,
      relays: [RELAY],
      clientKey: generateSecretKey(),
    });

    await disconnectNip46();

    expect(await db.vault.get(NIP46_ROW_ID)).toBeUndefined();
    expect(bunker.requests).toHaveLength(0);
  });
});

describe("StrictNostrConnectSigner", () => {
  function strictSigner() {
    const inner = new StrictNostrConnectSigner({
      relays: [RELAY],
      signer: new PrivateKeySigner(generateSecretKey()),
      pool: bunker,
      connectSecret: SECRET,
    });
    return { inner, uri: inner.getNostrConnectURI({ name: "test" }) };
  }

  async function waitConnected(inner: StrictNostrConnectSigner, uri: string) {
    const waiting = inner.waitForSigner();
    await vi.waitFor(() => expect(bunker.openSubscriptions).toBe(1));
    bunker.acceptNostrConnect(uri);
    await waiting;
  }

  it('"ack" と違う secret の応答では決まらず、secret が一致した応答で決まる（remote は送り手）', async () => {
    const { inner, uri } = strictSigner();
    let connected = false;
    const waiting = inner.waitForSigner().then(() => {
      connected = true;
    });
    await vi.waitFor(() => expect(bunker.openSubscriptions).toBe(1));

    bunker.acceptNostrConnect(uri, { result: "ack" });
    bunker.acceptNostrConnect(uri, { result: "wrong-secret" });
    bunker.acceptNostrConnect(uri, { result: "ack", nip04: true });
    await tick();

    expect(connected).toBe(false);
    expect(inner.remote).toBeUndefined();
    expect(inner.isConnected).toBe(false);

    bunker.acceptNostrConnect(uri);
    await waiting;

    expect(inner.remote).toBe(bunker.remote);
    expect(inner.isConnected).toBe(true);
    await inner.close();
  });

  it("NIP-04 の応答でも secret が一致すれば決まる", async () => {
    const { inner, uri } = strictSigner();
    const waiting = inner.waitForSigner();
    await vi.waitFor(() => expect(bunker.openSubscriptions).toBe(1));

    bunker.acceptNostrConnect(uri, { nip04: true });
    await waiting;

    expect(inner.remote).toBe(bunker.remote);
    await inner.close();
  });

  it("接続後は remote 以外からの応答を無視する", async () => {
    const { inner, uri } = strictSigner();
    await waitConnected(inner, uri);
    const other = generateSecretKey();
    // 別の鍵が secret を送っても remote は変わらない
    bunker.acceptNostrConnect(uri, { from: other });
    bunker.replies.set("get_public_key", "silent");
    let pubkey: string | null = null;
    const pending = inner.getPublicKey().then((value) => {
      pubkey = value;
    });
    await vi.waitFor(() => expect(bunker.methods()).toEqual(["get_public_key"]));
    const req = bunker.requests[0];
    if (!req) throw new Error("no request");

    bunker.send(req.client, { id: req.id, result: getPublicKey(other) }, { from: other });
    await tick();

    expect(pubkey).toBeNull();
    expect(inner.remote).toBe(bunker.remote);

    bunker.send(req.client, { id: req.id, result: bunker.user });
    await pending;
    expect(pubkey).toBe(bunker.user);
    await inner.close();
  });
});

describe("startNostrConnect", () => {
  it("URI は nostrconnect://<client>?secret=…&name=Nostrism&url=…&perms=…&relay=wss%3A%2F%2Frelay.nsec.app（既定）", () => {
    const { uri, done, cancel } = startNostrConnect();
    void done.catch(() => {});

    const [head, query] = uri.split("?");
    expect(head).toMatch(/^nostrconnect:\/\/[0-9a-f]{64}$/);
    expect([...new URLSearchParams(query)]).toEqual([
      ["secret", expect.stringMatching(/^[0-9a-f]{32}$/)],
      ["name", "Nostrism"],
      ["url", `${location.origin}/`],
      ["perms", NIP46_PERMISSIONS.join(",")],
      ["relay", "wss://relay.nsec.app"],
    ]);
    expect(query).toMatch(/&relay=wss%3A%2F%2Frelay\.nsec\.app$/);
    cancel();
  });

  it("[#899] 引数が無ければ設定値の中継リレーで URI を作り、そのリレーで待つ", async () => {
    setNostrConnectRelay("wss://nos.lol");
    const { uri, done, cancel } = startNostrConnect();
    void done.catch(() => {});

    expect(new URLSearchParams(uri.split("?")[1]).getAll("relay")).toEqual(["wss://nos.lol"]);
    await vi.waitFor(() => expect(bunker.relay("wss://nos.lol/").challenge$.observed).toBe(true));
    expect(bunker.relays.has(NSEC_APP)).toBe(false);
    cancel();
  });

  it("[#899] 引数のリレーが設定値より優先", () => {
    setNostrConnectRelay("wss://nos.lol");
    const { uri, done, cancel } = startNostrConnect("wss://relay.example");
    void done.catch(() => {});

    expect(new URLSearchParams(uri.split("?")[1]).getAll("relay")).toEqual(["wss://relay.example"]);
    cancel();
  });

  it("承認されたら get_public_key のユーザーを返し、保管庫に nip46 行を置く（secret は保存しない）", async () => {
    const db = await installTestVault();
    const { uri, done } = startNostrConnect();
    const secret = new URLSearchParams(uri.split("?")[1]).get("secret") ?? "";
    await vi.waitFor(() => expect(bunker.openSubscriptions).toBe(1));

    bunker.acceptNostrConnect(uri);

    expect(await done).toEqual({ pubkey: bunker.user });
    // クライアントからは connect を送らない
    expect(bunker.methods()).toEqual(["get_public_key"]);
    const row = await db.vault.get(NIP46_ROW_ID);
    expect(row).toMatchObject({
      id: "nip46",
      pubkey: bunker.user,
      remote: bunker.remote,
      relays: ["wss://relay.nsec.app"],
    });
    expect(JSON.stringify(row)).not.toContain(secret);
    const saved = await getNip46Store().load();
    expect(`nostrconnect://${getPublicKey(saved?.clientKey ?? new Uint8Array(32))}`).toBe(uri.split("?")[0]);

    const signed = await nip46Signer()?.signEvent({ kind: 1, content: "x", tags: [], created_at: 1 });
    expect(signed?.pubkey).toBe(bunker.user);
  });

  it('"ack" だけでは決まらず、cancel で cancelled。購読と AUTH の監視を閉じる', async () => {
    await installTestVault();
    const { uri, done, cancel } = startNostrConnect();
    const result = done.catch((e: unknown) => e);
    await vi.waitFor(() => expect(bunker.openSubscriptions).toBe(1));
    expect(bunker.relay(NSEC_APP).challenge$.observed).toBe(true);

    bunker.acceptNostrConnect(uri, { result: "ack" });
    await tick();
    cancel();

    const error = await result;
    expect(error).toBeInstanceOf(Nip46Error);
    expect(error).toMatchObject({ reason: "cancelled" });
    expect(bunker.requests).toHaveLength(0);
    expect(bunker.openSubscriptions).toBe(0);
    expect(bunker.relay(NSEC_APP).challenge$.observed).toBe(false);
    expect(nip46Signer()).toBeNull();
  });

  it("承認されなければ 180 秒で timeout。購読を閉じる", async () => {
    vi.useFakeTimers();
    const { done } = startNostrConnect();
    const result = done.catch((e: unknown) => e);

    await vi.advanceTimersByTimeAsync(179_999);
    expect(bunker.openSubscriptions).toBe(1);
    await vi.advanceTimersByTimeAsync(1);

    const error = await result;
    expect(error).toMatchObject({ name: "Nip46Error", reason: "timeout" });
    // secret はメッセージに入れない
    expect((error as Error).message).toBe("timeout");
    expect(bunker.openSubscriptions).toBe(0);
  });

  it("保管先が使えなければ unavailable。購読を閉じる", async () => {
    const { uri, done } = startNostrConnect();
    const result = done.catch((e: unknown) => e);
    await vi.waitFor(() => expect(bunker.openSubscriptions).toBe(1));

    bunker.acceptNostrConnect(uri);

    expect(await result).toMatchObject({ reason: "unavailable" });
    expect(bunker.openSubscriptions).toBe(0);
    expect(nip46Signer()).toBeNull();
  });

  it("終わった後の cancel は接続を閉じない", async () => {
    await installTestVault();
    const { uri, done, cancel } = startNostrConnect();
    await vi.waitFor(() => expect(bunker.openSubscriptions).toBe(1));
    bunker.acceptNostrConnect(uri);
    await done;

    cancel();

    expect(bunker.openSubscriptions).toBe(1);
    expect(nip46Signer()).not.toBeNull();
  });
});
