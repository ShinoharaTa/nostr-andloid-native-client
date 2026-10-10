import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { createMemoryRouter, RouterProvider } from "react-router";
import { VirtuosoMockContext } from "react-virtuoso";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  buildColumn,
  type ColumnSpec,
  columnSubtitleFor,
  DEFAULT_COLUMNS,
  decodeDeckColumns,
} from "../../lib/columns";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { FEED_CAT_HIDDEN_KEY, loadFeedCatHidden, useDeck } from "../../store/deck";
import { OTHER_PUBKEY, PUBKEY } from "../../test/fakeNostr";
import { renderWithRouter } from "../../test/renderWithRouter";
import { startDecrypting } from "../dm/dmService";
import { useDm } from "../dm/dmStore";
import { dmNotices, toNotification } from "../notifications/notificationModel";
import { toggleFollow } from "../profile/follow";
import { DeckColumn } from "./DeckColumn";
import { useColumnFeed } from "./useColumnFeed";

// フォロー / 解除は署名・発行しない（DeckColumn の PROFILE カードのテスト用）
vi.mock("../profile/follow", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../profile/follow")>()),
  toggleFollow: vi.fn(async () => "done"),
}));

// DM の購読・復号はしない（状態はストアへ直接入れる）
vi.mock("../dm/dmService", () => ({ startDecrypting: vi.fn(), resumeDecrypting: vi.fn() }));

// 購読はしない（本体は空のタイムライン）
vi.mock("./useColumnFeed", () => ({
  useColumnFeed: vi.fn(() => ({
    mode: "column",
    loading: false,
    events: [],
    rows: null,
    loadingOlder: false,
    loadOlder: () => {},
    refresh: () => {},
  })),
}));

const DM: ColumnSpec = {
  id: "c_dm",
  title: "DM",
  subtitle: "NIP-17",
  kind: "DM",
  renderer: "FEED",
  filter: { ...DEFAULT_COLUMNS[0].filter, kinds: [14] },
  pinned: true,
  order: 3,
};

/** Web 版でまだ描けない種別 */
const THREAD: ColumnSpec = { ...DM, id: "c_thread", title: "スレッド", kind: "THREAD", renderer: "THREAD" };

beforeEach(() => {
  useDeck.setState({ columns: [...DEFAULT_COLUMNS, DM], widths: {} });
});

afterEach(() => {
  localStorage.clear();
  useDm.getState().reset(null);
});

it("ヘッダにタイトルとサブタイトルを出す", () => {
  const [, hashtag] = DEFAULT_COLUMNS;
  renderWithRouter(<DeckColumn spec={hashtag} showHeader />);
  expect(screen.getByRole("heading", { name: "#nostr" })).toBeInTheDocument();
  expect(screen.getByText(columnSubtitleFor(hashtag))).toBeInTheDocument();
  expect(screen.getByText("投稿がありません")).toBeInTheDocument();
});

it("左端のカラムのメニューでは「左へ移動」が押せない", async () => {
  const user = userEvent.setup();
  renderWithRouter(<DeckColumn spec={DEFAULT_COLUMNS[0]} showHeader />);
  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));

  expect(screen.getByRole("menu")).toBeInTheDocument();
  expect(screen.getByRole("menuitem", { name: "左へ移動" })).toBeDisabled();
  expect(screen.getByRole("menuitem", { name: "右へ移動" })).toBeEnabled();
  // フォロー中は設定を持たないので「フィルターを編集」は出さない
  expect(screen.queryByRole("menuitem", { name: /フィルターを編集/ })).not.toBeInTheDocument();

  await user.keyboard("{Escape}");
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
});

it("[#819] メニューに「更新」は無い（引っ張って更新で足りる）。一時カラムの「固定する」は残す", async () => {
  const user = userEvent.setup();
  const temporary: ColumnSpec = { ...DEFAULT_COLUMNS[1], id: "c_temp", pinned: false };
  useDeck.setState({ columns: [...DEFAULT_COLUMNS, temporary], widths: {} });
  renderWithRouter(<DeckColumn spec={temporary} showHeader />);
  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));

  expect(screen.queryByRole("menuitem", { name: "更新" })).not.toBeInTheDocument();
  expect(screen.getByRole("menuitem", { name: "固定する" })).toBeInTheDocument();
});

/** DM カラムを / に描き、/messages/:peer へ移れるルータ */
function renderDmColumn(spec: ColumnSpec) {
  const router = createMemoryRouter([
    { path: "/", element: <DeckColumn spec={spec} showHeader /> },
    { path: "/messages/:peer", element: <p>messages</p> },
  ]);
  render(<RouterProvider router={router} />);
  return router;
}

function seedDm() {
  useDm.getState().reset(PUBKEY);
  useDm.setState({ loaded: true });
  useDm.getState().upsertMessages([
    {
      owner: PUBKEY,
      id: "a1",
      peer: OTHER_PUBKEY,
      sender: OTHER_PUBKEY,
      content: "こんにちは",
      tags: [],
      createdAt: 1_700_000_000,
      proto: "nip17",
    },
  ]);
}

it("DM カラムは会話の一覧を出し、購読しない。表示したら復号を始め、行を押すと /messages/npub1…（#506）", async () => {
  const user = userEvent.setup();
  vi.mocked(useColumnFeed).mockClear();
  vi.mocked(startDecrypting).mockClear();
  seedDm();
  const router = renderDmColumn(DM);

  expect(screen.getByRole("heading", { name: "DM" })).toBeInTheDocument();
  expect(screen.getByText("NIP-17")).toBeInTheDocument();
  expect(screen.queryByText(/まだ使えません/)).not.toBeInTheDocument();
  expect(vi.mocked(useColumnFeed)).not.toHaveBeenCalled();
  expect(vi.mocked(startDecrypting)).toHaveBeenCalledTimes(1);
  // 復号の案内（showBanners）はカラムに出さない
  act(() => useDm.setState({ pending: 3 }));
  expect(screen.queryByRole("status")).not.toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: /こんにちは/ }));
  expect(router.state.location.pathname).toBe(`/messages/${npubEncode(OTHER_PUBKEY)}`);
  // 履歴に積む（戻るでデッキへ）
  expect(router.state.historyAction).toBe("PUSH");
});

it("同期で入ってきた DM カラム（ネイティブの JSON）も会話の一覧になる", () => {
  const [synced] =
    decodeDeckColumns(
      '[{"id":"col_dm_1700000000","title":"DM","subtitle":"NIP-17","kind":"DM","renderer":"FEED","filter":{"kinds":[14]}}]',
    ) ?? [];
  seedDm();
  renderDmColumn(synced);
  expect(screen.getByRole("heading", { name: "DM" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /こんにちは/ })).toBeInTheDocument();
});

it("⋯ の「ミュートを表示 / 隠す」でカラムの設定を切り替える。描けない種別には出さない（#465）", async () => {
  const user = userEvent.setup();
  const [, hashtag] = DEFAULT_COLUMNS;
  useDeck.setState({ revealMuted: [] });
  const { unmount } = renderWithRouter(<DeckColumn spec={hashtag} showHeader />);

  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
  await user.click(screen.getByRole("menuitem", { name: "ミュートを表示" }));
  expect(useDeck.getState().revealMuted).toEqual(["c_hashtag"]);
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
  await user.click(screen.getByRole("menuitem", { name: "ミュートを隠す" }));
  expect(useDeck.getState().revealMuted).toEqual([]);
  unmount();

  renderWithRouter(<DeckColumn spec={THREAD} showHeader />);
  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
  expect(screen.queryByRole("menuitem", { name: "ミュートを表示" })).not.toBeInTheDocument();
});

it("⋯ の「タイムラインに混ぜる表示」: フォロー中カラムだけ 6 つのトグルを出し、押してもメニューは開いたまま。保存して再描画で戻す（#522 / #796）", async () => {
  const user = userEvent.setup();
  const [following, hashtag, notif] = DEFAULT_COLUMNS;
  useDeck.setState({ feedCatHidden: {} });
  const { unmount } = renderWithRouter(<DeckColumn spec={following} showHeader />);
  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));

  const group = screen.getByRole("group", { name: "タイムラインに混ぜる表示" });
  expect(group).toHaveTextContent("タイムラインに混ぜる表示");
  const toggles = screen.getAllByRole("menuitemcheckbox");
  expect(toggles.map((t) => t.textContent)).toEqual([
    "自分へのリアクション",
    "自分への返信・メンション",
    "自分へのリポスト",
    "自分がしたリアクション",
    "未読のメッセージ",
    "パブリックチャットの発言",
  ]);
  // 既定は全部表示（[#796] パブリックチャットの発言も）
  for (const t of toggles) expect(t).toHaveAttribute("aria-checked", "true");

  await user.click(screen.getByRole("menuitemcheckbox", { name: "自分へのリアクション" }));
  await user.click(screen.getByRole("menuitemcheckbox", { name: "未読のメッセージ" }));
  await user.click(screen.getByRole("menuitemcheckbox", { name: "パブリックチャットの発言" }));
  expect(screen.getByRole("menu")).toBeInTheDocument();
  expect(screen.getByRole("menuitemcheckbox", { name: "自分へのリアクション" })).toHaveAttribute(
    "aria-checked",
    "false",
  );
  expect(JSON.parse(localStorage.getItem(FEED_CAT_HIDDEN_KEY) ?? "null")).toEqual({
    c_following: ["REACTIONS", "DMS", "CHAT"],
  });

  // 保存値から読み直して描き直しても隠したまま
  unmount();
  useDeck.setState({ feedCatHidden: {} });
  useDeck.setState({ feedCatHidden: loadFeedCatHidden() });
  const second = renderWithRouter(<DeckColumn spec={following} showHeader />);
  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
  const checked = (name: string) =>
    screen.getByRole("menuitemcheckbox", { name }).getAttribute("aria-checked");
  expect(checked("自分へのリアクション")).toBe("false");
  expect(checked("未読のメッセージ")).toBe("false");
  expect(checked("パブリックチャットの発言")).toBe("false");
  expect(checked("自分へのリポスト")).toBe("true");

  // もう一度押すと表示に戻り、隠すものが無くなったカラムはキーごと消す
  await user.click(screen.getByRole("menuitemcheckbox", { name: "自分へのリアクション" }));
  await user.click(screen.getByRole("menuitemcheckbox", { name: "未読のメッセージ" }));
  await user.click(screen.getByRole("menuitemcheckbox", { name: "パブリックチャットの発言" }));
  expect(JSON.parse(localStorage.getItem(FEED_CAT_HIDDEN_KEY) ?? "null")).toEqual({});
  second.unmount();

  // 他の種類のカラムには出さない
  for (const spec of [hashtag, notif, DM]) {
    const other = renderWithRouter(<DeckColumn spec={spec} showHeader />);
    await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
    expect(screen.getByRole("menu")).toBeInTheDocument();
    expect(screen.queryByRole("menuitemcheckbox")).not.toBeInTheDocument();
    expect(screen.queryByText("タイムラインに混ぜる表示")).not.toBeInTheDocument();
    other.unmount();
  }
});

it("「カラムを削除」でカラムが消える", async () => {
  const user = userEvent.setup();
  renderWithRouter(<DeckColumn spec={DM} showHeader />);
  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
  await user.click(screen.getByRole("menuitem", { name: /カラムを削除/ }));

  expect(useDeck.getState().columns.map((c) => c.id)).toEqual(["c_following", "c_hashtag", "c_notif"]);
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
});

it("ふぁぼ欄の行は「あなたがリアクション」の 1 行で、投稿全体（「返信」ボタン）は出さない（#459）", async () => {
  // jsdom に ResizeObserver が無い（Virtuoso が使う。寸法は VirtuosoMockContext が与える）
  if (typeof globalThis.ResizeObserver !== "function") {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
  const FAVS: ColumnSpec = {
    id: "c_favs",
    title: "ふぁぼ欄",
    subtitle: "自分のリアクション",
    kind: "FAVS",
    renderer: "FEED",
    filter: { ...DEFAULT_COLUMNS[0].filter, kinds: [7] },
    pinned: false,
    order: 4,
  };
  const target = finalizeEvent(
    { kind: 1, created_at: unixNow(), tags: [], content: "ふぁぼった投稿" },
    generateSecretKey(),
  );
  eventStore.add(target);
  const reaction = finalizeEvent(
    {
      kind: 7,
      created_at: unixNow(),
      tags: [
        ["e", target.id],
        ["p", target.pubkey],
      ],
      content: "+",
    },
    generateSecretKey(),
  );
  const original = vi.mocked(useColumnFeed).getMockImplementation();
  vi.mocked(useColumnFeed).mockImplementation(() => ({
    mode: "column",
    loading: false,
    events: [reaction],
    rows: null,
    loadingOlder: false,
    loadOlder: () => {},
    refresh: () => {},
  }));
  try {
    renderWithRouter(
      <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
        <DeckColumn spec={FAVS} showHeader />
      </VirtuosoMockContext.Provider>,
    );
    expect(await screen.findByText("あなたがリアクション")).toBeInTheDocument();
    expect(screen.getByText(/: ふぁぼった投稿$/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "返信" })).toBeNull();
  } finally {
    if (original) vi.mocked(useColumnFeed).mockImplementation(original);
  }
});

it("通知カラムは種別（filter.kinds）に関わらずリアクション・Zap・リポストの行も出す（#460）", async () => {
  if (typeof globalThis.ResizeObserver !== "function") {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
  const meKey = generateSecretKey();
  const me = getPublicKey(meKey);
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  const target = finalizeEvent(
    { kind: 1, created_at: unixNow() - 600, tags: [], content: "自分の投稿" },
    meKey,
  );
  eventStore.add(target);
  const tags = [
    ["e", target.id],
    ["p", me],
  ];
  const reaction = finalizeEvent(
    { kind: 7, created_at: unixNow() - 100, tags, content: "+" },
    generateSecretKey(),
  );
  const zap = finalizeEvent(
    {
      kind: 9735,
      created_at: unixNow() - 200,
      tags: [...tags, ["description", JSON.stringify({ kind: 9734, tags: [["amount", "21000"]] })]],
      content: "",
    },
    generateSecretKey(),
  );
  const repost = finalizeEvent(
    { kind: 6, created_at: unixNow() - 300, tags, content: "" },
    generateSecretKey(),
  );
  const [, , notif] = DEFAULT_COLUMNS;
  const original = vi.mocked(useColumnFeed).getMockImplementation();
  vi.mocked(useColumnFeed).mockImplementation(() => ({
    mode: "column",
    loading: false,
    events: [reaction, zap, repost],
    rows: null,
    loadingOlder: false,
    loadOlder: () => {},
    refresh: () => {},
  }));
  try {
    renderWithRouter(
      <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
        <DeckColumn spec={{ ...notif, filter: { ...notif.filter, kinds: [1, 7, 9735] } }} showHeader />
      </VirtuosoMockContext.Provider>,
    );
    expect(await screen.findByRole("img", { name: "リアクション ❤️" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Zap" })).toBeInTheDocument();
    expect(screen.getByText("⚡ 21")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "リポスト" })).toBeInTheDocument();
  } finally {
    if (original) vi.mocked(useColumnFeed).mockImplementation(original);
    useSession.setState({ status: "loading", method: null, pubkey: null });
  }
});

it("フォロー中カラムは混ぜた行を描く（投稿・通知の行・ふぁぼ欄の行・DM の行）（#522）", async () => {
  if (typeof globalThis.ResizeObserver !== "function") {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
  const meKey = generateSecretKey();
  const me = getPublicKey(meKey);
  const myPost = finalizeEvent(
    { kind: 1, created_at: unixNow() - 600, tags: [], content: "自分の投稿" },
    meKey,
  );
  const other = finalizeEvent(
    { kind: 1, created_at: unixNow() - 500, tags: [], content: "ふぁぼった投稿" },
    generateSecretKey(),
  );
  eventStore.add(myPost);
  eventStore.add(other);
  const post = finalizeEvent(
    { kind: 1, created_at: unixNow() - 50, tags: [], content: "フォロー先の投稿" },
    generateSecretKey(),
  );
  const reaction = finalizeEvent(
    {
      kind: 7,
      created_at: unixNow() - 100,
      tags: [
        ["e", myPost.id],
        ["p", me],
      ],
      content: "+",
    },
    generateSecretKey(),
  );
  const myReaction = finalizeEvent(
    {
      kind: 7,
      created_at: unixNow() - 200,
      tags: [
        ["e", other.id],
        ["p", other.pubkey],
      ],
      content: "+",
    },
    meKey,
  );
  const notice = toNotification(reaction);
  if (!notice) throw new Error("not a notification");
  const [dm] = dmNotices([
    {
      peer: OTHER_PUBKEY,
      last: {
        owner: me,
        id: "m1",
        peer: OTHER_PUBKEY,
        sender: OTHER_PUBKEY,
        content: "本文",
        tags: [],
        createdAt: unixNow() - 300,
        proto: "nip17",
      },
      unread: 1,
      lastIncomingAt: unixNow() - 300,
    },
  ]);
  const original = vi.mocked(useColumnFeed).getMockImplementation();
  vi.mocked(useColumnFeed).mockImplementation(() => ({
    mode: "following",
    loading: false,
    events: [post],
    rows: [
      { type: "post", id: post.id, at: post.created_at, event: post },
      { type: "notice", id: notice.id, at: notice.createdAt, item: notice },
      { type: "myReaction", id: myReaction.id, at: myReaction.created_at, reaction: myReaction },
      { type: "notice", id: dm.id, at: dm.createdAt, item: dm },
    ],
    loadingOlder: false,
    loadOlder: () => {},
    refresh: () => {},
  }));
  try {
    const { container } = renderWithRouter(
      <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
        <DeckColumn spec={DEFAULT_COLUMNS[0]} showHeader />
      </VirtuosoMockContext.Provider>,
    );
    expect(await screen.findByText("フォロー先の投稿")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "リアクション ❤️" })).toBeInTheDocument();
    expect(await screen.findByText("あなたがリアクション")).toBeInTheDocument();
    expect(screen.getByText("メッセージが届きました")).toBeInTheDocument();
    const kinds = [...container.querySelectorAll("article[data-kind]")].map((row) =>
      row.getAttribute("data-kind"),
    );
    expect(kinds).toEqual(["reaction", "dm"]);
  } finally {
    if (original) vi.mocked(useColumnFeed).mockImplementation(original);
  }
});

it("PROFILE カラムは上部にカード（アバター・名前・npub・フォローボタン）を出す（#530）", async () => {
  const user = userEvent.setup();
  useSession.setState({ status: "in", method: "nip07", pubkey: PUBKEY });
  const targetKey = generateSecretKey();
  const target = getPublicKey(targetKey);
  eventStore.add(
    finalizeEvent(
      { kind: 0, created_at: 1_000, tags: [], content: JSON.stringify({ name: "Alice" }) },
      targetKey,
    ),
  );
  const spec = buildColumn("PROFILE", { text: target }, new Set(), unixNow());
  if (!spec) throw new Error("buildColumn returned null");
  try {
    renderWithRouter(
      <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
        <DeckColumn spec={spec} showHeader />
      </VirtuosoMockContext.Provider>,
    );
    // ヘッダタイトルとカードの名前、両方に「Alice」が出る（#590）
    expect(screen.getAllByText("Alice").length).toBe(2);
    const npub = npubEncode(target);
    expect(screen.getByText(`${npub.slice(0, 20)}…${npub.slice(-6)}`)).toBeInTheDocument();

    const button = screen.getByRole("button", { name: "フォロー" });
    await user.click(button);
    expect(vi.mocked(toggleFollow)).toHaveBeenCalledWith(PUBKEY, target, "follow");
  } finally {
    useSession.setState({ status: "loading", method: null, pubkey: null });
  }
});

it("PROFILE カラムの上部カードは投稿と同じスクロール領域に入る（一覧の先頭。固定表示ではない。#530 修正）", () => {
  const targetKey = generateSecretKey();
  const target = getPublicKey(targetKey);
  eventStore.add(
    finalizeEvent(
      { kind: 0, created_at: 1_000, tags: [], content: JSON.stringify({ name: "Alice" }) },
      targetKey,
    ),
  );
  const post = finalizeEvent({ kind: 1, created_at: 900, tags: [], content: "ここに投稿" }, targetKey);
  const spec = buildColumn("PROFILE", { text: target }, new Set(), unixNow());
  if (!spec) throw new Error("buildColumn returned null");
  const original = vi.mocked(useColumnFeed).getMockImplementation();
  vi.mocked(useColumnFeed).mockImplementation(() => ({
    mode: "column",
    loading: false,
    events: [post],
    rows: null,
    loadingOlder: false,
    loadOlder: () => {},
    refresh: () => {},
  }));
  try {
    const { container } = renderWithRouter(
      <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
        <DeckColumn spec={spec} showHeader />
      </VirtuosoMockContext.Provider>,
    );
    // カードは Virtuoso のスクローラの中（= 投稿と同じスクロール領域）にある
    const scroller = container.querySelector<HTMLElement>('[data-testid="virtuoso-scroller"]');
    if (!scroller) throw new Error("scroller が無い");
    const npub = npubEncode(target);
    expect(within(scroller).getByText(`${npub.slice(0, 20)}…${npub.slice(-6)}`)).toBeInTheDocument();
    expect(within(scroller).getByText("ここに投稿")).toBeInTheDocument();
  } finally {
    if (original) vi.mocked(useColumnFeed).mockImplementation(original);
  }
});

it("PROFILE 以外のカラムには上部カードを出さない（#530 修正）", () => {
  const [, hashtag] = DEFAULT_COLUMNS;
  renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      <DeckColumn spec={hashtag} showHeader />
    </VirtuosoMockContext.Provider>,
  );
  expect(screen.queryByRole("button", { name: "フォロー" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "画像を表示" })).not.toBeInTheDocument();
});

it("PROFILE カラムの上部カードは固定投稿（#531。その人の kind:10001）も出す（ネイティブと同じ位置・件数）", () => {
  const targetKey = generateSecretKey();
  const target = getPublicKey(targetKey);
  eventStore.add(
    finalizeEvent(
      { kind: 0, created_at: 1_000, tags: [], content: JSON.stringify({ name: "Alice" }) },
      targetKey,
    ),
  );
  const pinned = finalizeEvent({ kind: 1, created_at: 500, tags: [], content: "固定されたやつ" }, targetKey);
  eventStore.add(pinned);
  eventStore.add(
    finalizeEvent({ kind: 10001, created_at: 2_000, tags: [["e", pinned.id]], content: "" }, targetKey),
  );
  const spec = buildColumn("PROFILE", { text: target }, new Set(), unixNow());
  if (!spec) throw new Error("buildColumn returned null");
  renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      <DeckColumn spec={spec} showHeader />
    </VirtuosoMockContext.Provider>,
  );
  expect(screen.getByText("📌")).toBeInTheDocument();
  expect(screen.getByText("固定された投稿")).toBeInTheDocument();
  expect(screen.getByText("固定されたやつ")).toBeInTheDocument();
});

it("PROFILE カラムはプロフィール（kind:0）が入るとヘッダタイトルが名前になる（ネイティブ ProfileColumn.kt:66-71。#590）", () => {
  const targetKey = generateSecretKey();
  const target = getPublicKey(targetKey);
  eventStore.add(
    finalizeEvent(
      { kind: 0, created_at: 1_000, tags: [], content: JSON.stringify({ name: "Alice" }) },
      targetKey,
    ),
  );
  const spec = buildColumn("PROFILE", { text: target }, new Set(), unixNow());
  if (!spec) throw new Error("buildColumn returned null");
  renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      <DeckColumn spec={spec} showHeader />
    </VirtuosoMockContext.Provider>,
  );
  expect(screen.getByRole("heading", { name: "Alice" })).toBeInTheDocument();
  expect(screen.getByText("プロフィール")).toBeInTheDocument();
});

it("PROFILE カラムはプロフィールが未取得なら spec.title のままヘッダに出す（#590）", () => {
  const targetKey = generateSecretKey();
  const target = getPublicKey(targetKey);
  const spec = buildColumn("PROFILE", { text: target }, new Set(), unixNow());
  if (!spec) throw new Error("buildColumn returned null");
  renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      <DeckColumn spec={spec} showHeader />
    </VirtuosoMockContext.Provider>,
  );
  expect(screen.getByRole("heading", { name: spec.title })).toBeInTheDocument();
});
