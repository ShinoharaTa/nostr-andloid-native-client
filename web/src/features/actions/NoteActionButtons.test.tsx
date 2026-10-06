import { createHash } from "node:crypto";
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { NEVER } from "rxjs";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { requestZapInvoice } from "../../lib/lnurl";
import { unixNow } from "../../lib/time";
import { type EventDraft, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { renderWithRouter } from "../../test/renderWithRouter";
import { useToast } from "../../ui/toast";
import { useCompose } from "../compose/composeStore";
import { appendToEmojiList, EmojiListError } from "../compose/customEmojis";
import { NoteFooter } from "../compose/NoteFooter";
import { toggleBookmark, togglePinned, useOwnLists } from "../lists/ownLists";
import { EMPTY_MUTE_LIST, setMuteList } from "../mute/muteList";
import { MuteListError, muteUser, unmuteUser } from "../mute/muteSync";
import { toggleFollow } from "../profile/follow";
import { setDeveloperMode } from "../settings/devMode";
import { NoteActionButtons, NoteMoreMenu, REACTION_PENDING_MS } from "./NoteActionButtons";
import styles from "./NoteActionButtons.module.css";
import { setDefaultReaction, useDefaultReaction } from "./reactionPrefs";

// 署名・送信はしない（publishEvent だけ差し替えて draft を見る）
vi.mock("../../nostr/publish", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/publish")>();
  return { ...actual, publishEvent: vi.fn() };
});

// 自分の kind:7 の購読はリレーへ張らない
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  return { ...actual, subscribe: vi.fn(() => NEVER) };
});

// ミュート / 解除は muteSync の関数を呼ぶところまで（取り直し・発行は muteSync.test.ts）
vi.mock("../mute/muteSync", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../mute/muteSync")>();
  return {
    ...actual,
    muteUser: vi.fn(async () => "done" as const),
    unmuteUser: vi.fn(async () => "done" as const),
  };
});

// Zap の invoice は取りに行かない（呼ばれ方だけ見る。LNURL は lnurl.test.ts）
vi.mock("../../lib/lnurl", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/lnurl")>();
  return { ...actual, requestZapInvoice: vi.fn(async () => null) };
});

// フォロー / 解除は #457 の関数を呼ぶところまで
vi.mock("../profile/follow", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../profile/follow")>();
  return { ...actual, toggleFollow: vi.fn(async () => "done" as const) };
});

// [#768] 作った絵文字の保存は appendToEmojiList を呼ぶところまで（取り直し・発行は customEmojis.test.ts）
vi.mock("../compose/customEmojis", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../compose/customEmojis")>();
  return { ...actual, appendToEmojiList: vi.fn(async () => {}) };
});

// ブックマーク・固定は ownLists の関数を呼ぶところまで（取り直し・発行は ownLists.test.ts）
vi.mock("../lists/ownLists", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lists/ownLists")>();
  return {
    ...actual,
    toggleBookmark: vi.fn(async () => "done" as const),
    togglePinned: vi.fn(async () => "done" as const),
  };
});

let meKey: Uint8Array;
let me: string;

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  meKey = generateSecretKey();
  me = getPublicKey(meKey);
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  vi.mocked(publishEvent).mockReset();
  vi.mocked(publishEvent).mockImplementation(async (draft: EventDraft) =>
    finalizeEvent(
      { kind: draft.kind, content: draft.content, tags: draft.tags, created_at: unixNow() },
      meKey,
    ),
  );
  vi.mocked(toggleFollow).mockClear();
  vi.mocked(toggleBookmark).mockClear();
  vi.mocked(togglePinned).mockClear();
  useOwnLists.setState({ bookmarks: null, pinned: null });
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  Reflect.deleteProperty(navigator, "clipboard");
  useDefaultReaction.setState({ content: "+", image: null });
  useCompose.setState({ request: null });
  useToast.setState({ queue: [] });
  useSession.setState({ status: "loading", method: null, pubkey: null });
  setDeveloperMode(false);
});

function post(key = generateSecretKey(), tags: string[][] = []): NostrEvent {
  return finalizeEvent({ kind: 1, created_at: unixNow(), tags, content: "本文です" }, key);
}

/** 自分のイベントをストアに入れる */
function addMine(kind: number, content: string, tags: string[][]): NostrEvent {
  const event = finalizeEvent({ kind, created_at: unixNow(), tags, content }, meKey);
  act(() => {
    eventStore.add(event);
  });
  return event;
}

function renderRow(event: NostrEvent) {
  return renderWithRouter(
    <NoteFooter event={event} more={<NoteMoreMenu event={event} />}>
      <NoteActionButtons event={event} />
    </NoteFooter>,
  );
}

function lastDraft(): EventDraft {
  const calls = vi.mocked(publishEvent).mock.calls;
  return calls[calls.length - 1][0];
}

function button(name: string): HTMLElement {
  return screen.getByRole("button", { name });
}

it("「操作」の並びは 返信・リポスト・リアクション・絵文字でリアクション・その他の操作", () => {
  renderRow(post());
  const group = screen.getByRole("group", { name: "操作" });
  expect(
    within(group)
      .getAllByRole("button")
      .map((b) => b.getAttribute("aria-label")),
  ).toEqual(["返信", "リポスト", "リアクション", "絵文字でリアクション", "その他の操作"]);
});

describe("リポスト", () => {
  it("「リポスト」で kind:6、「引用リポスト」で引用の投稿シート", async () => {
    const user = userEvent.setup();
    const event = post();
    renderRow(event);

    await user.click(button("リポスト"));
    expect(screen.getAllByRole("menuitem").map((m) => m.textContent)).toEqual(["リポスト", "引用リポスト"]);
    await user.click(screen.getByRole("menuitem", { name: "リポスト" }));
    expect(lastDraft()).toMatchObject({ kind: 6, content: "" });

    await user.click(button("リポスト"));
    await user.click(screen.getByRole("menuitem", { name: "引用リポスト" }));
    expect(useCompose.getState().request).toEqual({ mode: "quote", target: event });
  });

  it("自分の kind:6 があればトリガが緑（reposted）", () => {
    const event = post();
    renderRow(event);
    expect(button("リポスト")).not.toHaveClass(styles.reposted);
    addMine(6, "", [
      ["e", event.id],
      ["p", event.pubkey],
    ]);
    expect(button("リポスト")).toHaveClass(styles.reposted);
  });
});

describe("既定リアクション", () => {
  it("押すと kind:7 を送って署名待ち、自分の kind:7 が来たら押下で確定", async () => {
    const user = userEvent.setup();
    const event = post();
    renderRow(event);

    await user.click(button("リアクション"));
    expect(lastDraft()).toMatchObject({ kind: 7, content: "+" });
    expect(button("リアクション")).toHaveAttribute("aria-busy", "true");
    expect(button("リアクション")).toHaveAttribute("aria-pressed", "true");

    addMine(7, "+", [
      ["e", event.id],
      ["p", event.pubkey],
    ]);
    expect(button("リアクション")).toHaveAttribute("aria-pressed", "true");
    expect(button("リアクション")).not.toHaveAttribute("aria-busy", "true");
  });

  it("押下済みを押すと確認し、「取り消す」で kind:5", async () => {
    const user = userEvent.setup();
    const event = post();
    const reaction = addMine(7, "+", [["e", event.id]]);
    renderRow(event);
    expect(button("リアクション")).toHaveAttribute("aria-pressed", "true");

    await user.click(button("リアクション"));
    const dialog = screen.getByRole("dialog", { name: "リアクションを取り消しますか？" });
    expect(publishEvent).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "取り消す" }));
    expect(lastDraft()).toEqual({
      kind: 5,
      content: "",
      tags: [
        ["e", reaction.id],
        ["k", "7"],
      ],
    });
  });

  it("自分の kind:7 が来ないまま 6 秒で押下を戻す", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    renderRow(post());
    fireEvent.click(button("リアクション"));
    expect(button("リアクション")).toHaveAttribute("aria-pressed", "true");
    act(() => {
      vi.advanceTimersByTime(REACTION_PENDING_MS);
    });
    expect(button("リアクション")).toHaveAttribute("aria-pressed", "false");
  });

  it("既定が ⭐ なら ☆、+ なら ♡", () => {
    const event = post();
    const { unmount } = renderRow(event);
    expect(button("リアクション").querySelector("[data-shape]")).toHaveAttribute("data-shape", "heart");
    unmount();
    setDefaultReaction("⭐", null);
    renderRow(event);
    expect(button("リアクション").querySelector("[data-shape]")).toHaveAttribute("data-shape", "star");
  });
});

it("「絵文字でリアクション」→ ピッカーで 😄 → kind:7", async () => {
  const user = userEvent.setup();
  renderRow(post());
  await user.click(button("絵文字でリアクション"));
  const dialog = screen.getByRole("dialog", { name: "リアクション" });
  await user.click(within(dialog).getByRole("button", { name: "😄" }));
  expect(lastDraft()).toMatchObject({ kind: 7, content: "😄" });
  expect(screen.queryByRole("dialog", { name: "リアクション" })).toBeNull();
});

describe("ピッカーからのリアクションのトースト", () => {
  it("Unicode 絵文字を選ぶと「😄 でリアクションしました」", async () => {
    const user = userEvent.setup();
    renderRow(post());
    await user.click(button("絵文字でリアクション"));
    await user.click(
      within(screen.getByRole("dialog", { name: "リアクション" })).getByRole("button", { name: "😄" }),
    );
    await waitFor(() => expect(useToast.getState().queue).toEqual(["😄 でリアクションしました"]));
  });

  it("カスタム絵文字は :name: で出る", async () => {
    const user = userEvent.setup();
    act(() => {
      eventStore.add(
        finalizeEvent(
          {
            kind: 10030,
            created_at: unixNow(),
            tags: [["emoji", "blobcat", "https://example.com/b.png"]],
            content: "",
          },
          meKey,
        ),
      );
    });
    renderRow(post());
    await user.click(button("絵文字でリアクション"));
    await user.click(
      within(screen.getByRole("dialog", { name: "リアクション" })).getByRole("button", { name: ":blobcat:" }),
    );
    await waitFor(() => expect(useToast.getState().queue).toEqual([":blobcat: でリアクションしました"]));
  });

  it("♡ では出ない", async () => {
    const user = userEvent.setup();
    renderRow(post());
    await user.click(button("リアクション"));
    await waitFor(() => expect(publishEvent).toHaveBeenCalled());
    expect(useToast.getState().queue).toEqual([]);
  });

  it("送信に失敗したら出ない", async () => {
    const user = userEvent.setup();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.mocked(publishEvent).mockRejectedValue(new Error("fail"));
    renderRow(post());
    await user.click(button("絵文字でリアクション"));
    await user.click(
      within(screen.getByRole("dialog", { name: "リアクション" })).getByRole("button", { name: "😄" }),
    );
    await waitFor(() => expect(publishEvent).toHaveBeenCalled());
    expect(useToast.getState().queue).toEqual([]);
  });
});

describe("ピッカーで作った絵文字でリアクション（#768）", () => {
  const fetchMock = vi.fn<typeof fetch>();
  const kusaUrl = () => `${window.location.origin}/api/emoji.png?text=%E8%8D%89&stroke=ffffff`;
  const autoName = () => `nostrism_${createHash("sha256").update(kusaUrl()).digest("hex").slice(0, 8)}`;

  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockImplementation(() =>
      Promise.resolve(new Response(new Blob([new Uint8Array([0x89, 0x50])], { type: "image/png" }))),
    );
    vi.stubGlobal("fetch", fetchMock);
    // jsdom に objectURL は無い
    Object.assign(URL, { createObjectURL: vi.fn(() => "blob:emoji"), revokeObjectURL: vi.fn() });
    vi.mocked(appendToEmojiList).mockReset();
    vi.mocked(appendToEmojiList).mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** ピッカー →「絵文字を作る」→「草」→（名前・保存）→「この絵文字でリアクション」 */
  async function reactWithMade({ name = "", save = false }: { name?: string; save?: boolean } = {}) {
    const user = userEvent.setup();
    await user.click(button("絵文字でリアクション"));
    const dialog = screen.getByRole("dialog", { name: "リアクション" });
    await user.click(within(dialog).getByRole("button", { name: "絵文字を作る" }));
    fireEvent.change(within(dialog).getByRole("textbox", { name: "テキスト" }), { target: { value: "草" } });
    if (name !== "")
      fireEvent.change(within(dialog).getByRole("textbox", { name: "ショートコード（任意）" }), {
        target: { value: name },
      });
    if (save) await user.click(within(dialog).getByRole("checkbox", { name: "自分の絵文字リストにも保存" }));
    const send = within(dialog).getByRole("button", { name: "この絵文字でリアクション" });
    await waitFor(() => expect(send).toBeEnabled());
    await user.click(send);
  }

  it("自動の名前は「作った絵文字でリアクションしました」。:code: + emoji タグで送り、「最近」に入る。保存はしない", async () => {
    renderRow(post());
    await reactWithMade();
    await waitFor(() => expect(useToast.getState().queue).toEqual(["作った絵文字でリアクションしました"]));
    expect(lastDraft()).toMatchObject({ kind: 7, content: `:${autoName()}:` });
    expect(lastDraft().tags).toContainEqual(["emoji", autoName(), kusaUrl()]);
    expect(appendToEmojiList).not.toHaveBeenCalled();

    // 次に開いたピッカーの「最近」に出る
    await userEvent.click(button("絵文字でリアクション"));
    const recent = within(screen.getByRole("region", { name: "最近" }));
    expect(recent.getByRole("button", { name: `:${autoName()}:` })).toBeVisible();
  });

  it("入力した名前は従来どおり「:name: でリアクションしました」", async () => {
    renderRow(post());
    await reactWithMade({ name: "kusa" });
    await waitFor(() => expect(useToast.getState().queue).toEqual([":kusa: でリアクションしました"]));
    expect(lastDraft()).toMatchObject({ kind: 7, content: ":kusa:" });
  });

  it("保存がオンなら送信の後に自分の絵文字リストへ足し、別のトーストで知らせる", async () => {
    renderRow(post());
    await reactWithMade({ name: "kusa", save: true });
    await waitFor(() =>
      expect(useToast.getState().queue).toEqual([
        ":kusa: でリアクションしました",
        "絵文字リストを公開しました。",
      ]),
    );
    expect(appendToEmojiList).toHaveBeenCalledWith(me, { shortcode: "kusa", url: kusaUrl() });
  });

  it("同じショートコードが既にあれば、リアクションは送ったうえで保存しなかったと知らせる", async () => {
    vi.mocked(appendToEmojiList).mockRejectedValue(new EmojiListError("duplicate"));
    renderRow(post());
    await reactWithMade({ save: true });
    await waitFor(() =>
      expect(useToast.getState().queue).toEqual([
        "作った絵文字でリアクションしました",
        "同じショートコードの絵文字がリストにあるため、保存しませんでした",
      ]),
    );
    expect(lastDraft()).toMatchObject({ kind: 7, content: `:${autoName()}:` });
  });

  it("保存の失敗はリアクションとは別に知らせる", async () => {
    vi.mocked(appendToEmojiList).mockRejectedValue(new EmojiListError("sign-failed"));
    renderRow(post());
    await reactWithMade({ save: true });
    await waitFor(() =>
      expect(useToast.getState().queue).toEqual([
        "作った絵文字でリアクションしました",
        "絵文字リストを公開できませんでした。",
      ]),
    );
  });
});

describe("⚡ Zap", () => {
  /** 作者の kind:0（lud16 は任意）をストアに入れる */
  function addProfile(key: Uint8Array, lud16?: string) {
    const content = JSON.stringify(lud16 ? { name: "作者", lud16 } : { name: "作者" });
    act(() => {
      eventStore.add(finalizeEvent({ kind: 0, created_at: unixNow(), tags: [], content }, key));
    });
  }

  /** target への Zap 受領（金額は bolt11） */
  function addZap(target: NostrEvent, bolt11: string) {
    act(() => {
      eventStore.add(
        finalizeEvent(
          {
            kind: 9735,
            created_at: unixNow(),
            tags: [
              ["e", target.id],
              ["p", target.pubkey],
              ["bolt11", bolt11],
            ],
            content: "",
          },
          generateSecretKey(),
        ),
      );
    });
  }

  it("作者に lud16 が無く受領も 0 なら ⚡ を出さない", () => {
    const key = generateSecretKey();
    addProfile(key);
    renderRow(post(key));
    expect(screen.queryByRole("img", { name: /Zap/ })).toBeNull();
  });

  it("lud16 があり受領 0 なら灰色の ⚡ ボタン（金額なし）", () => {
    const key = generateSecretKey();
    addProfile(key, "alice@getalby.com");
    renderRow(post(key));
    const zap = button("Zap");
    expect(zap).toHaveClass(styles.zap);
    expect(zap).not.toHaveClass(styles.zapped);
    expect(zap).toHaveTextContent(/^$/);
    expect(screen.queryByRole("img", { name: /Zap/ })).toBeNull();
  });

  it("受領があれば lud16 が無くても ⚡ と合計（formatSats）を --zap の色で出す（押せない）", () => {
    const key = generateSecretKey();
    addProfile(key);
    const event = post(key);
    renderRow(event);
    addZap(event, "lnbc10u1pxxxxxx");
    addZap(event, "lnbc2340n1pxxxxxx");
    const zap = screen.getByRole("img", { name: "Zap 1.2k sats" });
    expect(zap).toHaveClass(styles.zapped);
    expect(zap).toHaveTextContent("1.2k");
    expect(screen.queryByRole("button", { name: /Zap/ })).toBeNull();
  });

  it("⚡ を押すと Zap ダイアログ（投稿への Zap: 作者・lud16・e / k）", async () => {
    const user = userEvent.setup();
    const key = generateSecretKey();
    addProfile(key, " alice@getalby.com ");
    const event = post(key);
    renderRow(event);
    addZap(event, "lnbc210n1pxxxxxx");

    await user.click(button("Zap 21 sats"));
    const dialog = screen.getByRole("dialog", { name: "⚡ Zap" });
    expect(within(dialog).getByText("送信先: alice@getalby.com")).toBeInTheDocument();
    expect(within(dialog).getByText(/^作者 へ投げ銭します/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));
    await waitFor(() =>
      expect(requestZapInvoice).toHaveBeenCalledWith({
        recipient: event.pubkey,
        lud16: "alice@getalby.com",
        amountSats: 100,
        comment: "",
        eventId: event.id,
        targetKind: 1,
      }),
    );
    await user.click(within(dialog).getByRole("button", { name: "キャンセル" }));
    expect(screen.queryByRole("dialog", { name: "⚡ Zap" })).toBeNull();
  });
});

describe("⋯ メニュー", () => {
  it("他人の投稿: client の見出し。自分の kind:3 が無ければフォロー項目なし、あれば「フォロー解除」→ 確認", async () => {
    const user = userEvent.setup();
    const event = post(generateSecretKey(), [["client", "Nostrism"]]);
    renderRow(event);

    await user.click(button("その他の操作"));
    expect(screen.getByText("Nostrism から投稿")).toBeVisible();
    expect(screen.queryByRole("menuitem", { name: /フォロー/ })).toBeNull();
    await user.keyboard("{Escape}");

    addMine(3, "", [["p", event.pubkey]]);
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "フォロー解除" }));
    const dialog = screen.getByRole("dialog", { name: "フォローを解除しますか？" });
    await user.click(within(dialog).getByRole("button", { name: "解除する" }));
    expect(toggleFollow).toHaveBeenCalledWith(me, event.pubkey, "unfollow");
  });

  it("未フォローの「フォロー」は確認なしで #457 のフォローを呼ぶ", async () => {
    const user = userEvent.setup();
    const event = post();
    addMine(3, "", []);
    renderRow(event);
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "フォロー" }));
    expect(toggleFollow).toHaveBeenCalledWith(me, event.pubkey, "follow");
  });

  it("「このユーザーをミュート」→ 確認 →「ミュート」で muteUser とトースト。ミュート中は「ミュートを解除」", async () => {
    const user = userEvent.setup();
    const event = post();
    renderRow(event);
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "このユーザーをミュート" }));
    const dialog = screen.getByRole("dialog", { name: "このユーザーをミュートしますか？" });
    await user.click(within(dialog).getByRole("button", { name: "ミュート" }));
    expect(muteUser).toHaveBeenCalledWith(me, event.pubkey);
    await waitFor(() => expect(useToast.getState().queue).toEqual(["ミュートしました"]));

    act(() =>
      setMuteList({
        ...EMPTY_MUTE_LIST,
        entries: [{ category: "p", value: event.pubkey, isPublic: false, isPrivate: true }],
      }),
    );
    vi.mocked(unmuteUser).mockRejectedValueOnce(new MuteListError("locked"));
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "ミュートを解除" }));
    expect(unmuteUser).toHaveBeenCalledWith(me, event.pubkey);
    await waitFor(() =>
      expect(useToast.getState().queue).toEqual([
        "ミュートしました",
        "ミュートリストが変更できません（ロック中の可能性）",
      ]),
    );
    act(() => setMuteList(null));
  });

  it("「ブックマーク」→「ブックマークを解除」（#531。自分の kind:10003 が未取得の間は項目を出さない）", async () => {
    const user = userEvent.setup();
    const event = post();
    renderRow(event);
    await user.click(button("その他の操作"));
    expect(screen.queryByRole("menuitem", { name: /ブックマーク/ })).toBeNull();
    await user.keyboard("{Escape}");

    act(() =>
      useOwnLists.setState({
        bookmarks: { eventId: null, createdAt: 0, ids: [], otherTags: [], content: "" },
        pinned: null,
      }),
    );
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "ブックマーク" }));
    expect(toggleBookmark).toHaveBeenCalledWith(me, event.id, "bookmark");
    await waitFor(() => expect(useToast.getState().queue).toEqual(["ブックマークしました"]));

    act(() =>
      useOwnLists.setState({
        bookmarks: { eventId: "e1", createdAt: 1, ids: [event.id], otherTags: [], content: "" },
        pinned: null,
      }),
    );
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "ブックマークを解除" }));
    expect(toggleBookmark).toHaveBeenCalledWith(me, event.id, "unbookmark");
    await waitFor(() =>
      expect(useToast.getState().queue).toEqual(["ブックマークしました", "ブックマークを解除しました"]),
    );
  });

  it("自分の投稿だけ「プロフィールに固定」→「固定を解除」", async () => {
    const user = userEvent.setup();
    const event = post(meKey);
    act(() =>
      useOwnLists.setState({
        bookmarks: null,
        pinned: { eventId: null, createdAt: 0, ids: [], otherTags: [], content: "" },
      }),
    );
    renderRow(event);
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "プロフィールに固定" }));
    expect(togglePinned).toHaveBeenCalledWith(me, event.id, "pin");
    await waitFor(() => expect(useToast.getState().queue).toEqual(["プロフィールに固定しました"]));

    act(() =>
      useOwnLists.setState({
        bookmarks: null,
        pinned: { eventId: "e1", createdAt: 1, ids: [event.id], otherTags: [], content: "" },
      }),
    );
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "プロフィールの固定を解除" }));
    expect(togglePinned).toHaveBeenCalledWith(me, event.id, "unpin");
    await waitFor(() =>
      expect(useToast.getState().queue).toEqual(["プロフィールに固定しました", "固定を解除しました"]),
    );
  });

  it("「通報」→ 理由「スパム」で kind:1984", async () => {
    const user = userEvent.setup();
    const event = post();
    renderRow(event);
    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "通報" }));
    const dialog = screen.getByRole("dialog", { name: "この投稿を通報" });
    await user.click(within(dialog).getByRole("button", { name: "スパム" }));
    expect(lastDraft()).toEqual({
      kind: 1984,
      content: "",
      tags: [
        ["e", event.id, "spam"],
        ["p", event.pubkey],
      ],
    });
    expect(screen.queryByRole("dialog", { name: "この投稿を通報" })).toBeNull();
  });

  it("自分の投稿: 「削除をリクエスト」→ 確認 →「リクエストする」で kind:5 とトースト", async () => {
    const user = userEvent.setup();
    const event = post(meKey);
    renderRow(event);
    await user.click(button("その他の操作"));
    expect(screen.queryByRole("menuitem", { name: "通報" })).toBeNull();
    await user.click(screen.getByRole("menuitem", { name: "削除をリクエスト" }));
    const dialog = screen.getByRole("dialog", { name: "この投稿の削除をリクエストしますか？" });
    await user.click(within(dialog).getByRole("button", { name: "リクエストする" }));
    expect(lastDraft()).toEqual({
      kind: 5,
      content: "",
      tags: [
        ["e", event.id],
        ["k", "1"],
      ],
    });
    await waitFor(() => expect(useToast.getState().queue).toEqual(["削除をリクエストしました"]));
  });

  it("「リンクをコピー（njump）」は njump の URL を書いてトースト。失敗は「コピーできませんでした」", async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error("denied"));
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    renderRow(post());

    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "リンクをコピー（njump）" }));
    expect(writeText).toHaveBeenCalledWith(expect.stringMatching(/^https:\/\/njump\.me\/nevent1/));
    await waitFor(() => expect(useToast.getState().queue).toEqual(["コピーしました"]));

    await user.click(button("その他の操作"));
    await user.click(screen.getByRole("menuitem", { name: "リンクをコピー（njump）" }));
    await waitFor(() =>
      expect(useToast.getState().queue).toEqual(["コピーしました", "コピーできませんでした"]),
    );
  });

  it("開発者モードが OFF なら「イベントJSONを表示」は無い", async () => {
    const user = userEvent.setup();
    renderRow(post());
    await user.click(button("その他の操作"));
    expect(screen.getByRole("menuitem", { name: "テキストをコピー" })).toBeVisible();
    expect(screen.queryByRole("menuitem", { name: "イベントJSONを表示" })).toBeNull();
  });

  it("開発者モードが ON なら末尾の「イベントJSONを表示」で整形した JSON を出し、コピーでトースト", async () => {
    const user = userEvent.setup();
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    setDeveloperMode(true);
    const event = post();
    renderRow(event);

    await user.click(button("その他の操作"));
    const items = screen.getAllByRole("menuitem");
    expect(items.at(-1)).toHaveTextContent("イベントJSONを表示");
    await user.click(screen.getByRole("menuitem", { name: "イベントJSONを表示" }));

    const dialog = screen.getByRole("dialog", { name: "イベントJSON" });
    const expected = JSON.stringify(
      {
        id: event.id,
        pubkey: event.pubkey,
        created_at: event.created_at,
        kind: 1,
        tags: [],
        content: "本文です",
        sig: event.sig,
      },
      null,
      2,
    );
    expect(dialog.querySelector("pre")?.textContent).toBe(expected);
    expect(within(dialog).getByText("kind:1")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "テキストをコピー" }));
    expect(writeText).toHaveBeenCalledWith(expected);
    await waitFor(() => expect(useToast.getState().queue).toEqual(["JSONをコピーしました"]));

    await user.click(within(dialog).getByRole("button", { name: "閉じる" }));
    expect(screen.queryByRole("dialog", { name: "イベントJSON" })).toBeNull();
  });
});
