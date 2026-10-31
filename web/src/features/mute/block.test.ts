import { finalizeEvent, generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { publishEvent } from "../../nostr/publish";
import { useSession } from "../../signer/session";
import { reportTargets } from "../actions/reactions";
import {
  BLOCK_REPORT_REASON,
  blockUser,
  loadPendingBlocks,
  MODERATION_RELAY,
  removePendingBlock,
  resetBlocksForTest,
  retryPendingBlocks,
  unblockUser,
} from "./block";
import { EMPTY_MUTE_LIST, setMuteList, setPendingBlocks, useMute, userMuteStateOf } from "./muteList";
import { MuteListError, removeMuteEntry, saveBlockedUser } from "./muteSync";

// 署名・送信はしない（送信キューの入口だけ差し替える）
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async () => ({})),
}));

// ミュートリストの取り直し・発行（公開の p だけにする等）は muteSync.test.ts。ここでは結果だけ差し替える
vi.mock("./muteSync", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./muteSync")>()),
  saveBlockedUser: vi.fn(async () => "done"),
  removeMuteEntry: vi.fn(async () => "done"),
}));

const me = getPublicKey(generateSecretKey());
const BOB = getPublicKey(generateSecretKey());
const CAROL = getPublicKey(generateSecretKey());

function pendingKey(account = me): string {
  return `nostrism.block.pending.${account}`;
}

function storedPending(account = me): string[] {
  return (localStorage.getItem(pendingKey(account)) ?? "").split("\n").filter((pk) => pk !== "");
}

beforeEach(() => {
  vi.mocked(publishEvent).mockReset();
  vi.mocked(publishEvent).mockResolvedValue({} as never);
  vi.mocked(saveBlockedUser).mockReset();
  vi.mocked(saveBlockedUser).mockResolvedValue("done");
  vi.mocked(removeMuteEntry).mockReset();
  vi.mocked(removeMuteEntry).mockResolvedValue("done");
  useSession.setState({ status: "in", method: "local", pubkey: me });
});

afterEach(() => {
  resetBlocksForTest();
  setPendingBlocks(new Set());
  setMuteList(null);
  localStorage.clear();
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

describe("通報（#963: ⋯ の通報と同じ送り先）", () => {
  it("送り先は書き込みリレー ∪ 開発者のリレー（reportTargets）", () => {
    expect(reportTargets()).toContain(MODERATION_RELAY);
  });

  it("投稿から: kind:1984・content blocked・e（other）と p。送り先は reportTargets（未送信なら知らせる）", async () => {
    const note = finalizeEvent({ kind: 1, created_at: 1, tags: [], content: "x" }, generateSecretKey());
    await blockUser(me, note.pubkey, note);
    expect(vi.mocked(publishEvent).mock.calls).toEqual([
      [
        {
          kind: 1984,
          content: BLOCK_REPORT_REASON,
          tags: [
            ["e", note.id, "other"],
            ["p", note.pubkey],
          ],
        },
        { relays: reportTargets() },
      ],
    ]);
    expect(BLOCK_REPORT_REASON).toBe("blocked");
  });

  it("プロフィールから（投稿なし）: p に other", async () => {
    await blockUser(me, BOB);
    expect(vi.mocked(publishEvent).mock.calls).toEqual([
      [{ kind: 1984, content: "blocked", tags: [["p", BOB, "other"]] }, { relays: reportTargets() }],
    ]);
  });

  it("通報に失敗してもブロックは失敗にしない", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.mocked(publishEvent).mockRejectedValue(new Error("no signer"));
    expect(await blockUser(me, BOB)).toBe("saved");
    await vi.waitFor(() => expect(warn).toHaveBeenCalled());
    warn.mockRestore();
  });
});

describe("すぐ隠す・ミュートリストに公開の p で保存", () => {
  it("押した時点で保存待ちに入って隠れ、保存できたら外れて saved", async () => {
    let resolve: (v: "done") => void = () => {};
    vi.mocked(saveBlockedUser).mockImplementationOnce(() => new Promise((r) => (resolve = r)));
    const result = blockUser(me, BOB);
    expect(useMute.getState().pending.has(BOB)).toBe(true);
    expect(useMute.getState().matcher.users.has(BOB)).toBe(true);
    expect(storedPending()).toEqual([BOB]);

    await vi.waitFor(() => expect(vi.mocked(saveBlockedUser)).toHaveBeenCalledWith(me, BOB));
    expect(useMute.getState().pending.has(BOB)).toBe(true);
    resolve("done");
    expect(await result).toBe("saved");
    expect(useMute.getState().pending.has(BOB)).toBe(false);
    expect(localStorage.getItem(pendingKey())).toBeNull();
  });

  it("すでにブロック済み（noop）も saved", async () => {
    vi.mocked(saveBlockedUser).mockResolvedValueOnce("noop");
    expect(await blockUser(me, BOB)).toBe("saved");
    expect(useMute.getState().pending.size).toBe(0);
  });

  it("保存できなければ pending を返し、保存待ちに残す（隠したまま・localStorage にも残る）", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.mocked(saveBlockedUser).mockRejectedValueOnce(new MuteListError("no-mute-list"));
    expect(await blockUser(me, BOB)).toBe("pending");
    expect(useMute.getState().matcher.users.has(BOB)).toBe(true);
    expect(storedPending()).toEqual([BOB]);
    warn.mockRestore();
  });

  it("保存は直列（先の保存が終わるまで次を始めない）", async () => {
    const resolvers: (() => void)[] = [];
    vi.mocked(saveBlockedUser).mockImplementation(() => new Promise((r) => resolvers.push(() => r("done"))));
    const first = blockUser(me, BOB);
    const second = blockUser(me, CAROL);
    await vi.waitFor(() => expect(vi.mocked(saveBlockedUser)).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 0));
    expect(vi.mocked(saveBlockedUser)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(saveBlockedUser)).toHaveBeenLastCalledWith(me, BOB);
    expect(useMute.getState().pending).toEqual(new Set([BOB, CAROL]));

    resolvers[0]();
    expect(await first).toBe("saved");
    await vi.waitFor(() => expect(vi.mocked(saveBlockedUser)).toHaveBeenCalledTimes(2));
    expect(vi.mocked(saveBlockedUser)).toHaveBeenLastCalledWith(me, CAROL);
    resolvers[1]();
    expect(await second).toBe("saved");
    expect(useMute.getState().pending.size).toBe(0);
  });
});

describe("保存待ちの読み込み・やり直し", () => {
  it("loadPendingBlocks は localStorage から読み、ログアウト（null）で空にする", () => {
    localStorage.setItem(pendingKey(), `${BOB}\n${CAROL}`);
    loadPendingBlocks(me);
    expect(useMute.getState().pending).toEqual(new Set([BOB, CAROL]));
    expect(useMute.getState().matcher.users.has(CAROL)).toBe(true);
    loadPendingBlocks(null);
    expect(useMute.getState().pending.size).toBe(0);
  });

  it("retryPendingBlocks は保存待ちのそれぞれを保存し直し、できたものだけ外す", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    localStorage.setItem(pendingKey(), `${BOB}\n${CAROL}`);
    loadPendingBlocks(me);
    vi.mocked(saveBlockedUser).mockImplementation(async (_me, pubkey) => {
      if (pubkey === CAROL) throw new MuteListError("locked");
      return "done";
    });
    await retryPendingBlocks(me);
    expect(vi.mocked(saveBlockedUser)).toHaveBeenCalledTimes(2);
    expect(useMute.getState().pending).toEqual(new Set([CAROL]));
    expect(storedPending()).toEqual([CAROL]);
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it("removePendingBlock は保存待ちだったときだけ true", () => {
    localStorage.setItem(pendingKey(), BOB);
    loadPendingBlocks(me);
    expect(removePendingBlock(me, CAROL)).toBe(false);
    expect(removePendingBlock(me, BOB)).toBe(true);
    expect(useMute.getState().pending.size).toBe(0);
    expect(localStorage.getItem(pendingKey())).toBeNull();
  });

  it("localStorage が使えなくても、セッション中は隠す", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("denied");
    });
    vi.mocked(saveBlockedUser).mockRejectedValueOnce(new MuteListError("no-mute-list"));
    expect(await blockUser(me, BOB)).toBe("pending");
    expect(useMute.getState().matcher.users.has(BOB)).toBe(true);
    setItem.mockRestore();
    warn.mockRestore();
  });
});

describe("[#963] ブロック中の状態・ミュートからの昇格・解除", () => {
  const listWith = (isPublic: boolean, isPrivate: boolean) => ({
    ...EMPTY_MUTE_LIST,
    entries: [{ category: "p" as const, value: BOB, isPublic, isPrivate }],
  });

  it("ミュート中の人をブロックすると、保存待ちの間からブロック中になり、公開の p で保存する", async () => {
    setMuteList(listWith(false, true));
    expect(userMuteStateOf(useMute.getState(), BOB)).toBe("muted");
    const result = blockUser(me, BOB);
    expect(userMuteStateOf(useMute.getState(), BOB)).toBe("blocked");
    expect(await result).toBe("saved");
    expect(vi.mocked(saveBlockedUser)).toHaveBeenCalledWith(me, BOB);
  });

  it("unblockUser: ミュートリストにあれば公開・非公開の両方を外す（removeMuteEntry）", async () => {
    setMuteList(listWith(true, true));
    expect(await unblockUser(me, BOB)).toBe("done");
    expect(vi.mocked(removeMuteEntry)).toHaveBeenCalledWith(me, "p", BOB);
  });

  it("unblockUser: 保存待ちだけなら手元で外して done（ミュートリストは触らない）", async () => {
    vi.mocked(saveBlockedUser).mockRejectedValueOnce(new MuteListError("no-mute-list"));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(await blockUser(me, BOB)).toBe("pending");
    expect(await unblockUser(me, BOB)).toBe("done");
    expect(useMute.getState().pending.has(BOB)).toBe(false);
    expect(useMute.getState().matcher.users.has(BOB)).toBe(false);
    expect(vi.mocked(removeMuteEntry)).not.toHaveBeenCalled();
    warn.mockRestore();
  });
});
