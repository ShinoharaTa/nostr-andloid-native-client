import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import type { ReactElement } from "react";
import { useLocation } from "react-router";
import { VirtuosoMockContext } from "react-virtuoso";
import type { Subject } from "rxjs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { formatAbsoluteTime } from "../../lib/time";
import { useEventByAddress } from "../../nostr/loaders";
import { subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { renderWithRouter } from "../../test/renderWithRouter";
import cardStyles from "./CommentRootCard.module.css";
import reactorStyles from "./ReactorRow.module.css";
import { ThreadScreen } from "./ThreadScreen";
import zapStyles from "./ZapRow.module.css";

// リレーには繋がず、REQ ごとに Subject を返す（EOSE はテストから流す）
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  const { Subject } = await import("rxjs");
  const relays = ["wss://relay.example"];
  return {
    ...actual,
    useReadRelays: () => relays,
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
    requestOnce: vi.fn(() => new Subject<NostrEvent>()),
  };
});

// naddr の解決（addressLoader・実リレー）はしない。#534 の naddr 専用テストだけが差し替える
vi.mock("../../nostr/loaders", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/loaders")>()),
  useEventByAddress: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(subscribeTo).mockClear();
  vi.mocked(useEventByAddress).mockReturnValue({ event: undefined, failed: false });
});

/** 今の URL のパス（クリックで移動したかを見る） */
function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

/** jsdom は高さを測れないので、仮想リストに行の高さを与えて描く */
function render(ui: ReactElement) {
  return renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      {ui}
      <Where />
    </VirtuosoMockContext.Provider>,
  );
}

function signed(
  kind: number,
  tags: string[][] = [],
  { key = generateSecretKey(), content = "", createdAt = 1_000 } = {},
): NostrEvent {
  return finalizeEvent({ kind, created_at: createdAt, tags, content }, key);
}

function stored(...args: Parameters<typeof signed>): NostrEvent {
  const event = signed(...args);
  eventStore.add(event);
  return event;
}

/** root R ← 起点 F ← 返信 Y の 3 件 */
function conversation(focusTags: string[][] = []) {
  const root = stored(1, [], { content: "ルートの投稿", createdAt: 1_000 });
  const focus = stored(1, [["e", root.id, "", "root"], ...focusTags], {
    content: "起点の投稿",
    createdAt: 1_001,
  });
  const reply = stored(
    1,
    [
      ["e", root.id, "", "root"],
      ["e", focus.id, "", "reply"],
    ],
    { content: "返信の投稿", createdAt: 1_002 },
  );
  return { root, focus, reply };
}

/** target への Zap 受領（kind:9735）。金額は Zap リクエストの amount（msat） */
function storedZap(
  target: NostrEvent,
  {
    sender,
    sats,
    comment = "",
    createdAt = 2_000,
  }: { sender: string; sats: number; comment?: string; createdAt?: number },
): NostrEvent {
  const request = {
    kind: 9734,
    pubkey: sender,
    content: comment,
    tags: [
      ["amount", String(sats * 1000)],
      ["e", target.id],
    ],
  };
  return stored(
    9735,
    [
      ["e", target.id],
      ["p", target.pubkey],
      ["P", sender],
      ["description", JSON.stringify(request)],
    ],
    { createdAt },
  );
}

/** ReactorRow の行（DOM の順） */
function reactorRows(container: HTMLElement): HTMLElement[] {
  return [...container.getElementsByClassName(reactorStyles.row)] as HTMLElement[];
}

it("root から段を付けて並べ、起点の下に日時と反応を出す", () => {
  const { root, focus, reply } = conversation([["client", "Nostrism"]]);
  for (let i = 0; i < 2; i++) stored(7, [["e", focus.id]], { content: "+" });
  stored(6, [["e", focus.id]]);

  const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

  const articles = container.querySelectorAll("article");
  expect(articles).toHaveLength(3);
  const rowOf = (text: string) => screen.getByText(text).closest("[data-focused]") as HTMLElement;
  expect(rowOf("ルートの投稿")).toHaveAttribute("data-root", "true");
  expect(rowOf("起点の投稿")).toHaveAttribute("data-focused", "true");
  expect(rowOf("返信の投稿")).toHaveAttribute("data-focused", "false");
  expect(rowOf("返信の投稿").style.getPropertyValue("--depth")).toBe("2");
  expect(articles[0]).toHaveTextContent(root.content);
  expect(articles[2]).toHaveTextContent(reply.content);

  const focusRow = rowOf("起点の投稿");
  expect(
    within(focusRow).getByText(`${formatAbsoluteTime(focus.created_at)} · via Nostrism`),
  ).toBeInTheDocument();
  expect(within(focusRow).getByText("リプライ 1 · リポスト 1 · リアクション 2")).toBeInTheDocument();

  const [repostRow, heartRow] = reactorRows(container);
  // 件数はラベルで見る（画像の無いアバターは pubkey の頭文字を出すので、数字の 1 文字と重なりうる）
  const countOf = (row: HTMLElement) => row.getElementsByClassName(reactorStyles.label)[0];
  expect(countOf(repostRow)).toHaveTextContent(/^1$/);
  expect(within(repostRow).getAllByRole("link")).toHaveLength(1);
  expect(within(heartRow).getByText("❤️")).toBeInTheDocument();
  expect(countOf(heartRow)).toHaveTextContent(/^2$/);
  expect(within(heartRow).getAllByRole("link")).toHaveLength(2);
  for (const link of within(heartRow).getAllByRole("link")) {
    expect(link.getAttribute("href")).toMatch(/^\/p\/npub1/);
  }
});

it("client タグが無ければ日時だけ", () => {
  const { focus } = conversation();

  render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

  expect(screen.getByText(formatAbsoluteTime(focus.created_at))).toBeInTheDocument();
});

it("反応が 0 件なら集計の行を出さない", () => {
  const root = stored(1, [], { content: "ルートの投稿" });
  const focus = stored(1, [["e", root.id, "", "root"]], { content: "起点の投稿", createdAt: 1_001 });

  const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

  expect(screen.getByText("起点の投稿")).toBeInTheDocument();
  expect(screen.queryByText(/リプライ/)).toBeNull();
  expect(reactorRows(container)).toHaveLength(0);
});

it("リアクションした人が 13 人ならアバターは 12 個と +1", () => {
  const { focus } = conversation();
  for (let i = 0; i < 13; i++) stored(7, [["e", focus.id]], { content: "🔥" });

  const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

  const [fireRow] = reactorRows(container);
  expect(within(fireRow).getByText("🔥")).toBeInTheDocument();
  expect(within(fireRow).getByText("13")).toBeInTheDocument();
  expect(within(fireRow).getAllByRole("link")).toHaveLength(12);
  expect(within(fireRow).getByText("+1")).toBeInTheDocument();
});

it("onReply を渡すと返信ボックスが出て、押すと起点で呼ぶ", async () => {
  const { focus } = conversation();
  const onReply = vi.fn();

  render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} onReply={onReply} />);
  await userEvent.click(screen.getByRole("button", { name: "返信を書く" }));

  expect(onReply).toHaveBeenCalledWith(focus);
});

it("onReply が無ければ返信ボックスを出さない", () => {
  const { focus } = conversation();

  render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

  expect(screen.queryByRole("button", { name: "返信を書く" })).toBeNull();
});

it("行の本文を押してもスレッドを開き直さない", async () => {
  const { focus } = conversation();
  render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);
  const before = screen.getByTestId("where").textContent;

  await userEvent.click(screen.getByText("返信の投稿"));

  expect(screen.getByTestId("where").textContent).toBe(before);
  // 時刻もリンクにしない
  for (const time of document.querySelectorAll("article time")) expect(time.closest("a")).toBeNull();
});

it("行が 0 件なら読み込み中、EOSE の後は見つかりませんでした", () => {
  const missing = signed(1).id;

  render(<ThreadScreen onBack={() => {}} pointer={{ id: missing }} />);
  expect(screen.getByText("読み込み中…")).toBeInTheDocument();

  const reply = vi.mocked(subscribeTo).mock.results[0].value as Subject<"EOSE">;
  act(() => reply.next("EOSE"));

  expect(screen.getByText("見つかりませんでした")).toBeInTheDocument();
});

it("NIP-22 コメント（I タグ）は先頭に外部リンクのカードを出す", () => {
  const comment = stored(1111, [
    ["I", "https://example.com/a"],
    ["K", "web"],
  ]);

  render(<ThreadScreen onBack={() => {}} pointer={{ id: comment.id }} />);

  const card = screen.getByRole("link", { name: /example\.com へのコメント/ });
  expect(card).toHaveAttribute("href", "https://example.com/a");
  expect(card).toHaveAttribute("target", "_blank");
});

it("NIP-22 コメントのルート（E）が未取得なら K タグから「kind N へのコメント」", () => {
  const comment = stored(1111, [
    ["E", signed(1).id],
    ["K", "30023"],
  ]);

  const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: comment.id }} />);

  const [wrap] = container.getElementsByClassName(cardStyles.wrap);
  expect(wrap).toHaveTextContent("kind 30023 へのコメント");
});

it("NIP-22 コメントのルート（A）が記事（kind:30023）なら記事カードでタイトルを出す（#591。押すと記事へ）", () => {
  const authorKey = generateSecretKey();
  const author = getPublicKey(authorKey);
  const comment = stored(1111, [["A", `30023:${author}:x`]]);
  const article = signed(30023, [["title", "根の記事"]], { key: authorKey, content: "本文" });
  vi.mocked(useEventByAddress).mockReturnValue({ event: article, failed: false });

  render(<ThreadScreen onBack={() => {}} pointer={{ id: comment.id }} />);

  const card = screen.getByRole("link", { name: /根の記事/ });
  expect(card.getAttribute("href")).toMatch(/^\/e\/naddr1/);
});

it("NIP-22 コメントのルート（E）が記事（kind:30023）なら記事カードでタイトルを出す（#591）", () => {
  const article = stored(30023, [
    ["d", "z"],
    ["title", "取得済みの記事"],
  ]);
  const comment = stored(1111, [["E", article.id]]);

  // ルート記事本体がツリーの行としても取得できるため、タイトルの一致はリードカード（cardStyles.wrap）に絞る
  const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: comment.id }} />);

  const [wrap] = container.getElementsByClassName(cardStyles.wrap);
  expect(wrap).toHaveTextContent("取得済みの記事");
  const card = within(wrap as HTMLElement).getByRole("link");
  expect(card.getAttribute("href")).toMatch(/^\/e\/nevent1/);
});

it("kind:1 / 1111 / 30023 以外の起点は「Web 版ではまだ表示できません」", () => {
  const other = stored(9999, [], { content: "" });

  render(<ThreadScreen onBack={() => {}} pointer={{ id: other.id }} />);

  expect(screen.getByText("kind 9999 の投稿は Web 版ではまだ表示できません")).toBeInTheDocument();
});

it("起点が kind:30023 なら記事リーダーを描く（#534。今の「表示できません」の置き換え）", () => {
  const article = stored(
    30023,
    [
      ["d", "x"],
      ["title", "記事タイトル"],
    ],
    { content: "本文です" },
  );

  render(<ThreadScreen onBack={() => {}} pointer={{ id: article.id }} />);

  expect(screen.getByRole("heading", { level: 1, name: "記事タイトル" })).toBeInTheDocument();
  expect(screen.queryByText(/Web 版ではまだ表示できません/)).toBeNull();
});

it("naddr（AddressPointer）は addressLoader で解決するまで「読み込み中…」（#534）", () => {
  const addr = { kind: 30023 as const, pubkey: getPublicKey(generateSecretKey()), identifier: "y" };

  render(<ThreadScreen onBack={() => {}} pointer={addr} />);

  expect(screen.getByText("読み込み中…")).toBeInTheDocument();
});

it("naddr が 6 秒（ネイティブの resolveAddress と同じ）届かず諦めたら「記事を取得できませんでした」（#534）", () => {
  vi.mocked(useEventByAddress).mockReturnValue({ event: undefined, failed: true });
  const addr = { kind: 30023 as const, pubkey: getPublicKey(generateSecretKey()), identifier: "y" };

  render(<ThreadScreen onBack={() => {}} pointer={addr} />);

  expect(screen.getByText("記事を取得できませんでした")).toBeInTheDocument();
});

it("naddr が解決すれば記事リーダーになる（#534）", () => {
  const article = stored(
    30023,
    [
      ["d", "y"],
      ["title", "解決した記事"],
    ],
    { content: "本文" },
  );
  vi.mocked(useEventByAddress).mockReturnValue({ event: article, failed: false });
  const addr = { kind: 30023 as const, pubkey: article.pubkey, identifier: "y" };

  render(<ThreadScreen onBack={() => {}} pointer={addr} />);

  expect(screen.getByRole("heading", { level: 1, name: "解決した記事" })).toBeInTheDocument();
});

it("起点への Zap 受領を購読し、⚡ 行に合計 sats と Zap した人（重複なし）を出す", () => {
  const { focus } = conversation();
  const [alice, bob] = [getPublicKey(generateSecretKey()), getPublicKey(generateSecretKey())];
  storedZap(focus, { sender: alice, sats: 100, createdAt: 2_000 });
  storedZap(focus, { sender: bob, sats: 1_000, createdAt: 2_001 });
  storedZap(focus, { sender: alice, sats: 21, createdAt: 2_002 });

  const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

  expect(subscribeTo).toHaveBeenCalledWith(
    ["wss://relay.example"],
    [{ kinds: [9735], "#e": [focus.id], limit: 500 }],
  );
  const [zapRow] = reactorRows(container);
  expect(zapRow.getElementsByClassName(reactorStyles.label)[0]).toHaveTextContent(/^1121 sats$/);
  expect(within(zapRow).getAllByRole("link")).toHaveLength(2);
});

it("Zap だけの起点でも ⚡ 行を出す（集計の 1 行目は出さない）", () => {
  const root = stored(1, [], { content: "ルートの投稿" });
  const focus = stored(1, [["e", root.id, "", "root"]], { content: "起点の投稿", createdAt: 1_001 });
  storedZap(focus, { sender: getPublicKey(generateSecretKey()), sats: 21 });

  const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

  expect(screen.queryByText(/リプライ|リアクション/)).toBeNull();
  const [zapRow] = reactorRows(container);
  expect(zapRow.getElementsByClassName(reactorStyles.label)[0]).toHaveTextContent(/^21 sats$/);
});

it("コメント付き Zap だけを返信の後に新しい順で行にする（⚡・名前・金額・コメント）", () => {
  const { focus, reply } = conversation();
  const alice = generateSecretKey();
  stored(0, [], { key: alice, content: JSON.stringify({ name: "アリス" }) });
  const aliceHex = getPublicKey(alice);
  storedZap(focus, { sender: aliceHex, sats: 100, comment: "ありがとう", createdAt: 2_000 });
  storedZap(focus, { sender: getPublicKey(generateSecretKey()), sats: 5, createdAt: 2_001 });
  storedZap(focus, { sender: getPublicKey(generateSecretKey()), sats: 7, comment: "  ", createdAt: 2_002 });
  storedZap(focus, { sender: aliceHex, sats: 1_000, comment: "すごい", createdAt: 2_003 });

  const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

  const rows = [...container.getElementsByClassName(zapStyles.row)] as HTMLElement[];
  expect(rows).toHaveLength(2);
  expect(rows[0]).toHaveTextContent("アリス1000 satsすごい");
  expect(rows[1]).toHaveTextContent("アリス100 satsありがとう");
  expect(within(rows[0]).getByRole("img", { name: "Zap" })).toBeInTheDocument();
  expect(within(rows[0]).getByRole("link", { name: "アリス" }).getAttribute("href")).toMatch(/^\/p\/npub1/);
  // 返信の後
  const replyArticle = screen.getByText(reply.content).closest("article") as HTMLElement;
  expect(replyArticle.compareDocumentPosition(rows[0]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
});

describe("[#904] リアクション（kind:7 / 16）で開いたとき", () => {
  const scrollIntoView = vi.fn();
  beforeEach(() => {
    scrollIntoView.mockClear();
    Element.prototype.scrollIntoView = scrollIntoView;
  });

  /** 名前（kind:0）を持つ鍵 */
  function named(name: string): Uint8Array {
    const key = generateSecretKey();
    stored(0, [], { key, content: JSON.stringify({ name }) });
    return key;
  }

  const avatarHrefs = (row: HTMLElement) =>
    within(row)
      .getAllByRole("link")
      .map((a) => a.getAttribute("href"));
  const rowWith = (container: HTMLElement, text: string) =>
    reactorRows(container).find((row) => within(row).queryByText(text)) as HTMLElement;

  it("元の投稿のスレッドに付け替え、統計行のその絵文字の行でその人を先頭にして寄せてから光らせる", () => {
    const { focus } = conversation();
    const alice = generateSecretKey();
    const opened = stored(7, [["e", focus.id]], { key: alice, content: "👍", createdAt: 1_100 });
    // 新しい人（本来は先頭）
    stored(7, [["e", focus.id]], { content: "👍", createdAt: 1_200 });
    stored(7, [["e", focus.id]], { content: "❤️", createdAt: 1_300 });

    const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: opened.id }} />);

    expect(screen.getByText("起点の投稿").closest("[data-focused]")).toHaveAttribute("data-focused", "true");
    expect(screen.queryByText(/Web 版ではまだ表示できません/)).toBeNull();
    const thumbs = rowWith(container, "👍");
    expect(thumbs).toHaveAttribute("data-rx-highlight");
    expect(thumbs).toHaveAttribute("data-flash");
    expect(avatarHrefs(thumbs)[0]).toBe(`/p/${npubEncode(getPublicKey(alice))}`);
    expect(rowWith(container, "❤️")).not.toHaveAttribute("data-rx-highlight");
    expect(scrollIntoView).toHaveBeenCalledWith({ block: "center" });
  });

  it("リアクションへのリアクションは木の中の行が光り、見出しは「↳ 名前 の 👍 へ」。L2 の REQ を張る", () => {
    const { focus } = conversation();
    const mine = stored(7, [["e", focus.id]], { key: named("しの"), content: "👍", createdAt: 1_100 });
    const midori = generateSecretKey();
    const theirs = stored(7, [["e", mine.id]], { key: midori, content: "❤️", createdAt: 1_200 });

    const { container } = render(<ThreadScreen onBack={() => {}} pointer={{ id: theirs.id }} />);

    const heading = screen.getByText("↳ しの の").closest("p") as HTMLElement;
    expect(heading).toHaveTextContent("↳ しの の👍へ");
    expect(rowWith(container, "👍")).not.toHaveAttribute("data-rx-highlight");
    const deep = rowWith(container, "❤️");
    expect(deep).toHaveAttribute("data-rx-highlight");
    expect(avatarHrefs(deep)).toEqual([`/p/${npubEncode(getPublicKey(midori))}`]);
    const branchReq = vi
      .mocked(subscribeTo)
      .mock.calls.flatMap(([, filters]) => filters)
      .find((f) => f.kinds?.includes(16) && f["#e"]?.includes(mine.id));
    expect(branchReq).toMatchObject({ kinds: [7, 16], "#e": [mine.id] });
  });

  it("途中の投稿が手元に無ければ読み込み中で待ち、届いたら付け替える", () => {
    const post = signed(1, [], { content: "後から届く投稿", createdAt: 1_000 });
    const opened = stored(7, [["e", post.id]], { content: "+", createdAt: 1_100 });

    render(<ThreadScreen onBack={() => {}} pointer={{ id: opened.id }} />);

    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
    expect(screen.queryByText(/Web 版ではまだ表示できません/)).toBeNull();
    act(() => {
      eventStore.add(post);
    });
    expect(screen.getByText("後から届く投稿")).toBeInTheDocument();
  });

  it("4 秒待っても届かなければ従来どおり（kind 7 は表示できません）", () => {
    vi.useFakeTimers();
    try {
      const opened = stored(7, [["e", "f".repeat(64)]], { content: "+" });
      render(<ThreadScreen onBack={() => {}} pointer={{ id: opened.id }} />);
      expect(screen.getByText("読み込み中…")).toBeInTheDocument();
      act(() => {
        vi.advanceTimersByTime(4_000);
      });
      expect(screen.getByText("kind 7 の投稿は Web 版ではまだ表示できません")).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("e タグの無いリアクションは辿らず従来どおり", () => {
    const opened = stored(7, [], { content: "+" });
    render(<ThreadScreen onBack={() => {}} pointer={{ id: opened.id }} />);
    expect(screen.getByText("kind 7 の投稿は Web 版ではまだ表示できません")).toBeInTheDocument();
  });

  it("枝は 3 つまで出し、残りは「ほか n 件」で開く（行の人の順）", async () => {
    const { focus } = conversation();
    for (let i = 0; i < 5; i++) {
      const rx = stored(7, [["e", focus.id]], { key: named(`p${i}`), content: "👍", createdAt: 1_100 + i });
      stored(7, [["e", rx.id]], { content: "🎉", createdAt: 1_200 + i });
    }

    render(<ThreadScreen onBack={() => {}} pointer={{ id: focus.id }} />);

    // 行の人の順（新しい順）: p4 → p3 → p2
    expect(screen.getAllByText(/^↳ /).map((el) => el.textContent)).toEqual(["↳ p4 の", "↳ p3 の", "↳ p2 の"]);
    await userEvent.click(screen.getByRole("button", { name: "ほか 2 件" }));
    expect(screen.getAllByText(/^↳ /)).toHaveLength(5);
  });
});
