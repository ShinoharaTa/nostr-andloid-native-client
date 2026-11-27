import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { neventEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { createMemoryRouter, RouterProvider } from "react-router";
import { VirtuosoMockContext } from "react-virtuoso";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { DeckScreen } from "../../app/deck/DeckScreen";
import { type ColumnSpec, DEFAULT_COLUMNS } from "../../lib/columns";
import { unixNow } from "../../lib/time";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { installDialogPolyfill } from "../../test/dialog";
import { OTHER_PUBKEY } from "../../test/fakeNostr";
import { clearViewport, mockViewport } from "../../test/viewport";
import { reactWithDefault } from "../actions/reactions";
import { useCompose } from "../compose/composeStore";
import type { FeedRow } from "../deck/followingMix";
import type { ColumnFeed } from "../deck/useColumnFeed";
import { toggleBookmark, useOwnLists } from "../lists/ownLists";
import { type NotificationItem, toNotification } from "../notifications/notificationModel";
import { KeyboardShortcuts } from "./KeyboardShortcuts";
import { INITIAL_KEYBOARD_STATE, useKeyboard } from "./kbStore";

const KEY = generateSecretKey();
const NOW = unixNow();

function posts(prefix: string, count: number): NostrEvent[] {
  return Array.from({ length: count }, (_, i) =>
    finalizeEvent({ kind: 1, created_at: NOW - i * 60, tags: [], content: `${prefix} ${i}` }, KEY),
  );
}

// カラムごとの中身（描画のたびに同じ配列・同じ結果を返す）
const { FEEDS, EMPTY_FEED } = vi.hoisted(() => {
  const empty: ColumnFeed = {
    mode: "column",
    loading: false,
    events: [],
    rows: null,
    loadingOlder: false,
    loadOlder: () => {},
    refresh: () => {},
  };
  return { FEEDS: new Map<string, ColumnFeed>(), EMPTY_FEED: empty };
});
const FOLLOWING = posts("フォロー", 5);
const HASHTAG = posts("タグ", 3);
FEEDS.set("c_following", { ...EMPTY_FEED, mode: "following", events: FOLLOWING });
FEEDS.set("c_hashtag", { ...EMPTY_FEED, events: HASHTAG });

// 購読はしない
vi.mock("../deck/useColumnFeed", () => ({
  useColumnFeed: (spec: ColumnSpec) => FEEDS.get(spec.id) ?? EMPTY_FEED,
}));

// 発行しない
vi.mock("../actions/reactions", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../actions/reactions")>()),
  reactWithDefault: vi.fn(() => Promise.resolve()),
}));

// 発行しない（購読は startOwnLists を呼ばないのでしない）
vi.mock("../lists/ownLists", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../lists/ownLists")>()),
  toggleBookmark: vi.fn(() => Promise.resolve("done")),
}));

beforeAll(() => {
  installDialogPolyfill();
  // jsdom に ResizeObserver が無い（Virtuoso が使う。寸法は VirtuosoMockContext が与える）
  if (typeof globalThis.ResizeObserver !== "function") {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

beforeEach(() => {
  localStorage.clear();
  useDeck.setState({
    columns: structuredClone([...DEFAULT_COLUMNS]),
    widths: {},
    jumpTarget: null,
    visibleColumnId: null,
    editingColumnId: null,
    showAddColumn: false,
  });
  useKeyboard.setState({ ...INITIAL_KEYBOARD_STATE });
  useCompose.setState({ request: null });
  useOwnLists.setState({ bookmarks: null, pinned: null });
  vi.mocked(reactWithDefault).mockClear();
  vi.mocked(toggleBookmark).mockClear();
  mockViewport(1200);
});

afterEach(() => {
  clearViewport();
  useSession.setState({ status: "out", method: null, pubkey: null });
});

/** デッキ + ショートカットを / に描き、/e/:ref と /search へ移れるルータ */
function renderDeck() {
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: (
          <VirtuosoMockContext.Provider value={{ viewportHeight: 1000, itemHeight: 100 }}>
            <DeckScreen />
            <KeyboardShortcuts enabled hasDetail={false} />
          </VirtuosoMockContext.Provider>
        ),
      },
      { path: "/e/:ref", element: <p>スレッド</p> },
      { path: "/search", element: <input id="search-query" type="search" aria-label="検索語" /> },
    ],
    { initialEntries: ["/"] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

/** body で押す。既定の動作を止めたら true */
function press(key: string, init: KeyboardEventInit = {}): boolean {
  return !fireEvent.keyDown(document.body, { key, ...init });
}

/** カラム（id）で選択中の行の index（無ければ null） */
function selectedRow(columnId: string): number | null {
  const column = document.getElementById(`deck-col-${columnId}`);
  if (!column) throw new Error(`カラム ${columnId} が無い`);
  const rows = column.querySelectorAll<HTMLElement>("[data-kb-selected]");
  if (rows.length > 1) throw new Error("選択中の行が複数ある");
  return rows.length === 0 ? null : Number(rows[0].dataset.kbIndex);
}

describe("選択と移動", () => {
  it("j で選択が 1 進みハイライトが付く。k で戻り、範囲の外へは出ない", () => {
    renderDeck();
    expect(selectedRow("c_following")).toBeNull();

    expect(press("j")).toBe(true);
    expect(selectedRow("c_following")).toBe(0);
    press("j");
    expect(selectedRow("c_following")).toBe(1);
    press("ArrowDown");
    expect(selectedRow("c_following")).toBe(2);
    press("k");
    press("ArrowUp");
    press("k");
    expect(selectedRow("c_following")).toBe(0);

    press("G", { shiftKey: true });
    expect(selectedRow("c_following")).toBe(4);
    press("j");
    expect(selectedRow("c_following")).toBe(4);
    press("g");
    expect(selectedRow("c_following")).toBe(0);
  });

  it("l で次のカラムへ（選択が無ければ 0、そのカラムへスクロール）、h で戻ると前の選択のまま", () => {
    renderDeck();
    press("j");
    press("j");
    press("l");
    expect(useKeyboard.getState().focusColumnId).toBe("c_hashtag");
    expect(selectedRow("c_hashtag")).toBe(0);
    expect(selectedRow("c_following")).toBeNull();
    // jump はデッキが消費する
    expect(useDeck.getState().jumpTarget).toBeNull();

    press("j");
    press("h");
    expect(selectedRow("c_following")).toBe(1);
    expect(selectedRow("c_hashtag")).toBeNull();
    // 左端より左へは行かない
    press("ArrowLeft");
    expect(useKeyboard.getState().focusColumnId).toBe("c_following");
  });

  it("Esc で選択を解除する（次の j は前の位置から）", () => {
    renderDeck();
    press("j");
    press("j");
    expect(press("Escape")).toBe(true);
    expect(selectedRow("c_following")).toBeNull();
    // 閉じるものが無ければ止めない
    expect(press("Escape")).toBe(false);
    press("j");
    expect(selectedRow("c_following")).toBe(2);
  });

  it("カラムを消したら選択も消す", () => {
    renderDeck();
    press("l");
    press("j");
    act(() => useDeck.getState().removeColumn("c_hashtag"));
    expect(useKeyboard.getState().selected).not.toHaveProperty("c_hashtag");
    expect(useKeyboard.getState().focusColumnId).toBeNull();
  });

  it("入力欄にフォーカスがあるとき・IME 変換中・修飾キー付きは何もしない", () => {
    renderDeck();
    const input = document.createElement("input");
    document.body.append(input);
    expect(fireEvent.keyDown(input, { key: "j" })).toBe(true);
    input.remove();
    expect(press("j", { isComposing: true })).toBe(false);
    expect(press("r", { ctrlKey: true })).toBe(false);
    expect(selectedRow("c_following")).toBeNull();
  });
});

/** フォロー中カラムの混在行: 投稿 → 自分へのリアクション → 自分のリアクション → 未読 DM → 投稿 */
function mixedFeed(): { feed: ColumnFeed; mix: NostrEvent[] } {
  const mix = posts("混在", 2);
  const reaction = finalizeEvent(
    {
      kind: 7,
      created_at: NOW - 10,
      tags: [
        ["e", mix[0].id],
        ["p", getPublicKey(KEY)],
      ],
      content: "+",
    },
    generateSecretKey(),
  );
  const notice = toNotification(reaction);
  if (!notice) throw new Error("通知にならない");
  const myReaction = finalizeEvent(
    { kind: 7, created_at: NOW - 20, tags: [["e", "a".repeat(64)]], content: "+" },
    KEY,
  );
  const dm: NotificationItem = {
    id: `dm_${OTHER_PUBKEY}`,
    kind: "dm",
    event: null,
    actor: OTHER_PUBKEY,
    createdAt: NOW - 30,
    target: null,
    reaction: null,
    zapSats: null,
    dmUnread: 1,
    targetIsProfile: false,
    yo: null,
  };
  const rows: FeedRow[] = [
    { type: "post", id: mix[0].id, at: mix[0].created_at, event: mix[0] },
    { type: "notice", id: notice.id, at: notice.createdAt, item: notice },
    { type: "myReaction", id: myReaction.id, at: myReaction.created_at, reaction: myReaction },
    { type: "notice", id: dm.id, at: dm.createdAt, item: dm },
    { type: "post", id: mix[1].id, at: mix[1].created_at, event: mix[1] },
  ];
  return { feed: { ...EMPTY_FEED, mode: "following", events: mix, rows }, mix };
}

describe("フォロー中カラムの混在行", () => {
  it("j / k は投稿の行だけを選び、通知・リアクション・DM の行は飛ばす。Enter はその投稿へ", () => {
    const { feed, mix } = mixedFeed();
    const original = FEEDS.get("c_following");
    FEEDS.set("c_following", feed);
    try {
      const router = renderDeck();
      press("j");
      expect(selectedRow("c_following")).toBe(0);
      press("j");
      expect(selectedRow("c_following")).toBe(4);
      // 末尾の投稿より先へは行かない
      press("j");
      expect(selectedRow("c_following")).toBe(4);
      press("k");
      expect(selectedRow("c_following")).toBe(0);
      press("G", { shiftKey: true });
      expect(selectedRow("c_following")).toBe(4);

      press("r");
      expect(useCompose.getState().request).toEqual({ mode: "reply", target: mix[1] });
      act(() => useCompose.setState({ request: null }));

      press("Enter");
      expect(router.state.location.pathname).toBe(
        `/e/${neventEncode({ id: mix[1].id, author: mix[1].pubkey })}`,
      );
    } finally {
      if (original) FEEDS.set("c_following", original);
    }
  });
});

describe("選択中の投稿への操作", () => {
  it("Enter でスレッドの URL へ", () => {
    const router = renderDeck();
    press("j");
    press("j");
    press("Enter");
    const target = FOLLOWING[1];
    expect(router.state.location.pathname).toBe(
      `/e/${neventEncode({ id: target.id, author: target.pubkey })}`,
    );
  });

  it("o でも開く。選択が無ければ何もしない", () => {
    const router = renderDeck();
    press("o");
    expect(router.state.location.pathname).toBe("/");
    press("j");
    press("o");
    const target = FOLLOWING[0];
    expect(router.state.location.pathname).toBe(
      `/e/${neventEncode({ id: target.id, author: target.pubkey })}`,
    );
  });

  it("r で返信、t で引用のコンポーザ。f で既定リアクション（確認なし）", () => {
    renderDeck();
    press("j");
    press("r");
    expect(useCompose.getState().request).toEqual({ mode: "reply", target: FOLLOWING[0] });
    act(() => useCompose.setState({ request: null }));

    press("j");
    press("t");
    expect(useCompose.getState().request).toEqual({ mode: "quote", target: FOLLOWING[1] });
    act(() => useCompose.setState({ request: null }));

    press("f");
    expect(reactWithDefault).toHaveBeenCalledWith(FOLLOWING[1]);
  });

  it("b で選択中の投稿のブックマークをトグルする（押した時点の状態に合わせて向きを決める）", () => {
    useSession.setState({ status: "in", method: "local", pubkey: getPublicKey(KEY) });
    renderDeck();
    press("j");
    press("b");
    expect(vi.mocked(toggleBookmark)).toHaveBeenCalledWith(getPublicKey(KEY), FOLLOWING[0].id, "bookmark");

    useOwnLists.setState({
      bookmarks: { eventId: "e1", createdAt: 1, ids: [FOLLOWING[0].id], otherTags: [], content: "" },
      pinned: null,
    });
    press("b");
    expect(vi.mocked(toggleBookmark)).toHaveBeenLastCalledWith(
      getPublicKey(KEY),
      FOLLOWING[0].id,
      "unbookmark",
    );
  });

  it("b は未ログインなら何もしない", () => {
    renderDeck();
    press("j");
    press("b");
    expect(vi.mocked(toggleBookmark)).not.toHaveBeenCalled();
  });

  it("コンポーザが開いている間は効かない", () => {
    renderDeck();
    press("n");
    expect(useCompose.getState().request).toEqual({ mode: "new" });
    expect(press("j")).toBe(false);
    expect(selectedRow("c_following")).toBeNull();
  });
});

describe("画面", () => {
  it("n でコンポーザ（新規）", () => {
    renderDeck();
    expect(press("n")).toBe(true);
    expect(useCompose.getState().request).toEqual({ mode: "new" });
  });

  it("? で一覧を開き、Esc で閉じる。? でも閉じる", () => {
    renderDeck();
    press("?", { shiftKey: true });
    const dialog = screen.getByRole("dialog", { name: "キーボードショートカット" });
    expect(dialog).toHaveTextContent("次の投稿");
    expect(dialog).toHaveTextContent("Esc または画面タップで閉じる");
    // 開いている間は j 等は効かない
    press("j");
    expect(selectedRow("c_following")).toBeNull();

    expect(press("Escape")).toBe(true);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    press("?", { shiftKey: true });
    press("?", { shiftKey: true });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("一覧はスクリムを押すと閉じる", () => {
    renderDeck();
    press("?", { shiftKey: true });
    fireEvent.click(screen.getByRole("button", { name: "閉じる" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("/ で検索画面へ移り、検索欄にフォーカスする", async () => {
    const router = renderDeck();
    press("/");
    await waitFor(() => expect(router.state.location.pathname).toBe("/search"));
    await waitFor(() => expect(screen.getByRole("searchbox", { name: "検索語" })).toHaveFocus());
  });
});
