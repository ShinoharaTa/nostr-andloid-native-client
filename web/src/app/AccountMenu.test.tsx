import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { noteEncode, npubEncode } from "nostr-tools/nip19";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { useDmSeen } from "../features/dm/dmSeen";
import { useDm } from "../features/dm/dmStore";
import { settingsSectionTarget } from "../features/settings/sections";
import type { ColumnSpec } from "../lib/columns";
import { DEFAULT_COLUMNS } from "../lib/columns";
import { useSession } from "../signer/session";
import { useDeck } from "../store/deck";
import { installDialogPolyfill } from "../test/dialog";
import { OTHER_PUBKEY, PUBKEY, resetSession } from "../test/fakeNostr";
import { clearViewport, mockViewport } from "../test/viewport";
import {
  ACCOUNT_MENU_ITEMS,
  accountMenuSection,
  accountMenuTarget,
  badgeCount,
  endsGroup,
  isAccountIconActive,
} from "./accountMenuModel";
import { routes } from "./routes";

/*
 * [#797][#807] 自分のアイコンのメニュー（ネイティブ AccountMenuTest）。並びと区切り線、各項目の行き先、
 * メニューから開いた設定の項目の戻り先（メニューを開く前の画面）、未ログイン時、未読 DM の件数の出し先を守る。
 */

// カラムの中身（購読・仮想リスト）とダイアログは描かない（AppShell.test と同じ）
vi.mock("../features/deck/DeckColumn", () => ({
  DeckColumn: ({ spec }: { spec: ColumnSpec }) => <div data-testid={`col-${spec.id}`} />,
  ColumnMenu: () => <button type="button">カラムメニュー</button>,
}));
vi.mock("../features/deck/useColumnFeed", () => ({
  useColumnFeed: () => ({
    mode: "column",
    loading: false,
    events: [],
    loadingOlder: false,
    loadOlder: () => {},
    refresh: () => {},
  }),
}));
vi.mock("../features/deck/AddColumnDialog", () => ({ AddColumnDialog: () => <div role="dialog" /> }));
vi.mock("../features/deck/EditColumnDialog", () => ({ EditColumnDialog: () => <div role="dialog" /> }));
// DM の購読・復号はしない
vi.mock("../features/dm/dmService", () => ({ startDecrypting: vi.fn(), resumeDecrypting: vi.fn() }));

const NOTE = noteEncode("5c83da77af1dec6d7289834998ad7aafbd9e2191396d75ec3cc27f5a77226f36");

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState(null, "");
  useDeck.setState({
    columns: structuredClone([...DEFAULT_COLUMNS]),
    widths: {},
    jumpTarget: null,
    visibleColumnId: null,
    editingColumnId: null,
    showAddColumn: false,
  });
  useSession.setState({ status: "in", method: "nip07", pubkey: PUBKEY });
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ data: [] }))),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  clearViewport();
  resetSession();
  useDm.getState().reset(null);
  useDmSeen.setState({ me: null, first: 0, peers: {} });
});

/** いま描いている Router（choose が移り先を待つのに使う） */
let current: ReturnType<typeof createMemoryRouter> | null = null;

function renderAt(initialEntries: string[], width = 400) {
  mockViewport(width, { hover: false });
  const router = createMemoryRouter(routes, { initialEntries, initialIndex: initialEntries.length - 1 });
  render(<RouterProvider router={router} />);
  current = router;
  return router;
}

function mainNav() {
  return screen.getByRole("navigation", { name: "メイン" });
}

function accountIcon() {
  return within(mainNav()).getByRole("button", { name: /^アカウントのメニュー/ });
}

async function openMenu() {
  await userEvent.click(accountIcon());
  return screen.getByRole("menu", { name: "アカウントのメニュー" });
}

/** メニューの項目を選び、画面が移るまで待つ（メニューが積んだ履歴の印が消えてから移る） */
async function choose(name: string | RegExp, path: string) {
  const menu = await openMenu();
  await userEvent.click(within(menu).getByRole("menuitem", { name }));
  await waitFor(() => expect(current?.state.location.pathname).toBe(path));
}

function seedUnreadDm(n: number) {
  useDm.getState().reset(PUBKEY);
  useDmSeen.setState({ me: PUBKEY, first: 0, peers: {} });
  useDm.getState().upsertMessages(
    Array.from({ length: n }, (_, i) => ({
      owner: PUBKEY,
      id: `d${i}`,
      peer: OTHER_PUBKEY,
      sender: OTHER_PUBKEY,
      content: `未読 ${i}`,
      tags: [],
      createdAt: 1 + i,
      proto: "nip17" as const,
    })),
  );
}

describe("並びと区切り線（ネイティブ menu_order_and_dividers）", () => {
  it("10 行。区切り線は よく使う / 絵文字・ハッシュタグ・リレー / 設定 / ログアウト の境目の 3 か所", async () => {
    expect(ACCOUNT_MENU_ITEMS).toEqual([
      "profile",
      "dm",
      "favs",
      "bookmarks",
      "mute",
      "emoji",
      "hashtags",
      "relays",
      "settings",
      "logout",
    ]);
    expect(ACCOUNT_MENU_ITEMS.filter(endsGroup)).toEqual(["mute", "relays", "settings"]);

    renderAt(["/"]);
    const menu = await openMenu();
    // リレーの右には「接続数 / 全体 接続中」（数はリレーの状態しだいなので N / M に置き換えて比べる）
    const text = (el: Element | undefined) => el?.textContent?.replace(/\d+ \/ \d+/, "N / M");
    expect(within(menu).getAllByRole("menuitem").map(text)).toEqual([
      "プロフィール",
      "DM",
      "ふぁぼ",
      "ブックマーク",
      "ミュート",
      "カスタム絵文字",
      "ハッシュタグ",
      "リレーN / M 接続中",
      "設定",
      "ログアウト",
    ]);
    // 区切り線は項目の間に 3 本（DOM の順で ミュート・リレー・設定 の直後）
    const children = [...menu.children];
    const separators = children.flatMap((el, i) => (el.tagName === "HR" ? [i] : []));
    expect(separators.map((i) => text(children[i - 1]))).toEqual(["ミュート", "リレーN / M 接続中", "設定"]);
  });

  it("各項目にモノクロのアイコン（文字色）。ログアウトだけ Warn 色の印", async () => {
    renderAt(["/"]);
    const menu = await openMenu();
    for (const item of within(menu).getAllByRole("menuitem")) {
      expect(item.querySelector("svg")).not.toBeNull();
    }
    expect(within(menu).getByRole("menuitem", { name: "ログアウト" }).className).toMatch(/danger/);
    expect(within(menu).getByRole("menuitem", { name: "設定" }).className).not.toMatch(/danger/);
  });
});

describe("アイコンのタップ（ネイティブ icon_opens_menu_when_logged_in / icon_goes_to_settings_when_logged_out）", () => {
  it("ログイン中はメニューを開く（設定へは飛ばない）。キーボードでも開け、Esc で閉じてアイコンへフォーカスを戻す", async () => {
    const router = renderAt(["/"]);
    accountIcon().focus();
    await userEvent.keyboard("{Enter}");
    const menu = screen.getByRole("menu", { name: "アカウントのメニュー" });
    expect(within(menu).getByRole("menuitem", { name: "プロフィール" })).toHaveFocus();
    expect(router.state.location.pathname).toBe("/");

    await userEvent.keyboard("{ArrowDown}");
    expect(within(menu).getByRole("menuitem", { name: "DM" })).toHaveFocus();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(accountIcon()).toHaveFocus();
    await waitFor(() => expect(router.state.location.state).toBeNull());
  });

  it("未ログイン（自分の pubkey の読み込み前）はメニューを出さず設定へ", async () => {
    const router = renderAt(["/"]);
    act(() => useSession.setState({ pubkey: null }));
    await userEvent.click(accountIcon());
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/settings");
  });

  it("レール（Expanded）でも同じメニュー", async () => {
    renderAt(["/"], 1400);
    const menu = await openMenu();
    expect(within(menu).getAllByRole("menuitem")).toHaveLength(10);
  });
});

describe("各項目の行き先", () => {
  it("プロフィール: 自分のプロフィールを重ねる（積む）", async () => {
    const router = renderAt(["/search"]);
    await choose("プロフィール", `/p/${npubEncode(PUBKEY)}`);
    expect(router.state.historyAction).toBe("PUSH");
    expect(screen.getByRole("region", { name: "プロフィール" })).toBeInTheDocument();
  });

  it("DM: パブリックチャットを開いていても、詳細を重ねていても、常に DM（置き換え）", async () => {
    const router = renderAt(["/channels", `/e/${NOTE}`]);
    await choose("DM", "/messages");
    expect(router.state.historyAction).toBe("REPLACE");
    expect(screen.getByRole("heading", { level: 1, name: "DM" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "スレッド" })).not.toBeInTheDocument();
  });

  it("ふぁぼ〜リレーは設定のその項目へ直接入る（詳細を重ねていても畳む）", async () => {
    const expected: [string | RegExp, string][] = [
      ["ふぁぼ", "favs"],
      ["ブックマーク", "bookmarks"],
      ["ミュート", "mute"],
      ["カスタム絵文字", "emoji"],
      ["ハッシュタグ", "hashtags"],
      [/^リレー/, "relays"],
    ];
    expect(ACCOUNT_MENU_ITEMS.filter((i) => accountMenuSection(i) !== null)).toEqual([
      "favs",
      "bookmarks",
      "mute",
      "emoji",
      "hashtags",
      "relays",
    ]);
    for (const [name, section] of expected) {
      const router = renderAt([`/e/${NOTE}`]);
      await choose(name, `/settings/${section}`);
      expect(router.state.historyAction).toBe("PUSH");
      expect(screen.queryByRole("region", { name: "スレッド" })).not.toBeInTheDocument();
      cleanup();
    }
  });

  it("設定: 設定の一覧へ（置き換え）", async () => {
    const router = renderAt([`/e/${NOTE}`]);
    await choose("設定", "/settings");
    expect(router.state.historyAction).toBe("REPLACE");
    expect(screen.getByRole("navigation", { name: "設定の項目" })).toBeInTheDocument();
  });

  it("ログアウト: 設定と同じ確認ダイアログ（画面は移らない）。キャンセルならログインしたまま", async () => {
    const router = renderAt(["/search"]);
    const menu = await openMenu();
    await userEvent.click(within(menu).getByRole("menuitem", { name: "ログアウト" }));
    const dialog = screen.getByRole("dialog", { name: "ログアウトしますか？" });
    await userEvent.click(within(dialog).getByRole("button", { name: "キャンセル" }));
    expect(screen.queryByRole("dialog", { name: "ログアウトしますか？" })).not.toBeInTheDocument();
    expect(useSession.getState().status).toBe("in");
    await waitFor(() => expect(router.state.location.state).toBeNull());
    expect(router.state.location.pathname).toBe("/search");
  });

  it("行き先（純粋関数）: ログアウトと設定の項目は accountMenuTarget では移らない", () => {
    expect(accountMenuTarget("profile", PUBKEY)).toEqual({ to: `/p/${npubEncode(PUBKEY)}`, replace: false });
    expect(accountMenuTarget("dm", PUBKEY)).toEqual({ to: "/messages", replace: true });
    expect(accountMenuTarget("settings", PUBKEY)).toEqual({ to: "/settings", replace: true });
    expect(accountMenuTarget("logout", PUBKEY)).toBeNull();
    expect(accountMenuTarget("mute", PUBKEY)).toBeNull();
  });
});

describe("戻り先（ネイティブ closing_menu_section_returns_to_previous_screen 等）", () => {
  it("メニューから開いた項目の「←」は、メニューを開く前の画面（パブリックチャットに重ねていたスレッド）へ戻る", async () => {
    const router = renderAt(["/channels"]);
    await act(() => router.navigate(`/e/${NOTE}`));
    await choose("ミュート", "/settings/mute");
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe(`/e/${NOTE}`);
    expect(screen.getByRole("region", { name: "スレッド" })).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 1, name: "パブリックチャット", hidden: true }),
    ).toBeInTheDocument();
  });

  it("自分のプロフィール →「編集」→「←」で、そのプロフィールへ戻る", async () => {
    const router = renderAt(["/"]);
    await choose("プロフィール", `/p/${npubEncode(PUBKEY)}`);
    await userEvent.click(screen.getByRole("button", { name: "編集" }));
    expect(router.state.location.pathname).toBe("/settings/profile-edit");
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe(`/p/${npubEncode(PUBKEY)}`);
  });

  it("ホーム（デッキ）から開いた項目の「←」はデッキへ戻る（見ていたカラムはストアの visibleColumnId のまま）", async () => {
    const router = renderAt(["/"]);
    await choose(/^リレー/, "/settings/relays");
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/");
    expect(screen.getByTestId("col-c_following")).toBeInTheDocument();
  });

  it("設定の項目を開いたままメニューから別の項目へ移っても、戻り先は最初にメニューを開く前の画面", async () => {
    const router = renderAt(["/messages"]);
    await choose("ミュート", "/settings/mute");
    await choose(/^リレー/, "/settings/relays");
    expect(router.state.historyAction).toBe("REPLACE");
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/messages");
  });

  it("設定の一覧から開いた項目は、今までどおり一覧へ戻る（メニューから入った後に一覧から移った場合も）", async () => {
    const router = renderAt(["/"]);
    await choose("設定", "/settings");
    await userEvent.click(
      within(screen.getByRole("navigation", { name: "設定の項目" })).getByRole("button", { name: "表示" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/settings");
    cleanup();

    const t = renderAt(["/"]);
    await choose("ミュート", "/settings/mute");
    await choose("設定", "/settings");
    await userEvent.click(
      within(screen.getByRole("navigation", { name: "設定の項目" })).getByRole("button", {
        name: "データ・キャッシュ",
      }),
    );
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(t.state.location.pathname).toBe("/settings");
  });

  it("設定の一覧を見ている間にメニューから開いた項目も一覧へ戻る", async () => {
    const router = renderAt(["/settings"]);
    await choose("ハッシュタグ", "/settings/hashtags");
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/settings");
  });

  it("設定から離れたら戻り先は忘れる（別の入り方で同じ項目へ来たとき、古い画面へ戻さない）", async () => {
    const router = renderAt(["/search"]);
    await choose("カスタム絵文字", "/settings/emoji");
    await userEvent.click(within(mainNav()).getByRole("button", { name: "パブリックチャット" }));
    expect(router.state.location.pathname).toBe("/channels");
    await act(() => router.navigate("/settings/profile-edit", { replace: true }));
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/settings");
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("行き先（純粋関数 settingsSectionTarget）", () => {
    const mode = "compact" as const;
    expect(settingsSectionTarget("mute", { pathname: "/channels", state: null, mode })).toEqual({
      to: "/settings/mute",
      replace: false,
      state: { settingsFromOutside: true },
    });
    expect(
      settingsSectionTarget("relays", {
        pathname: "/settings/mute",
        state: { settingsFromOutside: true },
        mode,
      }),
    ).toEqual({ to: "/settings/relays", replace: true, state: { settingsFromOutside: true } });
    expect(
      settingsSectionTarget("relays", {
        pathname: "/settings/data",
        state: { settingsFromList: true },
        mode,
      }),
    ).toEqual({ to: "/settings/relays", replace: true, state: { settingsFromList: true } });
    expect(settingsSectionTarget("relays", { pathname: "/settings", state: null, mode })).toEqual({
      to: "/settings/relays",
      replace: false,
      state: { settingsFromList: true },
    });
    expect(settingsSectionTarget("relays", { pathname: "/settings", state: null, mode: "expanded" })).toEqual(
      {
        to: "/settings/relays",
        replace: true,
      },
    );
  });
});

describe("選択表示とバッジ", () => {
  it("自分のアイコンは設定と DM のときだけ選択表示（ネイティブ account_icon_is_selected_on_settings_and_dm）", () => {
    for (const [dest, active] of [
      ["settings", true],
      ["messages", true],
      ["channels", false],
      ["home", false],
      ["search", false],
      ["notifications", false],
      ["notFound", false],
    ] as const) {
      expect(isAccountIconActive(dest)).toBe(active);
    }
  });

  it("未読 DM の件数は自分のアイコンとメニューの「DM」にだけ出す", async () => {
    for (const item of ACCOUNT_MENU_ITEMS) {
      expect(badgeCount(item, 3)).toBe(item === "dm" ? 3 : 0);
      expect(badgeCount(item, 0)).toBe(0);
    }
    seedUnreadDm(2);
    renderAt(["/"]);
    expect(
      within(mainNav()).getByRole("button", { name: "アカウントのメニュー（未読 2 件）" }),
    ).toHaveTextContent("2");
    const menu = await openMenu();
    expect(within(menu).getByRole("menuitem", { name: "DM（未読 2 件）" })).toHaveTextContent("DM2");
    expect(within(menu).getByRole("menuitem", { name: "プロフィール" })).toHaveTextContent(/^プロフィール$/);
  });

  it("Expanded の 2 ペインでは、メニューから開いた一覧に無い項目を右に出し、一覧はどれも選択中にしない", async () => {
    renderAt(["/"], 1400);
    await choose("ブックマーク", "/settings/bookmarks");
    expect(screen.getByRole("region", { name: "ブックマーク" })).toBeInTheDocument();
    expect(
      within(screen.getByRole("navigation", { name: "設定の項目" }))
        .getAllByRole("button")
        .filter((b) => b.hasAttribute("aria-current")),
    ).toEqual([]);
  });
});
