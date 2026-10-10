import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { neventEncode, noteEncode, npubEncode } from "nostr-tools/nip19";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useCompose } from "../features/compose/composeStore";
import { saveDraft } from "../features/compose/storage";
import { useDmSeen } from "../features/dm/dmSeen";
import { useDm } from "../features/dm/dmStore";
import type { ColumnSpec } from "../lib/columns";
import { DEFAULT_COLUMNS } from "../lib/columns";
import { useSession } from "../signer/session";
import { useDeck } from "../store/deck";
import { installDialogPolyfill } from "../test/dialog";
import { OTHER_PUBKEY, PUBKEY, resetSession } from "../test/fakeNostr";
import { clearViewport, mockViewport } from "../test/viewport";
import { routes } from "./routes";

// カラムの中身（購読・仮想リスト）とダイアログは描かない
vi.mock("../features/deck/DeckColumn", () => ({
  DeckColumn: ({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) => (
    <div data-testid={`col-${spec.id}`} data-header={String(showHeader)} />
  ),
  ColumnMenu: () => <button type="button">カラムメニュー</button>,
}));
// 通知画面の購読もしない（空の一覧）
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

const NOTE = noteEncode("5c83da77af1dec6d7289834998ad7aafbd9e2191396d75ec3cc27f5a77226f36");

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
});

afterEach(() => {
  clearViewport();
  resetSession();
});

function renderAt(initialEntries: string[], width = 400, hover = false) {
  mockViewport(width, { hover });
  const router = createMemoryRouter(routes, { initialEntries });
  render(<RouterProvider router={router} />);
  return router;
}

function mainNav() {
  return screen.getByRole("navigation", { name: "メイン" });
}

/** レール・下部ナビのボタンの名前（DOM の順。レールのリレーの接続表示は除く） */
function navLabels() {
  return within(mainNav())
    .getAllByRole("button")
    .map((b) => b.getAttribute("aria-label"))
    .filter((label) => !label?.startsWith("リレー接続 "));
}

function currentNavLabels() {
  return within(mainNav())
    .getAllByRole("button")
    .filter((b) => b.getAttribute("aria-current") === "page")
    .map((b) => b.getAttribute("aria-label"));
}

describe("骨格", () => {
  it("compact: 下部ナビ（5 つ）と main。レールは無い", () => {
    renderAt(["/"]);
    expect(navLabels()).toEqual(["ホーム", "検索", "パブリックチャット", "通知", "アカウントのメニュー"]);
    expect(screen.getAllByRole("navigation", { name: "メイン" })).toHaveLength(1);
    expect(screen.queryByRole("img", { name: "Nostrism" })).not.toBeInTheDocument();
    expect(screen.getByRole("main")).toBeInTheDocument();
  });

  it("[#661] ホバーできる端末（PC を細くした場合）は 600px 未満でもレール（内容は compact のまま）", () => {
    renderAt(["/"], 500, true);
    expect(within(mainNav()).getByRole("img", { name: "Nostrism" })).toBeInTheDocument();
    expect(navLabels()).toEqual([
      "ホーム",
      "フォロー中",
      "#nostr",
      "通知",
      "カラム追加",
      "検索",
      "パブリックチャット",
      "アカウントのメニュー",
    ]);
    expect(screen.getAllByRole("navigation", { name: "メイン" })).toHaveLength(1);
    expect(screen.getByTestId("col-c_following")).toHaveAttribute("data-header", "false");
  });

  it("[#661][#680] タッチ端末でも 600px 以上・2 カラム入る閾値未満ならレール（内容は compact のまま）", () => {
    renderAt(["/"], 700);
    expect(within(mainNav()).getByRole("img", { name: "Nostrism" })).toBeInTheDocument();
    expect(navLabels()).toEqual([
      "ホーム",
      "フォロー中",
      "#nostr",
      "通知",
      "カラム追加",
      "検索",
      "パブリックチャット",
      "アカウントのメニュー",
    ]);
    expect(screen.getAllByRole("navigation", { name: "メイン" })).toHaveLength(1);
    expect(screen.getByTestId("col-c_following")).toHaveAttribute("data-header", "false");
  });

  it("[#648] 500px でもタッチ端末（hover 無し）なら下部ナビ（レールにしない）", () => {
    mockViewport(500, { hover: false });
    const router = createMemoryRouter(routes, { initialEntries: ["/"] });
    render(<RouterProvider router={router} />);
    expect(navLabels()).toEqual(["ホーム", "検索", "パブリックチャット", "通知", "アカウントのメニュー"]);
    expect(screen.queryByRole("img", { name: "Nostrism" })).not.toBeInTheDocument();
  });

  it("[#540] 439px は下部ナビ（レールは無い）", () => {
    renderAt(["/"], 439);
    expect(navLabels()).toEqual(["ホーム", "検索", "パブリックチャット", "通知", "アカウントのメニュー"]);
    expect(screen.queryByRole("img", { name: "Nostrism" })).not.toBeInTheDocument();
  });

  it("expanded: レールに目次 3 件。通知カラムがあれば通知ボタンは出さず、消すと出る", () => {
    renderAt(["/"], 1400);
    expect(within(mainNav()).getByRole("img", { name: "Nostrism" })).toBeInTheDocument();
    expect(screen.getAllByRole("navigation", { name: "メイン" })).toHaveLength(1);
    expect(navLabels()).toEqual([
      "ホーム",
      "フォロー中",
      "#nostr",
      "通知",
      "カラム追加",
      "検索",
      "パブリックチャット",
      "アカウントのメニュー",
    ]);

    act(() => {
      useDeck.getState().removeColumn("c_notif");
    });
    expect(navLabels()).toEqual([
      "ホーム",
      "フォロー中",
      "#nostr",
      "カラム追加",
      "検索",
      "パブリックチャット",
      "通知",
      "アカウントのメニュー",
    ]);
  });
});

describe("/ の出し分け（#647: LP は静的 HTML 側なのでここでは何も描かない）", () => {
  it("セッション復元中は何も描かない", () => {
    useSession.setState({ status: "loading", method: null, pubkey: null });
    const router = renderAt(["/"]);
    expect(screen.queryByRole("main")).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
  });

  it("未ログインは何も描かない。/login へは飛ばない", () => {
    useSession.setState({ status: "out", method: null, pubkey: null });
    const router = renderAt(["/"]);
    expect(screen.queryByRole("main")).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
  });

  it("ログイン済みはデッキを描く（骨格 参照）", () => {
    renderAt(["/"]);
    expect(screen.getByRole("main")).toBeInTheDocument();
  });
});

describe("/about（#647: 常に LP。ログイン中でも）", () => {
  it("ログイン中でも何も描かない。デッキへは飛ばない", () => {
    const router = renderAt(["/about"]);
    expect(screen.queryByRole("main")).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/about");
  });

  it("未ログインでも何も描かない。/login へは飛ばない", () => {
    useSession.setState({ status: "out", method: null, pubkey: null });
    const router = renderAt(["/about"]);
    expect(screen.queryByRole("main")).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/about");
  });
});

describe("宛先", () => {
  it.each([
    ["/search", "検索", "検索"],
    ["/notifications", "通知", "通知"],
    ["/messages", "DM", "アカウントのメニュー"],
    ["/channels", "パブリックチャット", "パブリックチャット"],
    ["/settings", "設定", "アカウントのメニュー"],
    ["/settings/relays", "リレー", "アカウントのメニュー"],
  ])("%s は見出し「%s」とナビの「%s」を選択表示する", async (path, heading, nav) => {
    renderAt([path]);
    expect(await screen.findByRole("heading", { name: heading })).toBeInTheDocument();
    expect(currentNavLabels()).toEqual([nav]);
  });

  it("/notifications は通知画面（通知の一覧。準備中の文言は無い）", async () => {
    renderAt(["/notifications"]);
    expect(await screen.findByRole("heading", { name: "通知" })).toBeInTheDocument();
    expect(screen.getByText("通知はまだありません")).toBeInTheDocument();
    expect(screen.queryByText(/準備中/)).not.toBeInTheDocument();
    expect(currentNavLabels()).toEqual(["通知"]);
  });

  it("設定（アカウント）のログアウトで未ログインになり /login?next=%2Fsettings%2Faccount へ", async () => {
    installDialogPolyfill();
    const router = renderAt(["/settings/account"]);
    await userEvent.click(await screen.findByRole("button", { name: "ログアウト" }));
    const dialog = screen.getByRole("dialog", { name: "ログアウトしますか？" });
    await userEvent.click(within(dialog).getByRole("button", { name: "ログアウト" }));
    expect(useSession.getState().status).toBe("out");
    expect(router.state.location.pathname).toBe("/login");
    expect(router.state.location.search).toBe("?next=%2Fsettings%2Faccount");
  });

  it("未定義のパスは「ページが見つかりません」とデッキへのリンク。どのナビも選択しない", async () => {
    renderAt(["/no/such/path"]);
    expect(await screen.findByRole("heading", { name: "ページが見つかりません" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "デッキへ戻る" })).toHaveAttribute("href", "/");
    expect(
      within(mainNav())
        .getAllByRole("button")
        .some((b) => b.hasAttribute("aria-current")),
    ).toBe(false);
  });
});

describe("詳細", () => {
  it("/e/<note1…> はデッキを背後に残したままスレッドを重ね、背後を inert にする", () => {
    renderAt([`/e/${NOTE}`]);
    expect(screen.getByTestId("col-c_following")).toBeInTheDocument();
    const thread = screen.getByRole("region", { name: "スレッド" });
    expect(within(thread).getByRole("button", { name: "戻る" })).toBeInTheDocument();
    expect(within(thread).getByText("読み込み中…")).toBeInTheDocument();
    expect(screen.getByRole("main").firstElementChild).toHaveAttribute("inert");
  });

  it("/e/zzz は「URL が正しくありません」", () => {
    renderAt(["/e/zzz"]);
    expect(screen.getByText("URL が正しくありません")).toBeInTheDocument();
  });

  it("/p/<npub1…> はプロフィールを重ね、見出しは名前（未取得なら hex の先頭 10 字）", () => {
    renderAt([`/p/${npubEncode(OTHER_PUBKEY)}`]);
    const profile = screen.getByRole("region", { name: "プロフィール" });
    expect(
      within(profile).getByRole("heading", { level: 1, name: OTHER_PUBKEY.slice(0, 10) }),
    ).toBeInTheDocument();
  });

  it("背後の宛先を保ち、「戻る」はアプリ内の履歴があれば戻り、無ければデッキへ置き換える", async () => {
    const user = userEvent.setup();
    const router = renderAt(["/search"]);
    await act(() => router.navigate(`/e/${NOTE}`));
    expect(screen.getByRole("heading", { name: "検索", hidden: true })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "スレッド" })).toBeInTheDocument();

    window.history.replaceState({ idx: 1 }, "");
    await user.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/search");
    expect(screen.queryByRole("region", { name: "スレッド" })).not.toBeInTheDocument();

    await act(() => router.navigate(`/e/${NOTE}`));
    window.history.replaceState(null, "");
    await user.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/");
    expect(router.state.historyAction).toBe("REPLACE");
  });
});

describe("ナビ", () => {
  it("「検索」は /search へ置き換える", async () => {
    const router = renderAt(["/"]);
    await userEvent.click(within(mainNav()).getByRole("button", { name: "検索" }));
    expect(router.state.location.pathname).toBe("/search");
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("「通知」は通知カラムがあればそこへ jump し、無ければ通知画面へ", async () => {
    const user = userEvent.setup();
    const router = renderAt(["/"]);
    await user.click(within(mainNav()).getByRole("button", { name: "通知" }));
    expect(router.state.location.pathname).toBe("/");
    expect(useDeck.getState().jumpTarget).toBeNull();
    expect(useDeck.getState().visibleColumnId).toBe("c_notif");
    expect(currentNavLabels()).toEqual(["通知"]);

    act(() => {
      useDeck.getState().removeColumn("c_notif");
    });
    await user.click(within(mainNav()).getByRole("button", { name: "通知" }));
    expect(router.state.location.pathname).toBe("/notifications");
  });

  it("検索画面で「ホーム」を押すとデッキへ戻ってフォロー中へ jump する", async () => {
    useDeck.setState({ visibleColumnId: "c_notif" });
    const router = renderAt(["/search"]);
    await userEvent.click(within(mainNav()).getByRole("button", { name: "ホーム" }));
    expect(router.state.location.pathname).toBe("/");
    expect(useDeck.getState().jumpTarget).toBeNull();
    expect(useDeck.getState().visibleColumnId).toBe("c_following");
  });

  it("expanded: レールの目次はデッキへ戻ってそのカラムへ jump する", async () => {
    const targets: string[] = [];
    const unsubscribe = useDeck.subscribe((s) => {
      if (s.jumpTarget !== null) targets.push(s.jumpTarget);
    });
    const router = renderAt(["/search"], 1400);
    await userEvent.click(within(mainNav()).getByRole("button", { name: "#nostr" }));
    unsubscribe();
    expect(router.state.location.pathname).toBe("/");
    expect(targets).toEqual(["c_hashtag"]);
    expect(useDeck.getState().jumpTarget).toBeNull();
  });

  it("DM の未読があれば下部ナビ・レールの自分のアイコンに数を出す（#506 / #797）", () => {
    useDm.getState().reset(PUBKEY);
    useDmSeen.setState({ me: PUBKEY, first: 0, peers: {} });
    useDm.getState().upsertMessages(
      ["a1", "a2"].map((id, i) => ({
        owner: PUBKEY,
        id,
        peer: OTHER_PUBKEY,
        sender: OTHER_PUBKEY,
        content: id,
        tags: [],
        createdAt: 1 + i,
        proto: "nip17" as const,
      })),
    );
    try {
      renderAt(["/"]);
      expect(
        within(mainNav()).getByRole("button", { name: "アカウントのメニュー（未読 2 件）" }),
      ).toBeInTheDocument();
      cleanup();
      renderAt(["/"], 1400);
      expect(
        within(mainNav()).getByRole("button", { name: "アカウントのメニュー（未読 2 件）" }),
      ).toBeInTheDocument();
    } finally {
      useDm.getState().reset(null);
      useDmSeen.setState({ me: null, first: 0, peers: {} });
    }
  });

  it("宛先の外で jump したらデッキへ出る", async () => {
    const router = renderAt(["/search"]);
    act(() => {
      useDeck.getState().jumpTo("c_notif");
    });
    expect(router.state.location.pathname).toBe("/");
  });

  it("検索画面の「Deckに追加」でデッキへ出る", async () => {
    const user = userEvent.setup();
    const router = renderAt(["/search"]);
    await user.type(await screen.findByRole("searchbox", { name: "検索語" }), "rally{Enter}");
    await user.click(screen.getByRole("button", { name: "Deckに追加" }));
    expect(router.state.location.pathname).toBe("/");
    expect(useDeck.getState().columns.at(-1)).toMatchObject({ title: "rally", kind: "GLOBAL" });
  });
});

describe("一時カラム（/t/:tag）", () => {
  it("一時カラムを開いて / に置き換え、戻るとそのカラムを閉じる", async () => {
    const router = renderAt(["/", "/t/bitcoin"]);
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/"));
    const last = useDeck.getState().columns.at(-1);
    expect(last).toMatchObject({ title: "#bitcoin", pinned: false });
    expect(router.state.location.state).toEqual({ deckTransient: last?.id });

    await act(() => router.navigate(-1));
    expect(useDeck.getState().columns.map((c) => c.id)).toEqual(["c_following", "c_hashtag", "c_notif"]);
  });

  it("固定済みのタグ（#nostr）は既存カラムへ jump し、戻っても閉じない", async () => {
    const router = renderAt(["/", "/t/nostr"]);
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(router.state.location.state).toEqual({ deckTransient: "c_hashtag" });

    await act(() => router.navigate(-1));
    expect(useDeck.getState().columns.map((c) => c.id)).toEqual(["c_following", "c_hashtag", "c_notif"]);
  });
});

describe("共有（/share。#541）", () => {
  afterEach(() => {
    act(() => useCompose.setState({ request: null }));
    localStorage.clear();
  });

  it("title・text・url を改行でつないで下書きに入れ、投稿シートを開いて / に置き換える", async () => {
    installDialogPolyfill();
    const router = renderAt(["/share?title=a&text=b&url=https://x"]);
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(screen.getByRole("dialog", { name: "投稿" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "本文" })).toHaveValue("a\nb\nhttps://x");
  });

  it("空の値は省く", async () => {
    installDialogPolyfill();
    const router = renderAt(["/share?text=本文だけ"]);
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(screen.getByRole("textbox", { name: "本文" })).toHaveValue("本文だけ");
  });

  it("書きかけの下書きは消さず、空行を挟んで後ろへ足す", async () => {
    installDialogPolyfill();
    saveDraft("書きかけ");
    const router = renderAt(["/share?url=https://x"]);
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(screen.getByRole("textbox", { name: "本文" })).toHaveValue("書きかけ\n\nhttps://x");
  });
});

describe("web+nostr:（/open。#541）", () => {
  it("npub / nprofile は /p/ のプロフィールへ", async () => {
    const npub = npubEncode(OTHER_PUBKEY);
    const router = renderAt([`/open?uri=${encodeURIComponent(`web+nostr:${npub}`)}`]);
    await vi.waitFor(() => expect(router.state.location.pathname).toBe(`/p/${npub}`));
    expect(screen.getByRole("region", { name: "プロフィール" })).toBeInTheDocument();
  });

  it("note / nevent / naddr は /e/ のスレッドへ（nostr: だけの URI も受ける）", async () => {
    const nevent = neventEncode({ id: "5c83da77af1dec6d7289834998ad7aafbd9e2191396d75ec3cc27f5a77226f36" });
    const router = renderAt([`/open?uri=${encodeURIComponent(`nostr:${nevent}`)}`]);
    await vi.waitFor(() => expect(router.state.location.pathname).toBe(`/e/${nevent}`));
    expect(screen.getByRole("region", { name: "スレッド" })).toBeInTheDocument();
  });

  it("読めない値は「ページが見つかりません」", async () => {
    const router = renderAt([`/open?uri=${encodeURIComponent("web+nostr:not-a-valid-ref")}`]);
    await vi.waitFor(() =>
      expect(screen.getByRole("heading", { name: "ページが見つかりません" })).toBeInTheDocument(),
    );
    expect(router.state.location.pathname).toBe("/404");
  });
});

describe("投稿ボタン", () => {
  it("デッキ（/）には「投稿」を出す", () => {
    renderAt(["/"]);
    expect(screen.getByRole("button", { name: "投稿" })).toBeInTheDocument();
  });

  it("検索では出さない", async () => {
    renderAt(["/search"]);
    expect(await screen.findByRole("heading", { name: "検索" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "投稿" })).toBeNull();
  });

  it("スレッドの詳細では出さない", () => {
    renderAt([`/e/${NOTE}`]);
    expect(screen.getByRole("region", { name: "スレッド" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "投稿" })).toBeNull();
  });
});
