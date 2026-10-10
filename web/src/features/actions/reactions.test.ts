import { act, renderHook } from "@testing-library/react";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { Observable } from "rxjs";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { subscribe } from "../../nostr/pool";
import { type EventDraft, PublishError, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { createTestSigner } from "../../test/fakeSigner";
import { loadRecentEmojis, setDefaultReaction, useDefaultReaction } from "./reactionPrefs";
import {
  ensureMyReactionsSubscribed,
  publishReaction,
  publishRepost,
  reactionDisplay,
  reactionKey,
  reactWithDefault,
  reportNote,
  reportUser,
  requestDelete,
  useIsReacted,
  useIsReposted,
} from "./reactions";

// 署名・送信はしない（publishEvent だけ差し替えて draft を見る）
vi.mock("../../nostr/publish", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/publish")>();
  return { ...actual, publishEvent: vi.fn() };
});

// リレーへ REQ を張らない
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  return { ...actual, subscribe: vi.fn() };
});

let meKey: Uint8Array;
let me: string;

beforeEach(() => {
  const signer = createTestSigner();
  meKey = signer.secretKey;
  me = signer.pubkey;
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  vi.mocked(publishEvent).mockReset();
  vi.mocked(publishEvent).mockImplementation(async (draft: EventDraft) =>
    finalizeEvent(
      { kind: draft.kind, content: draft.content, tags: draft.tags, created_at: unixNow() },
      meKey,
    ),
  );
});

afterEach(() => {
  localStorage.clear();
  useDefaultReaction.setState({ content: "+", image: null });
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

function note(key = generateSecretKey(), { kind = 1, tags = [] as string[][] } = {}): NostrEvent {
  return finalizeEvent({ kind, created_at: unixNow(), tags, content: "本文" }, key);
}

/** 自分のイベントをストアに入れる */
function mine(kind: number, content: string, tags: string[][]): NostrEvent {
  const event = finalizeEvent({ kind, created_at: unixNow(), tags, content }, meKey);
  eventStore.add(event);
  return event;
}

function drafts(): EventDraft[] {
  return vi.mocked(publishEvent).mock.calls.map((c) => c[0]);
}

describe("reactionKey / reactionDisplay", () => {
  it("+ と空は ❤️ として照合する", () => {
    expect(reactionKey("+")).toBe("❤️");
    expect(reactionKey("")).toBe("❤️");
    expect(reactionKey("🔥")).toBe("🔥");
  });

  it("表示は trim、+ → ❤️、- → 👎、:code: は emoji タグの画像", () => {
    const key = generateSecretKey();
    const r = (content: string, tags: string[][] = []) =>
      finalizeEvent({ kind: 7, created_at: unixNow(), tags, content }, key);
    expect(reactionDisplay(r(" + "))).toEqual({ text: "❤️", imageUrl: null });
    expect(reactionDisplay(r("-"))).toEqual({ text: "👎", imageUrl: null });
    expect(reactionDisplay(r(":cat:", [["emoji", "cat", "https://e/cat.png"]]))).toEqual({
      text: ":cat:",
      imageUrl: "https://e/cat.png",
    });
    expect(reactionDisplay(r(" 🔥 "))).toEqual({ text: "🔥", imageUrl: null });
  });
});

describe("publishReaction", () => {
  it("既定は + で e → p（ヒントが無ければ空）", async () => {
    const target = note();
    await publishReaction(target);
    expect(drafts()).toEqual([
      {
        kind: 7,
        content: "+",
        tags: [
          ["e", target.id, ""],
          ["p", target.pubkey, ""],
        ],
      },
    ]);
    // + は「最近」に記録しない
    expect(loadRecentEmojis()).toEqual([]);
  });

  it("カスタム絵文字は emoji タグを 3 番目に付け、「最近」に記録する", async () => {
    const target = note();
    await publishReaction(target, ":cat:", "https://e/cat.png");
    expect(drafts()[0].tags[2]).toEqual(["emoji", "cat", "https://e/cat.png"]);
    expect(loadRecentEmojis()).toMatchObject([{ content: ":cat:", imageUrl: "https://e/cat.png", uses: 1 }]);
  });

  it("画像 URL が無ければ emoji タグを付けない", async () => {
    await publishReaction(note(), ":cat:", null);
    expect(drafts()[0].tags.some((t) => t[0] === "emoji")).toBe(false);
  });
});

describe("reactWithDefault", () => {
  it("自分の kind:7 が無ければ既定の内容で送る", async () => {
    setDefaultReaction("⭐", null);
    const target = note();
    await reactWithDefault(target);
    expect(drafts()).toMatchObject([{ kind: 7, content: "⭐" }]);
  });

  it("既定 + で、この投稿への自分の ❤️ があれば kind:5 で取り消す", async () => {
    const target = note();
    const reaction = mine(7, "❤️", [
      ["e", target.id],
      ["p", target.pubkey],
    ]);
    await reactWithDefault(target);
    expect(drafts()).toEqual([
      {
        kind: 5,
        content: "",
        tags: [
          ["e", reaction.id],
          ["k", "7"],
        ],
      },
    ]);
  });

  it("自分の kind:7 が 🔥 だけなら消さずに既定を送る", async () => {
    const target = note();
    mine(7, "🔥", [["e", target.id]]);
    await reactWithDefault(target);
    expect(drafts()).toMatchObject([{ kind: 7, content: "+" }]);
  });
});

describe("publishRepost（NIP-18）", () => {
  it("kind:1 は kind:6・content 空で e → p", async () => {
    const target = note(generateSecretKey(), { kind: 1 });
    await publishRepost(target);
    expect(drafts()).toEqual([
      {
        kind: 6,
        content: "",
        tags: [
          ["e", target.id, ""],
          ["p", target.pubkey, ""],
        ],
      },
    ]);
  });

  it("[#810] kind:1 以外（パブリックチャットの発言・コメント）は kind:16 で e → p → k（元の kind）", async () => {
    for (const kind of [42, 1111]) {
      vi.mocked(publishEvent).mockClear();
      const target = note(generateSecretKey(), { kind });
      await publishRepost(target);
      expect(drafts()).toEqual([
        {
          kind: 16,
          content: "",
          tags: [
            ["e", target.id, ""],
            ["p", target.pubkey, ""],
            ["k", String(kind)],
          ],
        },
      ]);
    }
  });

  it("[#810] 置き換え可能なイベント（記事 kind:30023）は a（kind:pubkey:d）も付ける", async () => {
    const target = note(generateSecretKey(), { kind: 30023, tags: [["d", "my-article"]] });
    await publishRepost(target);
    expect(drafts()).toEqual([
      {
        kind: 16,
        content: "",
        tags: [
          ["e", target.id, ""],
          ["p", target.pubkey, ""],
          ["a", `30023:${target.pubkey}:my-article`],
          ["k", "30023"],
        ],
      },
    ]);
  });
});

describe("requestDelete", () => {
  it("自分の kind:1 は e / k の kind:5 を送って true", async () => {
    const event = note(meKey);
    expect(await requestDelete(event)).toBe(true);
    expect(drafts()).toEqual([
      {
        kind: 5,
        content: "",
        tags: [
          ["e", event.id],
          ["k", "1"],
        ],
      },
    ]);
  });

  it("アドレス指定可能な kind には a タグも付ける", async () => {
    const event = note(meKey, { kind: 30023, tags: [["d", "x"]] });
    await requestDelete(event);
    expect(drafts()[0].tags).toEqual([
      ["e", event.id],
      ["k", "30023"],
      ["a", `30023:${me}:x`],
    ]);
  });

  it("他人の投稿は送らずに false", async () => {
    expect(await requestDelete(note())).toBe(false);
    expect(publishEvent).not.toHaveBeenCalled();
  });

  it("発行に失敗したら false", async () => {
    vi.mocked(publishEvent).mockRejectedValue(new PublishError("sign-failed"));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(await requestDelete(note(meKey))).toBe(false);
    warn.mockRestore();
  });
});

it("reportNote は kind:1984 で e に理由・p に作者", async () => {
  const event = note();
  await reportNote(event, "spam");
  expect(drafts()).toEqual([
    {
      kind: 1984,
      content: "",
      tags: [
        ["e", event.id, "spam"],
        ["p", event.pubkey],
      ],
    },
  ]);
});

it("reportUser（プロフィールの ⋯）は kind:1984 で p だけ、e タグは付けない", async () => {
  const target = getPublicKey(generateSecretKey());
  await reportUser(target, "spam");
  expect(drafts()).toEqual([{ kind: 1984, content: "", tags: [["p", target, "spam"]] }]);
});

describe("押下状態", () => {
  it("useIsReacted: 既定と同じ自分の kind:7 があれば true。既定を ⭐ にすると false", () => {
    const target = note();
    const { result } = renderHook(() => useIsReacted(target.id));
    expect(result.current).toBe(false);

    act(() => {
      mine(7, "+", [
        ["e", target.id],
        ["p", target.pubkey],
      ]);
    });
    expect(result.current).toBe(true);

    act(() => setDefaultReaction("⭐", null));
    expect(result.current).toBe(false);
  });

  it("useIsReposted: 自分の kind:6 の e がこの投稿なら true", () => {
    const target = note();
    const { result } = renderHook(() => useIsReposted(target.id));
    expect(result.current).toBe(false);
    act(() => {
      mine(6, "", [
        ["e", target.id],
        ["p", target.pubkey],
      ]);
    });
    expect(result.current).toBe(true);
  });

  it("[#810] useIsReposted: 自分の kind:16（汎用リポスト）の e がこの投稿でも true", () => {
    const target = note(generateSecretKey(), { kind: 42 });
    const { result } = renderHook(() => useIsReposted(target.id));
    expect(result.current).toBe(false);
    act(() => {
      mine(16, "", [
        ["e", target.id],
        ["p", target.pubkey],
        ["k", "42"],
      ]);
    });
    expect(result.current).toBe(true);
  });
});

describe("ensureMyReactionsSubscribed", () => {
  it("同じ me では 1 度だけ張り、別の me では前を解除して張り直す", () => {
    const teardowns: ReturnType<typeof vi.fn>[] = [];
    vi.mocked(subscribe).mockReset();
    vi.mocked(subscribe).mockImplementation(() => {
      const teardown = vi.fn();
      teardowns.push(teardown);
      return new Observable<"EOSE">(() => teardown);
    });

    const a = getPublicKey(generateSecretKey());
    const b = getPublicKey(generateSecretKey());
    ensureMyReactionsSubscribed(a);
    ensureMyReactionsSubscribed(a);
    expect(subscribe).toHaveBeenCalledTimes(1);
    expect(subscribe).toHaveBeenCalledWith({ kinds: [7], authors: [a], limit: 100 });

    ensureMyReactionsSubscribed(b);
    expect(subscribe).toHaveBeenCalledTimes(2);
    expect(subscribe).toHaveBeenLastCalledWith({ kinds: [7], authors: [b], limit: 100 });
    expect(teardowns[0]).toHaveBeenCalledTimes(1);
    expect(teardowns[1]).not.toHaveBeenCalled();
  });
});
