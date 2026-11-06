import { normalizeURL } from "applesauce-core/helpers/url";
import { noteEncode, npubEncode } from "nostr-tools/nip19";
import { generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enqueueSigned } from "../../nostr/publish";
import { currentSigner, useSession } from "../../signer/session";
import { createCipherSigner } from "../../test/cipherSigner";
import { peerDmRelays } from "../dm/dmRelays";
import { wrapGiftWrap } from "../dm/nip17";
import {
  blockNoticeText,
  DEVELOPER_NOTICE_FALLBACK_RELAY,
  DEVELOPER_PUBKEY,
  notifyDeveloper,
  reportNoticeText,
} from "./developerNotice";

// 送信キューには積まず、積んだものを見る
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  enqueueSigned: vi.fn(async () => {}),
}));

// 開発者の DM リレー（kind:10050）はテストごとに決める
vi.mock("../dm/dmRelays", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../dm/dmRelays")>()),
  peerDmRelays: vi.fn(async () => []),
}));

// 包む中身（rumor）と宛先を見るため、本物の wrapGiftWrap を見張る
vi.mock("../dm/nip17", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../dm/nip17")>();
  return { ...actual, wrapGiftWrap: vi.fn(actual.wrapGiftWrap) };
});

vi.mock("../../signer/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../signer/session")>()),
  currentSigner: vi.fn(),
}));

const TARGET = getPublicKey(generateSecretKey());
const POST = "ab".repeat(32);

function login(opts: { nip44?: boolean; pubkey?: string } = {}) {
  const { signer, pubkey } = createCipherSigner({ nip44: opts.nip44 });
  vi.mocked(currentSigner).mockReturnValue(signer);
  useSession.setState({ status: "in", method: "local", pubkey: opts.pubkey ?? pubkey });
  return opts.pubkey ?? pubkey;
}

beforeEach(() => {
  vi.mocked(enqueueSigned).mockReset();
  vi.mocked(enqueueSigned).mockResolvedValue(undefined);
  vi.mocked(peerDmRelays).mockReset();
  vi.mocked(peerDmRelays).mockResolvedValue([]);
  vi.mocked(wrapGiftWrap).mockClear();
  vi.mocked(currentSigner).mockReset();
});

afterEach(() => {
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

describe("[#961] 知らせの本文（ネイティブ DeveloperNotice と同じ文字列）", () => {
  it("受け取るのは開発者の npub", () => {
    expect(npubEncode(DEVELOPER_PUBKEY)).toBe(
      "npub1l60d6h2uvdwa9yq0r7r2suhgrnsadcst6nsx2j03xwhxhu2cjyascejxe5",
    );
  });

  it("ブロック: 相手、投稿から押したときは投稿も", () => {
    expect(blockNoticeText(TARGET)).toBe(`[Nostrism] block\nuser: nostr:${npubEncode(TARGET)}`);
    expect(blockNoticeText(TARGET, POST)).toBe(
      `[Nostrism] block\nuser: nostr:${npubEncode(TARGET)}\npost: nostr:${noteEncode(POST)}`,
    );
  });

  it("通報: 種類と相手、投稿の通報は投稿も、理由は空でなければ", () => {
    expect(reportNoticeText("spam", TARGET, POST)).toBe(
      `[Nostrism] report: spam\nuser: nostr:${npubEncode(TARGET)}\npost: nostr:${noteEncode(POST)}`,
    );
    expect(reportNoticeText("impersonation", TARGET)).toBe(
      `[Nostrism] report: impersonation\nuser: nostr:${npubEncode(TARGET)}`,
    );
    expect(reportNoticeText("other", TARGET, null, "理由")).toBe(
      `[Nostrism] report: other\nuser: nostr:${npubEncode(TARGET)}\nreason: 理由`,
    );
  });
});

describe("[#961] notifyDeveloper", () => {
  it("開発者あての gift wrap を 1 通だけ（自分宛ての控えなし）、開発者の DM リレー ＋ 固定のリレーへ積む", async () => {
    const me = login();
    vi.mocked(peerDmRelays).mockResolvedValue(["wss://dm.example"]);
    const text = blockNoticeText(TARGET, POST);
    await notifyDeveloper(text);

    expect(peerDmRelays).toHaveBeenCalledWith(DEVELOPER_PUBKEY);
    expect(wrapGiftWrap).toHaveBeenCalledTimes(1);
    const [, rumor, to] = vi.mocked(wrapGiftWrap).mock.calls[0];
    expect(to).toBe(DEVELOPER_PUBKEY);
    expect(rumor).toMatchObject({ pubkey: me, kind: 14, content: text, tags: [["p", DEVELOPER_PUBKEY]] });

    expect(enqueueSigned).toHaveBeenCalledTimes(1);
    const [wrap, opts] = vi.mocked(enqueueSigned).mock.calls[0];
    expect(wrap.kind).toBe(1059);
    expect(wrap.tags).toEqual([["p", DEVELOPER_PUBKEY]]);
    expect(wrap.pubkey).not.toBe(me); // 包みは使い捨ての鍵
    expect(opts).toEqual({
      relays: [normalizeURL("wss://dm.example"), normalizeURL(DEVELOPER_NOTICE_FALLBACK_RELAY)],
      refId: null,
      notify: false,
    });
  });

  it("開発者の DM リレーが固定のリレーと同じなら 1 つにまとめる。取れなければ固定のリレーだけ", async () => {
    login();
    vi.mocked(peerDmRelays).mockResolvedValue([DEVELOPER_NOTICE_FALLBACK_RELAY]);
    await notifyDeveloper("x");
    expect(vi.mocked(enqueueSigned).mock.calls[0][1].relays).toEqual([
      normalizeURL(DEVELOPER_NOTICE_FALLBACK_RELAY),
    ]);

    vi.mocked(peerDmRelays).mockRejectedValue(new Error("offline"));
    await notifyDeveloper("y");
    expect(vi.mocked(enqueueSigned).mock.calls[1][1].relays).toEqual([
      normalizeURL(DEVELOPER_NOTICE_FALLBACK_RELAY),
    ]);
  });

  it("NIP-44 を使えない署名者・未ログイン・自分が開発者のときは何もしない", async () => {
    login({ nip44: false });
    await notifyDeveloper("x");
    login({ pubkey: DEVELOPER_PUBKEY });
    await notifyDeveloper("x");
    useSession.setState({ status: "loading", method: null, pubkey: null });
    vi.mocked(currentSigner).mockReturnValue(null);
    await notifyDeveloper("x");
    expect(wrapGiftWrap).not.toHaveBeenCalled();
    expect(enqueueSigned).not.toHaveBeenCalled();
  });

  it("送れなくても投げない（本文はログに出さない）", async () => {
    login();
    vi.mocked(enqueueSigned).mockRejectedValue(new Error("boom"));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    await expect(notifyDeveloper("秘密の本文")).resolves.toBeUndefined();
    expect(warn).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(warn.mock.calls)).not.toContain("秘密の本文");
    warn.mockRestore();
  });
});
