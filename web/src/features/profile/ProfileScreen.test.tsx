import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { decode, npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import type { ReactElement } from "react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { VirtuosoMockContext } from "react-virtuoso";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { clearViewport, mockViewport } from "../../test/viewport";
import { useToast } from "../../ui/toast";
import { reportUser } from "../actions/reactions";
import { EMPTY_MUTE_MATCHER, useMute } from "../mute/muteList";
import { muteUser, unmuteUser } from "../mute/muteSync";
import { FollowError, toggleFollow } from "./follow";
import type { FollowersState } from "./followers";
import { useFollowers } from "./followers";
import type { Nip51Set } from "./nip51";
import { ProfileScreen } from "./ProfileScreen";
import profileScreenStyles from "./ProfileScreen.module.css";
import { useContactsOf } from "./useContactsOf";
import { useProfileFeed } from "./useProfileFeed";
import { useProfileLists } from "./useProfileLists";

// 自分の kind:3 の購読（useFollows）はリレーに繋がない。それ以外はテスト用のオフライン WebSocket のまま
vi.mock("../../nostr/pool", async (importOriginal) => {
  const { Subject } = await import("rxjs");
  return {
    ...(await importOriginal<typeof import("../../nostr/pool")>()),
    subscribe: vi.fn(() => new Subject<"EOSE">()),
  };
});

vi.mock("./useProfileFeed", () => ({
  useProfileFeed: vi.fn(() => ({ loading: false, posts: [], media: [], articles: [], refresh: vi.fn() })),
}));

vi.mock("./useContactsOf", () => ({ useContactsOf: vi.fn(() => null) }));

vi.mock("./follow", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./follow")>()),
  toggleFollow: vi.fn(async () => "done"),
}));

// フォロワー集計は REQ を出さない（state はテストから直接差し込む）
vi.mock("./followers", () => ({ useFollowers: vi.fn() }));

// リストタブは REQ を出さない（sets はテストから直接差し込む）
vi.mock("./useProfileLists", () => ({ useProfileLists: vi.fn(() => ({ loading: false, sets: [] })) }));

// ミュートの発行・通報は署名・送信しない
vi.mock("../mute/muteSync", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../mute/muteSync")>()),
  muteUser: vi.fn(async () => "done"),
  unmuteUser: vi.fn(async () => "done"),
}));
vi.mock("../actions/reactions", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../actions/reactions")>()),
  reportUser: vi.fn(async () => {}),
}));

const BANNER = "https://img.test/banner.jpg";
const PICTURE = "https://img.test/alice.png";

let themKey: Uint8Array;
let them: string;
let meKey: Uint8Array;
let me: string;
let writeText: ReturnType<typeof vi.fn>;

beforeAll(() => {
  // jsdom に ResizeObserver が無い（Virtuoso が使う。寸法は VirtuosoMockContext が与える）
  if (typeof globalThis.ResizeObserver !== "function") {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
  // jsdom の版によっては showModal が無い
  if (typeof HTMLDialogElement.prototype.showModal !== "function") {
    HTMLDialogElement.prototype.showModal = function () {
      this.open = true;
    };
  }
});

beforeEach(() => {
  themKey = generateSecretKey();
  them = getPublicKey(themKey);
  meKey = generateSecretKey();
  me = getPublicKey(meKey);
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  // NIP-05 の検証は外へ出さない（確認中のまま）
  vi.stubGlobal(
    "fetch",
    vi.fn(() => new Promise<Response>(() => {})),
  );
  mockClipboard();
  vi.mocked(toggleFollow).mockReset();
  vi.mocked(toggleFollow).mockResolvedValue("done");
  vi.mocked(useContactsOf).mockReturnValue(null);
  vi.mocked(useProfileFeed).mockReturnValue({
    loading: false,
    posts: [],
    media: [],
    articles: [],
    refresh: vi.fn(),
  });
  vi.mocked(useFollowers).mockReturnValue({
    followers: null,
    hasMore: false,
    loading: false,
    start: vi.fn(),
    loadMore: vi.fn(),
  });
  vi.mocked(useProfileLists).mockReturnValue({ loading: false, sets: [] });
  vi.mocked(muteUser).mockReset();
  vi.mocked(muteUser).mockResolvedValue("done");
  vi.mocked(unmuteUser).mockReset();
  vi.mocked(unmuteUser).mockResolvedValue("done");
  vi.mocked(reportUser).mockReset();
  vi.mocked(reportUser).mockResolvedValue(undefined);
  useMute.setState({ matcher: EMPTY_MUTE_MATCHER, list: null });
  useToast.setState({ queue: [] });
  mockViewport(400);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  clearViewport();
  Reflect.deleteProperty(navigator, "clipboard");
  useSession.setState({ status: "loading", method: null, pubkey: null });
  useMute.setState({ matcher: EMPTY_MUTE_MATCHER, list: null });
  window.history.replaceState(null, "");
});

/** them をミュート中にする（他は空のミュートリスト） */
function muteThem() {
  useMute.setState({
    matcher: { ...EMPTY_MUTE_MATCHER, isEmpty: false, users: new Set([them]) },
    list: null,
  });
}

/** navigator.clipboard を差し替える（userEvent.setup() は自前の clipboard を入れるので、その後にも呼ぶ） */
function mockClipboard() {
  writeText = vi.fn(async () => {});
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
}

function addProfile(key: Uint8Array, content: Record<string, unknown>, tags: string[][] = []) {
  eventStore.add(finalizeEvent({ kind: 0, created_at: 1_000, tags, content: JSON.stringify(content) }, key));
}

function addRelayList(key: Uint8Array, tags: string[][]) {
  eventStore.add(finalizeEvent({ kind: 10002, created_at: 1_000, tags, content: "" }, key));
}

function addContacts(key: Uint8Array, follows: string[]) {
  eventStore.add(
    finalizeEvent({ kind: 3, created_at: 1_000, tags: follows.map((p) => ["p", p]), content: "" }, key),
  );
}

function note(
  key: Uint8Array,
  content: string,
  createdAt: number,
  kind = 1,
  tags: string[][] = [],
): NostrEvent {
  return finalizeEvent({ kind, created_at: createdAt, tags, content }, key);
}

/** プロフィール画面をメモリ上のルータで描く（Virtuoso は全行を描くモック） */
function renderScreen(pubkey = them, relayHints: string[] = []) {
  const ui: ReactElement = (
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      <ProfileScreen pubkey={pubkey} relayHints={relayHints} onBack={() => {}} />
    </VirtuosoMockContext.Provider>
  );
  const router = createMemoryRouter([{ path: "*", element: ui }], { initialEntries: ["/p/x"] });
  const { unmount } = render(<RouterProvider router={router} />);
  return { router, unmount };
}

function alice(extra: Record<string, unknown> = {}) {
  addProfile(themKey, { name: "Alice", picture: PICTURE, banner: BANNER, ...extra });
}

describe("レイアウト", () => {
  it("compact: 上バーの見出しが表示名、タブは 1 つ、左ペインは無い", () => {
    alice();
    renderScreen();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Alice");
    expect(screen.getAllByRole("tablist")).toHaveLength(1);
    expect(screen.queryByRole("complementary")).not.toBeInTheDocument();
  });

  it("expanded: 左ペインに「プロフィール」とヘッダカード、タブと投稿はその外", () => {
    mockViewport(1400);
    alice();
    renderScreen();
    const side = screen.getByRole("complementary", { name: "プロフィール詳細" });
    expect(within(side).getByRole("heading", { level: 1, name: "プロフィール" })).toBeInTheDocument();
    expect(within(side).getByRole("heading", { level: 2, name: "Alice" })).toBeInTheDocument();
    expect(within(side).queryByRole("tablist")).not.toBeInTheDocument();
    expect(within(side).queryByRole("tabpanel")).not.toBeInTheDocument();
    expect(screen.getByRole("tablist")).toBeInTheDocument();
    expect(screen.getByRole("tabpanel")).toBeInTheDocument();
  });
});

describe("ヘッダカード", () => {
  it("バナーは幅 900・品質 80、アバターは幅 256 のプロキシ。バナーを押すと原 URL で開く", async () => {
    const user = userEvent.setup();
    alice();
    renderScreen();

    const container = document.body;
    const banner = container.querySelector<HTMLImageElement>(`img[src*="${encodeURIComponent(BANNER)}"]`);
    expect(banner?.src).toContain("w=900");
    expect(banner?.src).toContain("q=80");
    const avatar = container.querySelector<HTMLImageElement>(`img[src*="${encodeURIComponent(PICTURE)}"]`);
    expect(avatar?.src).toContain("w=256");

    const [bannerButton] = screen.getAllByRole("button", { name: "画像を表示" });
    await user.click(bannerButton);
    const dialog = screen.getByRole("dialog", { name: "画像" });
    expect(dialog.querySelector("img")?.getAttribute("src")).toBe(BANNER);
  });

  it("名前は h2、NIP-05 の文字、npub は先頭 20 + … + 末尾 6。コピーするとトーストで知らせる（P6）", async () => {
    alice({ nip05: "alice@example.com" });
    renderScreen();

    expect(screen.getByRole("heading", { level: 2, name: "Alice" })).toBeInTheDocument();
    expect(screen.getByText("alice@example.com")).toBeInTheDocument();
    const npub = npubEncode(them);
    expect(screen.getByText(`${npub.slice(0, 20)}…${npub.slice(-6)}`)).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "npub をコピー" }));
    });
    expect(writeText).toHaveBeenCalledWith(npub);
    expect(useToast.getState().queue).toEqual(["npub をコピーしました"]);
  });

  it("⋯ メニュー: nprofile（リレーは kind:10002 の先頭 3 件）と njump のリンクをコピーする", async () => {
    const user = userEvent.setup();
    mockClipboard();
    alice();
    addRelayList(themKey, [
      ["r", "wss://relay.one"],
      ["r", "wss://relay.two/", "read"],
      ["r", "wss://relay.three", "write"],
      ["r", "wss://relay.four"],
    ]);
    renderScreen();

    await user.click(screen.getByRole("button", { name: "メニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "nprofile をコピー" }));
    const nprofile = writeText.mock.calls[0][0] as string;
    const decoded = decode(nprofile);
    expect(decoded.type).toBe("nprofile");
    expect(decoded.data).toEqual({
      pubkey: them,
      relays: ["wss://relay.one", "wss://relay.two", "wss://relay.three"],
    });
    expect(useToast.getState().queue).toEqual(["nprofile をコピーしました"]);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "メニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "リンクをコピー（njump）" }));
    expect(writeText).toHaveBeenLastCalledWith(`https://njump.me/${nprofile}`);
    expect(useToast.getState().queue).toEqual(["nprofile をコピーしました", "リンクをコピーしました"]);
  });

  it("自己紹介の URL・#タグ・メンションはリンク（画像 URL もリンクのまま）、lud16 と website", () => {
    const mentioned = npubEncode(getPublicKey(generateSecretKey()));
    alice({
      about: `見て https://x.co と #nostr と nostr:${mentioned} https://i.test/1.jpg`,
      lud16: "alice@getalby.com",
      website: "https://alice.example",
    });
    renderScreen();

    for (const href of ["https://x.co", "https://i.test/1.jpg"]) {
      const link = screen.getByRole("link", { name: href });
      expect(link).toHaveAttribute("href", href);
      expect(link).toHaveAttribute("target", "_blank");
    }
    expect(screen.getByRole("link", { name: "#nostr" })).toHaveAttribute("href", "/t/nostr");
    const mention = screen.getAllByRole("link").find((a) => a.getAttribute("href") === `/p/${mentioned}`);
    expect(mention).toBeDefined();
    expect(screen.getByText("⚡ alice@getalby.com")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "https://alice.example" })).toHaveAttribute(
      "href",
      "https://alice.example",
    );
  });

  it("スキームの無い website は文字のまま", () => {
    alice({ website: "alice.example" });
    renderScreen();
    expect(screen.getByText("alice.example")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "alice.example" })).not.toBeInTheDocument();
  });

  it("他人の lud16 を押すとプロフィール Zap のダイアログ", async () => {
    const user = userEvent.setup();
    alice({ lud16: "alice@getalby.com" });
    renderScreen();
    await user.click(screen.getByRole("button", { name: "⚡ alice@getalby.com" }));
    const dialog = screen.getByRole("dialog", { name: "⚡ Zap" });
    expect(within(dialog).getByText(/^Alice へ投げ銭します/)).toBeInTheDocument();
    expect(within(dialog).getByText("送信先: alice@getalby.com")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "キャンセル" }));
    expect(screen.queryByRole("dialog", { name: "⚡ Zap" })).not.toBeInTheDocument();
  });

  it("自分の lud16 は文字だけ（押せない）", () => {
    addProfile(meKey, { name: "Me", lud16: "me@getalby.com" });
    renderScreen(me);
    expect(screen.getByText("⚡ me@getalby.com")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "⚡ me@getalby.com" })).not.toBeInTheDocument();
  });

  it("他人で lud16 があれば ⋯ の左に丸い ⚡ ボタンを出す（#592。ネイティブ ProfileScreen.kt:715-719）", async () => {
    const user = userEvent.setup();
    alice({ lud16: "alice@getalby.com" });
    renderScreen();
    await user.click(screen.getByRole("button", { name: "Zap" }));
    const dialog = screen.getByRole("dialog", { name: "⚡ Zap" });
    expect(within(dialog).getByText("送信先: alice@getalby.com")).toBeInTheDocument();
  });

  it("lud16 が無ければ丸い ⚡ ボタンを出さない（#592）", () => {
    alice();
    renderScreen();
    expect(screen.queryByRole("button", { name: "Zap" })).not.toBeInTheDocument();
  });

  it("自分のプロフィールでは lud16 があっても丸い ⚡ ボタンを出さない（#592）", () => {
    addProfile(meKey, { name: "Me", lud16: "me@getalby.com" });
    renderScreen(me);
    expect(screen.queryByRole("button", { name: "Zap" })).not.toBeInTheDocument();
  });

  it("使用リレー: 押すと URL（wss:// と末尾 / 無し）と read / write。kind:10002 が無ければ出さない", async () => {
    const user = userEvent.setup();
    alice();
    addRelayList(themKey, [
      ["r", "wss://relay.one"],
      ["r", "wss://relay.two/", "read"],
    ]);
    renderScreen();

    const toggle = screen.getByRole("button", { name: /使用リレー \(2\)/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await user.click(toggle);
    const rows = screen.getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("relay.one");
    expect(rows[0]).toHaveTextContent("read · write");
    expect(rows[1]).toHaveTextContent("relay.two");
    expect(rows[1]).not.toHaveTextContent("write");
  });

  it("kind:10002 が無ければ使用リレーの項目自体が無い", () => {
    alice();
    renderScreen();
    expect(screen.queryByRole("button", { name: /使用リレー/ })).not.toBeInTheDocument();
  });
});

describe("フォロー", () => {
  it("未フォローは「フォロー」。押すと follow し、終わるまで押せない", async () => {
    const user = userEvent.setup();
    let finish: (value: "done") => void = () => {};
    vi.mocked(toggleFollow).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    alice();
    renderScreen();

    const button = screen.getByRole("button", { name: "フォロー" });
    expect(button).toHaveAttribute("aria-pressed", "false");
    await user.click(button);
    expect(vi.mocked(toggleFollow)).toHaveBeenCalledWith(me, them, "follow");
    expect(button).toBeDisabled();

    await act(async () => finish("done"));
    expect(button).toBeEnabled();
  });

  it("自分の kind:3 に相手がいれば「フォロー中」。押すと unfollow", async () => {
    const user = userEvent.setup();
    alice();
    addContacts(meKey, [them]);
    renderScreen();

    const button = screen.getByRole("button", { name: "フォロー中" });
    expect(button).toHaveAttribute("aria-pressed", "true");
    await user.click(button);
    expect(vi.mocked(toggleFollow)).toHaveBeenCalledWith(me, them, "unfollow");
  });

  it("自分のフォローリストが取れなければその旨、その他の失敗は「更新できませんでした」", async () => {
    const user = userEvent.setup();
    alice();
    renderScreen();

    vi.mocked(toggleFollow).mockRejectedValueOnce(new FollowError("no-contacts"));
    await user.click(screen.getByRole("button", { name: "フォロー" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "フォローリストを取得できませんでした。通信状態を確認してもう一度お試しください",
    );

    vi.mocked(toggleFollow).mockRejectedValueOnce(new FollowError("sign-failed"));
    await user.click(screen.getByRole("button", { name: "フォロー" }));
    expect(screen.getByRole("alert")).toHaveTextContent("フォローを更新できませんでした");
  });

  it("相手のフォローに自分がいれば「フォローされています」。件数を押すと一覧、「戻る」でプロフィール", async () => {
    const user = userEvent.setup();
    alice();
    const other = getPublicKey(generateSecretKey());
    vi.mocked(useContactsOf).mockReturnValue([me, other]);
    renderScreen();

    expect(screen.getByText("フォローされています")).toBeInTheDocument();
    const count = screen.getByRole("button", { name: /フォロー中/ });
    expect(count).toHaveTextContent("2");
    await user.click(count);

    expect(screen.getByRole("heading", { name: "フォロー中" })).toBeInTheDocument();
    const links = screen.getAllByRole("link");
    expect(links.map((a) => a.getAttribute("href"))).toEqual([
      `/p/${npubEncode(me)}`,
      `/p/${npubEncode(other)}`,
    ]);

    await user.click(screen.getByRole("button", { name: "戻る" }));
    expect(screen.getByRole("heading", { level: 2, name: "Alice" })).toBeInTheDocument();
  });

  it("自分のプロフィール: 「編集」があり「フォロー」は無い。押すと設定のプロフィール編集を積む（戻るでプロフィールへ。#807）", async () => {
    const user = userEvent.setup();
    addProfile(meKey, { name: "Me" });
    const { router } = renderScreen(me);

    expect(screen.queryByRole("button", { name: "フォロー" })).not.toBeInTheDocument();
    expect(vi.mocked(useContactsOf)).toHaveBeenCalledWith(null);
    await user.click(screen.getByRole("button", { name: "編集" }));
    expect(router.state.location.pathname).toBe("/settings/profile-edit");
    expect(router.state.historyAction).toBe("PUSH");
    expect(router.state.location.state).toEqual({ settingsFromOutside: true });
  });
});

describe("タブ", () => {
  it("投稿は kind 1 と リポスト、メディアは画像のある投稿だけ", async () => {
    const user = userEvent.setup();
    alice();
    const otherKey = generateSecretKey();
    const original = note(otherKey, "元の投稿です", 900);
    eventStore.add(original);
    addProfile(otherKey, { name: "Bob" });
    const text = note(themKey, "ふつうの投稿", 1_000);
    const repost = note(themKey, "", 1_001, 6, [["e", original.id]]);
    const photo = note(themKey, "写真 https://img.test/p.jpg", 1_002);
    vi.mocked(useProfileFeed).mockReturnValue({
      loading: false,
      posts: [photo, repost, text],
      media: [photo],
      articles: [],
      refresh: vi.fn(),
    });
    renderScreen();

    const posts = screen.getByRole("tab", { name: "投稿" });
    expect(posts).toHaveAttribute("aria-selected", "true");
    const panel = screen.getByRole("tabpanel");
    expect(within(panel).getAllByRole("article")).toHaveLength(3);
    expect(within(panel).getByText("ふつうの投稿")).toBeInTheDocument();
    expect(within(panel).getByText("元の投稿です")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "メディア" }));
    expect(screen.getByRole("tab", { name: "メディア" })).toHaveAttribute("aria-selected", "true");
    const media = within(screen.getByRole("tabpanel")).getAllByRole("article");
    expect(media).toHaveLength(1);
    expect(media[0]).toHaveTextContent("写真");
  });

  it("記事タブは本人の kind:30023 を新しい順にカードで出す。0 件でもタブは出る（#534）", async () => {
    const user = userEvent.setup();
    alice();
    const older = finalizeEvent(
      {
        kind: 30023,
        created_at: 1_000,
        tags: [
          ["d", "a"],
          ["title", "古い記事"],
        ],
        content: "",
      },
      themKey,
    );
    const newer = finalizeEvent(
      {
        kind: 30023,
        created_at: 2_000,
        tags: [
          ["d", "b"],
          ["title", "新しい記事"],
        ],
        content: "",
      },
      themKey,
    );
    vi.mocked(useProfileFeed).mockReturnValue({
      loading: false,
      posts: [],
      media: [],
      articles: [newer, older],
      refresh: vi.fn(),
    });
    renderScreen();

    expect(screen.getByRole("tab", { name: "記事" })).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "記事" }));

    const panel = screen.getByRole("tabpanel");
    const links = within(panel).getAllByRole("link");
    expect(links.map((l) => l.textContent)).toEqual([
      expect.stringContaining("新しい記事"),
      expect.stringContaining("古い記事"),
    ]);
    expect(links[0].getAttribute("href")).toMatch(/^\/e\/naddr1/);
  });

  it("記事が 0 件なら「まだ記事がありません」", async () => {
    const user = userEvent.setup();
    alice();
    vi.mocked(useProfileFeed).mockReturnValue({
      loading: false,
      posts: [],
      media: [],
      articles: [],
      refresh: vi.fn(),
    });
    renderScreen();

    await user.click(screen.getByRole("tab", { name: "記事" }));
    expect(screen.getByText("まだ記事がありません")).toBeInTheDocument();
  });

  it("空なら「まだ投稿がありません」、読み込み中は「読み込み中…」", () => {
    alice();
    vi.mocked(useProfileFeed).mockReturnValue({
      loading: true,
      posts: [],
      media: [],
      articles: [],
      refresh: vi.fn(),
    });
    const { unmount } = renderScreen();
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
    unmount();

    vi.mocked(useProfileFeed).mockReturnValue({
      loading: false,
      posts: [],
      media: [],
      articles: [],
      refresh: vi.fn(),
    });
    renderScreen();
    expect(screen.getByText("まだ投稿がありません")).toBeInTheDocument();
  });
});

describe("固定投稿（#531。その人の kind:10001）", () => {
  it("投稿タブの先頭に「📌 固定された投稿」付きで出す。メディアタブには出さない", async () => {
    const user = userEvent.setup();
    alice();
    const pinned = note(themKey, "固定されたやつ", 500);
    eventStore.add(pinned);
    eventStore.add(
      finalizeEvent({ kind: 10001, created_at: 2_000, tags: [["e", pinned.id]], content: "" }, themKey),
    );
    const text = note(themKey, "ふつうの投稿", 1_000);
    const photo = note(themKey, "写真 https://img.test/p.jpg", 1_002);
    vi.mocked(useProfileFeed).mockReturnValue({
      loading: false,
      posts: [text],
      media: [photo],
      articles: [],
      refresh: vi.fn(),
    });
    renderScreen();

    const panel = screen.getByRole("tabpanel");
    expect(within(panel).getByText("📌")).toBeInTheDocument();
    expect(within(panel).getByText("固定された投稿")).toBeInTheDocument();
    const articles = within(panel).getAllByRole("article");
    expect(articles).toHaveLength(2);
    expect(articles[0]).toHaveTextContent("固定されたやつ");
    expect(articles[1]).toHaveTextContent("ふつうの投稿");

    await user.click(screen.getByRole("tab", { name: "メディア" }));
    const mediaPanel = screen.getByRole("tabpanel");
    expect(within(mediaPanel).queryByText("固定された投稿")).toBeNull();
  });

  it("固定投稿が無ければ見出しも出さない", () => {
    alice();
    renderScreen();
    expect(screen.queryByText("固定された投稿")).toBeNull();
  });
});

describe("フォロワー", () => {
  function followersState(overrides: Partial<FollowersState> = {}) {
    return { followers: null, hasMore: false, loading: false, ...overrides };
  }

  it("「フォロワーを確認」を押すまで集計しない。押すと start() を呼びフォロワー一覧に置き換わる", async () => {
    const user = userEvent.setup();
    const start = vi.fn();
    vi.mocked(useFollowers).mockReturnValue({ ...followersState(), start, loadMore: vi.fn() });
    alice();
    renderScreen();

    expect(start).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "フォロワーを確認" }));
    expect(start).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("heading", { name: "フォロワー" })).toBeInTheDocument();
    expect(
      screen.getByText("リレーで観測できた範囲のみ表示しています（全数ではありません）"),
    ).toBeInTheDocument();
    expect(screen.getByText("集計中…")).toBeInTheDocument();
  });

  it("件数が増えていれば行と「さらに読み込む」を出す。押すと loadMore、「戻る」でプロフィールに戻る", async () => {
    const user = userEvent.setup();
    const loadMore = vi.fn();
    const f1 = getPublicKey(generateSecretKey());
    const f2 = getPublicKey(generateSecretKey());
    vi.mocked(useFollowers).mockReturnValue({
      ...followersState({ followers: [f1, f2], hasMore: true }),
      start: vi.fn(),
      loadMore,
    });
    alice();
    renderScreen();

    await user.click(screen.getByRole("button", { name: "フォロワーを確認" }));
    const links = screen.getAllByRole("link");
    expect(links.map((a) => a.getAttribute("href"))).toEqual([
      `/p/${npubEncode(f1)}`,
      `/p/${npubEncode(f2)}`,
    ]);

    await user.click(screen.getByRole("button", { name: "さらに読み込む" }));
    expect(loadMore).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "戻る" }));
    expect(screen.getByRole("heading", { level: 2, name: "Alice" })).toBeInTheDocument();
  });

  it("増えなければ「さらに読み込む」を出さない。0 件なら「見つかりませんでした」", async () => {
    const user = userEvent.setup();
    vi.mocked(useFollowers).mockReturnValue({
      ...followersState({ followers: [] }),
      start: vi.fn(),
      loadMore: vi.fn(),
    });
    alice();
    renderScreen();
    await user.click(screen.getByRole("button", { name: "フォロワーを確認" }));
    expect(screen.getByText("見つかりませんでした")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "さらに読み込む" })).not.toBeInTheDocument();
  });
});

describe("ミュート / 通報", () => {
  it("ミュート中は名前の横に「ミュート中」、⋯ には「ミュートを解除」だけ出る", async () => {
    const user = userEvent.setup();
    alice();
    muteThem();
    renderScreen();
    expect(screen.getByText("ミュート中")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "メニュー" }));
    expect(screen.getByRole("menuitem", { name: "ミュートを解除" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "ミュート" })).not.toBeInTheDocument();
  });

  it("「ミュート」は確認してから muteUser を呼ぶ（ネイティブと同じ文言。P3）", async () => {
    const user = userEvent.setup();
    alice();
    renderScreen();
    await user.click(screen.getByRole("button", { name: "メニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "ミュート" }));
    expect(vi.mocked(muteUser)).not.toHaveBeenCalled();
    const dialog = screen.getByRole("dialog", { name: "このユーザーをミュートしますか？" });
    await user.click(within(dialog).getByRole("button", { name: "ミュート" }));
    expect(vi.mocked(muteUser)).toHaveBeenCalledWith(me, them);
  });

  it("ミュート中の「ミュートを解除」は確認してから unmuteUser を呼ぶ", async () => {
    const user = userEvent.setup();
    alice();
    muteThem();
    renderScreen();
    await user.click(screen.getByRole("button", { name: "メニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "ミュートを解除" }));
    const dialog = screen.getByRole("dialog", { name: "ミュートを解除しますか？" });
    await user.click(within(dialog).getByRole("button", { name: "解除する" }));
    expect(vi.mocked(unmuteUser)).toHaveBeenCalledWith(me, them);
  });

  it("「通報」は理由を選ぶと reportUser を呼ぶ（e タグの無い通報。中身は reactions.test.ts。ネイティブと同じ文言。P3）", async () => {
    const user = userEvent.setup();
    alice();
    renderScreen();
    await user.click(screen.getByRole("button", { name: "メニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "通報" }));
    const dialog = screen.getByRole("dialog", { name: "このユーザーを通報" });
    await user.click(within(dialog).getByRole("button", { name: "スパム" }));
    expect(vi.mocked(reportUser)).toHaveBeenCalledWith(them, "spam");
  });

  it("自分のプロフィールにはミュート・通報の項目が無い", async () => {
    const user = userEvent.setup();
    addProfile(meKey, { name: "Me" });
    renderScreen(me);
    await user.click(screen.getByRole("button", { name: "メニュー" }));
    expect(screen.queryByRole("menuitem", { name: /ミュート/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "通報" })).not.toBeInTheDocument();
  });
});

describe("リストタブ", () => {
  function followSet(overrides: Partial<Nip51Set> = {}): Nip51Set {
    return {
      kind: 30000,
      author: them,
      dTag: "friends",
      title: "仲良し",
      description: "",
      image: null,
      members: [],
      eventIds: [],
      addresses: [],
      createdAt: 100,
      hasPrivate: false,
      ...overrides,
    };
  }

  it("空なら「公開されているリストはありません」", async () => {
    const user = userEvent.setup();
    alice();
    renderScreen();
    await user.click(screen.getByRole("tab", { name: "リスト" }));
    expect(screen.getByText("公開されているリストはありません")).toBeInTheDocument();
  });

  it("フォローセット: 件数・展開でメンバー一覧、「カラムで開く」で一時カラムを追加（ネイティブ buildListColumn）", async () => {
    const user = userEvent.setup();
    const m1 = getPublicKey(generateSecretKey());
    const m2 = getPublicKey(generateSecretKey());
    vi.mocked(useProfileLists).mockReturnValue({ loading: false, sets: [followSet({ members: [m1, m2] })] });
    alice();
    renderScreen();
    const before = useDeck.getState().columns.length;

    await user.click(screen.getByRole("tab", { name: "リスト" }));
    expect(screen.getByText("仲良し")).toBeInTheDocument();
    expect(screen.getByText("2 件")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /仲良し/ }));
    const links = screen.getAllByRole("link");
    expect(links.map((a) => a.getAttribute("href"))).toEqual([
      `/p/${npubEncode(m1)}`,
      `/p/${npubEncode(m2)}`,
    ]);

    await user.click(screen.getByRole("button", { name: "カラムで開く" }));
    const columns = useDeck.getState().columns;
    expect(columns).toHaveLength(before + 1);
    expect(columns.at(-1)).toMatchObject({
      title: "仲良し",
      subtitle: "list",
      kind: "LIST",
      renderer: "FEED",
      pinned: false,
      filter: expect.objectContaining({ kinds: [1, 6, 16], authors: [m1, m2] }),
    });
    useDeck.setState({ columns: columns.slice(0, before) });
  });

  it("ブックマークセット: 展開すると対象の投稿、非公開ありなら注記も出す", async () => {
    const user = userEvent.setup();
    const bookmarked = finalizeEvent(
      { kind: 1, created_at: 900, tags: [], content: "ブックマークした投稿" },
      generateSecretKey(),
    );
    eventStore.add(bookmarked);
    vi.mocked(useProfileLists).mockReturnValue({
      loading: false,
      sets: [
        followSet({
          kind: 30003,
          dTag: "reads",
          title: "あとで読む",
          eventIds: [bookmarked.id],
          hasPrivate: true,
        }),
      ],
    });
    alice();
    renderScreen();

    await user.click(screen.getByRole("tab", { name: "リスト" }));
    await user.click(screen.getByRole("button", { name: /あとで読む/ }));
    expect(await screen.findByText("ブックマークした投稿")).toBeInTheDocument();
    expect(
      screen.getByText("このリストには非公開の項目があります（本人以外は読めません）。"),
    ).toBeInTheDocument();
  });
});

describe("タブ・スクロール位置の復元（#401 #540）", () => {
  it("history state のタブから始まり、切り替えると history state に保存される", async () => {
    const user = userEvent.setup();
    window.history.replaceState({ usr: { profileTab: "media" } }, "");
    alice();
    vi.mocked(useProfileFeed).mockReturnValue({
      loading: false,
      posts: [],
      media: [note(themKey, "写真 https://img.test/p.jpg", 1_000)],
      articles: [],
      refresh: vi.fn(),
    });
    renderScreen();

    expect(screen.getByRole("tab", { name: "メディア" })).toHaveAttribute("aria-selected", "true");

    await user.click(screen.getByRole("tab", { name: "記事" }));
    const usr = (window.history.state as { usr: { profileTab: string } }).usr;
    expect(usr.profileTab).toBe("articles");
  });

  it("壊れた・無い history state はタブ「投稿」から始まる", () => {
    alice();
    renderScreen();
    expect(screen.getByRole("tab", { name: "投稿" })).toHaveAttribute("aria-selected", "true");
  });

  it("スクロール位置を history state に保存し、次のマウントで復元する", () => {
    vi.useFakeTimers();
    alice();
    renderScreen();

    const scroller = document.body.getElementsByClassName(profileScreenStyles.scroll)[0] as HTMLElement;
    Object.defineProperty(scroller, "scrollTop", { configurable: true, value: 240 });
    fireEvent.scroll(scroller);
    act(() => {
      vi.advanceTimersToNextFrame();
    });
    expect((window.history.state as { usr: { profileScrollY: number } }).usr.profileScrollY).toBe(240);

    renderScreen();
    const restored = document.body.getElementsByClassName(profileScreenStyles.scroll)[1] as HTMLElement;
    expect(restored.scrollTop).toBe(240);
  });
});
