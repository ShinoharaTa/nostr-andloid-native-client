import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { type ColumnSpec, DEFAULT_COLUMNS, decodeDeckColumns } from "../../lib/columns";
import { requestZapInvoice } from "../../lib/lnurl";
import { readRelays, subscribeTo } from "../../nostr/pool";
import { unsent$ } from "../../nostr/publish";
import { addVerified, eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { installDialogPolyfill } from "../../test/dialog";
import { clearViewport, mockViewport } from "../../test/viewport";
import { DeckColumn } from "../deck/DeckColumn";
import { useDmSeen } from "../dm/dmSeen";
import { useDm } from "../dm/dmStore";
import { MessagesScreen } from "../dm/MessagesScreen";
import { setMuteList } from "../mute/muteList";
import { resetChannelsForTest, useChannels } from "./channels";

// リレーへは張らない（呼ばれ方だけ見る）
vi.mock("../../nostr/pool", async (importOriginal) => {
  const { NEVER } = await import("rxjs");
  return {
    ...(await importOriginal<typeof import("../../nostr/pool")>()),
    subscribeTo: vi.fn(() => NEVER),
    subscribe: vi.fn(() => NEVER),
  };
});
// DM の購読・復号はしない
vi.mock("../dm/dmService", () => ({ startDecrypting: vi.fn(), resumeDecrypting: vi.fn() }));
// [#538] Zap の invoice は取りに行かない（呼ばれ方だけ見る）
vi.mock("../../lib/lnurl", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/lnurl")>();
  return { ...actual, requestZapInvoice: vi.fn(async () => null) };
});

const CH = "7f5475b40ce3350e161c24d7cea37ffd2c291c71e9118df5ec7395822c1f6302";
const CH2 = "1c6ffe5f48bf5f0fce9c991b34dd22e96e49ce38fab9f953820f6727fe6e6b04";
const ROOM_RELAY = "wss://room.example/";

const meKey = generateSecretKey();
const aliceKey = generateSecretKey();
const bobKey = generateSecretKey();
const ME = getPublicKey(meKey);
const ALICE = getPublicKey(aliceKey);
const BOB = getPublicKey(bobKey);

function message(
  key: Uint8Array,
  content: string,
  createdAt: number,
  extraTags: string[][] = [],
): NostrEvent {
  return finalizeEvent(
    { kind: 42, content, created_at: createdAt, tags: [["e", CH, ROOM_RELAY, "root"], ...extraTags] },
    key,
  );
}

const first = message(aliceKey, "こんにちは", 1_700_000_000);
const muted = message(bobKey, "ミュートされる発言", 1_700_000_400);
const mine = message(meKey, "わたしの発言", 1_700_000_800);
const reply = message(aliceKey, "返信です", 1_700_001_200, [
  ["e", first.id, ROOM_RELAY, "reply"],
  ["p", first.pubkey, ROOM_RELAY],
]);
const reaction = finalizeEvent(
  {
    kind: 7,
    content: "+",
    created_at: 1_700_001_300,
    tags: [
      ["e", mine.id],
      ["p", ME],
    ],
  },
  aliceKey,
);
const otherRoom = finalizeEvent(
  { kind: 42, content: "別のチャンネル", created_at: 1_700_000_500, tags: [["e", CH2, "", "root"]] },
  aliceKey,
);

/** ネイティブが NIP-78 で同期したルームカラム（DeckColumnDto の JSON） */
const SYNCED = `[{"id":"room_${CH}","title":"さびれたスナック","subtitle":"酔っ払いが問わず語り","kind":"CHANNEL_ROOM","renderer":"ROOM","filter":{"kinds":[42],"channelId":"${CH}"},"order":3}]`;

const CHANNELS = [
  {
    id: CH,
    name: "さびれたスナック",
    about: "酔っ払いが問わず語り",
    picture: null,
    relays: [ROOM_RELAY, "ws://insecure.example"],
    createdAt: 1,
    lastAt: 20,
  },
  { id: CH2, name: "comic magazine", about: "", picture: null, relays: [], createdAt: 1, lastAt: 10 },
];

const FETCHED = {
  data: [
    {
      id: CH2,
      name: "comic magazine",
      content: JSON.stringify({ name: "comic magazine" }),
      latest_update: 10,
    },
    {
      id: CH,
      name: "さびれたスナック",
      content: JSON.stringify({
        name: "さびれたスナック",
        about: "酔っ払いが問わず語り",
        relays: [ROOM_RELAY],
      }),
      latest_update: 20,
    },
  ],
};

beforeEach(() => {
  installDialogPolyfill();
  vi.mocked(subscribeTo).mockClear();
  useSession.setState({ status: "in", method: "nip07", pubkey: ME });
  useDeck.setState({ columns: structuredClone([...DEFAULT_COLUMNS]), widths: {}, revealMuted: [] });
  for (const event of [first, muted, mine, reply, reaction, otherRoom]) addVerified(event);
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(FETCHED))),
  );
});

afterEach(() => {
  clearViewport();
  setMuteList(null);
  resetChannelsForTest();
  localStorage.clear();
  vi.unstubAllGlobals();
  useSession.setState({ status: "loading", method: null, pubkey: null });
  useDm.getState().reset(null);
  useDmSeen.setState({ me: null, first: 0, peers: {} });
});

afterAll(() => {
  for (const event of [first, muted, mine, reply, reaction, otherRoom]) eventStore.remove(event.id);
});

function muteBob() {
  setMuteList({
    eventId: null,
    createdAt: 0,
    entries: [{ category: "p", value: BOB, isPublic: true, isPrivate: false }],
    locked: false,
    publicTags: [],
    privateTags: [],
    content: "",
  });
}

/** 発言の本文（DOM の順） */
function texts(): string[] {
  return screen
    .getAllByText(/^(こんにちは|ミュートされる発言|わたしの発言|返信です|別のチャンネル)$/)
    .map((e) => e.textContent ?? "");
}

describe("デッキのルームカラム", () => {
  function renderColumn(spec: ColumnSpec) {
    const router = createMemoryRouter([{ path: "*", element: <DeckColumn spec={spec} showHeader /> }]);
    render(<RouterProvider router={router} />);
  }

  it("NIP-78 で同期した CHANNEL_ROOM はルームとして描かれる（最新が上・入力欄の代わりに「✏️ メッセージを書く」）", () => {
    useChannels.setState({ channels: CHANNELS });
    const [spec] = decodeDeckColumns(SYNCED) ?? [];
    renderColumn(spec);

    expect(screen.getByRole("heading", { name: "さびれたスナック" })).toBeInTheDocument();
    expect(screen.getByText("酔っ払いが問わず語り")).toBeInTheDocument();
    expect(screen.queryByText(/まだ使えません/)).not.toBeInTheDocument();
    expect(texts()).toEqual(["返信です", "わたしの発言", "ミュートされる発言", "こんにちは"]);
    expect(screen.getByRole("button", { name: "✏️ メッセージを書く" })).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "メッセージ" })).not.toBeInTheDocument();
    // 返信は返信元を 1 行で引用する
    expect(screen.getByText(/: こんにちは$/)).toBeInTheDocument();
    // 集約リアクション
    expect(screen.getByRole("listitem", { name: "❤️ 1" })).toBeInTheDocument();
  });

  it("kind:42 #e=[channelId] limit:200 を read リレーとチャンネルのリレー（wss:// のみ）へ張る。表示中の発言へのリアクションも", () => {
    useChannels.setState({ channels: CHANNELS });
    const [spec] = decodeDeckColumns(SYNCED) ?? [];
    renderColumn(spec);

    const calls = vi.mocked(subscribeTo).mock.calls;
    const room = calls.find(([, filters]) => filters[0].kinds?.includes(42));
    expect(room?.[1]).toEqual([{ kinds: [42], "#e": [CH], limit: 200 }]);
    expect(room?.[0]).toEqual([...readRelays(), ROOM_RELAY]);
    const reactions = calls.find(([, filters]) => filters[0].kinds?.includes(7));
    expect(reactions?.[1]).toEqual([
      { kinds: [7], "#e": [reply.id, mine.id, muted.id, first.id], limit: 500 },
    ]);
  });

  it("ミュートした人の発言は出ない。⋯「ミュートを表示」で出る", async () => {
    const user = userEvent.setup();
    useChannels.setState({ channels: CHANNELS });
    muteBob();
    const [spec] = decodeDeckColumns(SYNCED) ?? [];
    useDeck.setState({ columns: [spec] });
    renderColumn(spec);
    expect(texts()).toEqual(["返信です", "わたしの発言", "こんにちは"]);

    await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "ミュートを表示" }));
    expect(texts()).toEqual(["返信です", "わたしの発言", "ミュートされる発言", "こんにちは"]);
  });

  it("「✏️ メッセージを書く」・リプライはモーダルの入力欄を開く", async () => {
    const user = userEvent.setup();
    useChannels.setState({ channels: CHANNELS });
    const [spec] = decodeDeckColumns(SYNCED) ?? [];
    renderColumn(spec);

    await user.click(screen.getByRole("button", { name: "✏️ メッセージを書く" }));
    const dialog = screen.getByRole("dialog", { name: "さびれたスナック" });
    expect(within(dialog).getByRole("textbox", { name: "メッセージ" })).toHaveAttribute(
      "placeholder",
      "メッセージを入力…",
    );
    await user.click(within(dialog).getByRole("button", { name: "閉じる" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    const firstRow = screen.getByText("こんにちは").closest("article");
    if (!firstRow) throw new Error("no row");
    await user.click(within(firstRow).getByRole("button", { name: "リプライ" }));
    expect(within(screen.getByRole("dialog")).getByText(/ に返信: こんにちは$/)).toBeInTheDocument();
  });

  it("[#538] ⚡ は発言者の kind:0 に lud16 があるときだけ。押すと ZapDialog（e=発言の id・k=42）", async () => {
    const user = userEvent.setup();
    useChannels.setState({ channels: CHANNELS });
    const aliceProfile = finalizeEvent(
      {
        kind: 0,
        created_at: 1,
        tags: [],
        content: JSON.stringify({ name: "alice", lud16: "alice@ln.example" }),
      },
      aliceKey,
    );
    addVerified(aliceProfile);
    const [spec] = decodeDeckColumns(SYNCED) ?? [];
    renderColumn(spec);

    const bobRow = screen.getByText("ミュートされる発言").closest("article");
    if (!bobRow) throw new Error("no row");
    expect(within(bobRow).queryByRole("button", { name: "Zap" })).not.toBeInTheDocument();

    const aliceRow = screen.getByText("こんにちは").closest("article");
    if (!aliceRow) throw new Error("no row");
    await user.click(within(aliceRow).getByRole("button", { name: "Zap" }));
    const dialog = screen.getByRole("dialog", { name: "⚡ Zap" });
    expect(within(dialog).getByText("送信先: alice@ln.example")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "⚡ 100" }));
    await waitFor(() =>
      expect(requestZapInvoice).toHaveBeenCalledWith(
        expect.objectContaining({
          recipient: ALICE,
          lud16: "alice@ln.example",
          eventId: first.id,
          targetKind: 42,
        }),
      ),
    );

    eventStore.remove(aliceProfile.id);
  });
});

describe("メッセージ画面のチャット", () => {
  function renderAt(path: string, width: number) {
    mockViewport(width);
    const router = createMemoryRouter(
      [
        { path: "/messages/:peer?", element: <MessagesScreen /> },
        { path: "/channels/:id?", element: <MessagesScreen kind="chat" /> },
      ],
      { initialEntries: [path] },
    );
    render(<RouterProvider router={router} />);
    return router;
  }

  function channelRows() {
    return within(screen.getByRole("list")).getAllByRole("listitem");
  }

  it("一覧は /api/nchan/channels の最終更新の新しい順。行を押すとルーム（最新が下・下に常設の入力欄）、「戻る」で一覧", async () => {
    const user = userEvent.setup();
    const router = renderAt("/channels", 400);
    expect(await screen.findByText("comic magazine")).toBeInTheDocument();
    expect(vi.mocked(fetch)).toHaveBeenCalledWith("/api/nchan/channels", expect.anything());
    expect(channelRows()).toHaveLength(2);
    expect(within(channelRows()[0]).getByText("さびれたスナック")).toBeInTheDocument();
    expect(within(channelRows()[0]).getByText("酔っ払いが問わず語り")).toBeInTheDocument();
    expect(within(channelRows()[1]).getByText("comic magazine")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "パブリックチャット" })).toBeInTheDocument();

    await user.click(within(channelRows()[0]).getByRole("button", { name: /さびれたスナック/ }));
    expect(router.state.location.pathname).toBe(`/channels/${CH}`);
    const room = screen.getByRole("region", { name: "さびれたスナック" });
    expect(within(room).getByRole("heading", { name: "さびれたスナック" })).toBeInTheDocument();
    expect(within(room).getByRole("textbox", { name: "メッセージ" })).toBeInTheDocument();
    // DOM は新しい順（column-reverse で最新が下）
    expect(texts()).toEqual(["返信です", "わたしの発言", "ミュートされる発言", "こんにちは"]);
    // [#600] Compact は「←」だけ。「✕」（選択解除）は Expanded だけ
    expect(screen.queryByRole("button", { name: "選択を解除" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "戻る" }));
    expect(router.state.location.pathname).toBe("/channels");
  });

  it("ミュートした人の発言は出ない（画面では常に隠す）", () => {
    useChannels.setState({ channels: CHANNELS });
    muteBob();
    renderAt(`/channels/${CH}`, 1400);
    expect(texts()).toEqual(["返信です", "わたしの発言", "こんにちは"]);
  });

  it("Expanded: 未選択は「チャンネルを選択」。リプライで返信中の表示（取り消せる）", async () => {
    const user = userEvent.setup();
    useChannels.setState({ channels: CHANNELS });
    renderAt("/channels", 1400);
    expect(screen.getByText("チャンネルを選択")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /comic magazine/ }));
    expect(screen.getByRole("region", { name: "comic magazine" })).toBeInTheDocument();
    expect(texts()).toEqual(["別のチャンネル"]);
    const row = screen.getByText("別のチャンネル").closest("article");
    if (!row) throw new Error("no row");
    await user.click(within(row).getByRole("button", { name: "リプライ" }));
    expect(screen.getByText(/ に返信: 別のチャンネル$/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "返信をやめる" }));
    expect(screen.queryByText(/ に返信: /)).not.toBeInTheDocument();
  });

  it("[#600] Expanded はヘッダ右端に「✕」（選択解除）。押すと一覧はそのまま「チャンネルを選択」へ戻る", async () => {
    const user = userEvent.setup();
    useChannels.setState({ channels: CHANNELS });
    const router = renderAt("/channels", 1400);

    await user.click(screen.getByRole("button", { name: /comic magazine/ }));
    expect(screen.getByRole("region", { name: "comic magazine" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "選択を解除" }));
    expect(router.state.location.pathname).toBe("/channels");
    expect(router.state.historyAction).toBe("REPLACE");
    expect(screen.getByText("チャンネルを選択")).toBeInTheDocument();
    expect(channelRows()).toHaveLength(2);
  });

  it("「ピン留め」でルームを固定カラムにして jump する（ネイティブ roomColumnFor）", async () => {
    const user = userEvent.setup();
    useChannels.setState({ channels: CHANNELS });
    renderAt("/channels", 1400);
    const pins = screen.getAllByRole("button", { name: "ピン留め" });
    expect(pins[0]).toHaveAttribute("aria-pressed", "false");
    await user.click(pins[0]);

    const column = useDeck.getState().columns.find((c) => c.id === `room_${CH}`);
    expect(column).toMatchObject({
      title: "さびれたスナック",
      subtitle: "酔っ払いが問わず語り",
      kind: "CHANNEL_ROOM",
      renderer: "ROOM",
      pinned: true,
    });
    expect(column?.filter).toMatchObject({ kinds: [42], channelId: CH });
    expect(useDeck.getState().jumpTarget).toBe(`room_${CH}`);
    expect(localStorage.getItem("nostrism.deck.columns")).toContain(`"id":"room_${CH}"`);
    expect(screen.getAllByRole("button", { name: "ピン留め" })[0]).toHaveAttribute("aria-pressed", "true");
  });

  it("一覧に無いチャンネルは、一覧を取っている間「チャンネルを読み込み中…」、取れたらルームを開く", async () => {
    const unknown = "f".repeat(64);
    renderAt(`/channels/${unknown}`, 400);
    expect(screen.getByText("チャンネルを読み込み中…")).toBeInTheDocument();
    expect(await screen.findByRole("region", { name: "パブリックチャット" })).toBeInTheDocument();
  });

  it("id が読めなければ「チャンネルを読み取れません」", () => {
    useChannels.setState({ channels: CHANNELS });
    renderAt("/channels/zzz", 400);
    expect(screen.getByText("チャンネルを読み取れません")).toBeInTheDocument();
  });
});

describe("未送信", () => {
  it("自分の発言で受理が無いものは「未送信・タップで再送」", () => {
    useChannels.setState({ channels: CHANNELS });
    const [spec] = decodeDeckColumns(SYNCED) ?? [];
    const router = createMemoryRouter([{ path: "*", element: <DeckColumn spec={spec} showHeader /> }]);
    render(<RouterProvider router={router} />);
    expect(screen.queryByRole("button", { name: "未送信・タップで再送" })).not.toBeInTheDocument();
    act(() => unsent$.next(new Set([mine.id])));
    expect(screen.getByRole("button", { name: "未送信・タップで再送" })).toBeInTheDocument();
    act(() => unsent$.next(new Set()));
  });
});
