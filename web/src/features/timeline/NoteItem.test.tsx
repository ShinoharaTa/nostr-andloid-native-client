import { act, fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { decode, naddrEncode, neventEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import type { ReactElement } from "react";
import { useLocation } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { avatarShade } from "../../lib/avatar";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { renderWithRouter } from "../../test/renderWithRouter";
import { useToast } from "../../ui/toast";
import linkCardStyles from "../linkcard/LinkCard.module.css";
import gridStyles from "../media/ImageGrid.module.css";
import { DEFAULT_THEME_PREFS, setNoteAccent, useThemePrefs } from "../theme/themePrefs";
import { useTranslateStore } from "../translate/translateStore";
import contentStyles from "./NoteContent.module.css";
import { NoteItem } from "./NoteItem";
import noteStyles from "./NoteItem.module.css";

// naddr の解決（addressLoader・実リレー）はしない。ここでは埋め込みの並び順だけを見る（中身は ArticleCard.test.tsx）
vi.mock("../../nostr/loaders", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/loaders")>()),
  useEventByAddress: vi.fn(() => ({ event: undefined, failed: false })),
}));

afterEach(() => {
  useThemePrefs.setState(DEFAULT_THEME_PREFS);
});

// applesauce の Tokens.link はホストにドットを要求するため、テストの URL は *.test にする

/** 名前（とその他の項目）を持つプロフィールを入れ、その鍵を返す */
function withProfile(fields: Record<string, string>): Uint8Array {
  const key = generateSecretKey();
  eventStore.add(
    finalizeEvent({ kind: 0, created_at: unixNow(), tags: [], content: JSON.stringify(fields) }, key),
  );
  return key;
}

function post(
  content: string,
  {
    key = generateSecretKey(),
    tags = [],
    kind = 1,
  }: { key?: Uint8Array; tags?: string[][]; kind?: number } = {},
): NostrEvent {
  return finalizeEvent({ kind, created_at: unixNow(), tags, content }, key);
}

/** ストアに入れた投稿 */
function stored(content: string, options: Parameters<typeof post>[1] = {}): NostrEvent {
  const event = post(content, options);
  eventStore.add(event);
  return event;
}

const CARD = { name: "引用元の投稿を開く" } as const;

it("kind:6 は「〜がリポスト」の行と content に埋め込まれた元投稿を出す", async () => {
  const reposterKey = withProfile({ name: "bob" });
  const original = finalizeEvent(
    { kind: 1, created_at: unixNow() - 180, tags: [], content: "元の投稿です" },
    generateSecretKey(),
  );
  const repost = finalizeEvent(
    {
      kind: 6,
      created_at: unixNow(),
      tags: [
        ["e", original.id],
        ["p", original.pubkey],
      ],
      content: JSON.stringify(original),
    },
    reposterKey,
  );

  renderWithRouter(<NoteItem event={repost} />);

  expect(await screen.findByText("bob")).toBeInTheDocument();
  expect(screen.getByText("がリポスト")).toBeInTheDocument();
  expect(screen.getByText("元の投稿です")).toBeInTheDocument();
  expect(screen.getByText("3m")).toBeInTheDocument();
});

it("CW 付きは「表示」を押すまで本文も引用も出さない", async () => {
  const quoted = stored("引用された投稿");
  const event = post("秘密", {
    tags: [
      ["content-warning", "nsfw"],
      ["q", quoted.id],
    ],
  });

  renderWithRouter(<NoteItem event={event} />);

  expect(screen.queryByText("秘密")).toBeNull();
  expect(screen.queryByRole("link", CARD)).toBeNull();
  expect(screen.queryByText("引用元を読み込み中…")).toBeNull();
  expect(screen.getByText("nsfw")).toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", { name: /センシティブな内容/ }));

  expect(screen.getByText("秘密")).toBeInTheDocument();
  expect(screen.getByRole("link", CARD)).toHaveTextContent("引用された投稿");
});

it("返信は親の名前と本文 1 行を返信アイコン付きのリンクで出す", async () => {
  const parent = stored("a\nb https://i.test/x.jpg", { key: withProfile({ name: "carol" }) });
  const reply = post("返信です", { tags: [["e", parent.id, "", "reply"]] });

  renderWithRouter(<NoteItem event={reply} />);

  const line = await screen.findByText("carol: a b");
  const link = line.closest("a");
  expect(link).toHaveAttribute("href", `/e/${neventEncode({ id: parent.id })}`);
  expect(link?.querySelector("svg")).not.toBeNull();
});

it("返信の親がストアに無ければ返信行を出さない", () => {
  const reply = post("返信です", { tags: [["e", "f".repeat(64), "", "reply"]] });

  const { container } = renderWithRouter(<NoteItem event={reply} />);

  expect(screen.getByText("返信です")).toBeInTheDocument();
  // 時刻（この投稿のスレッドへのリンク）以外に /e/ へのリンクが無い
  const links = [...container.querySelectorAll('a[href^="/e/"]')].filter((a) => !a.querySelector("time"));
  expect(links).toHaveLength(0);
});

it("kind:1111 で親が取れていない間は K タグから「kind N へのコメント」を出す", () => {
  const comment = post("コメント", {
    kind: 1111,
    tags: [
      ["E", "e".repeat(64)],
      ["K", "30023"],
    ],
  });

  renderWithRouter(<NoteItem event={comment} />);

  expect(screen.getByText("kind 30023 へのコメント")).toBeInTheDocument();
});

it("本文の nevent が取れていれば引用カードにし、本文側の参照を消す", async () => {
  const quoted = stored("引用元の本文 https://x.test/page #tag", { key: withProfile({ name: "dave" }) });
  const nevent = neventEncode({ id: quoted.id });

  renderWithRouter(<NoteItem event={post(`見て nostr:${nevent}`)} />);

  const card = screen.getByRole("link", CARD);
  expect(card).toHaveAttribute("href", `/e/${nevent}`);
  expect(await within(card).findByText("dave")).toBeInTheDocument();
  expect(card).toHaveTextContent("引用元の本文");
  expect(card.querySelector("a")).toBeNull();
  expect(screen.queryByText(`↗${nevent.slice(0, 12)}…`)).toBeNull();
  expect(screen.getByText("見て")).toBeInTheDocument();
});

it("本文の nevent が取れていなければ ↗ のリンクを残し、読み込み中のカードを出す", () => {
  const nevent = neventEncode({ id: "d".repeat(64) });

  renderWithRouter(<NoteItem event={post(`見て nostr:${nevent}`)} />);

  expect(screen.getByRole("link", { name: `↗${nevent.slice(0, 12)}…` })).toHaveAttribute(
    "href",
    `/e/${nevent}`,
  );
  expect(screen.getByText("引用元を読み込み中…")).toBeInTheDocument();
  expect(screen.queryByRole("link", CARD)).toBeNull();
});

it("引用元がさらに引用していてもカードは 1 段だけ", () => {
  const inner = stored("内側の投稿");
  const outer = stored(`外側 nostr:${neventEncode({ id: inner.id })}`);

  renderWithRouter(<NoteItem event={post(`nostr:${neventEncode({ id: outer.id })}`)} />);

  expect(screen.getAllByRole("link", CARD)).toHaveLength(1);
  expect(screen.queryByText("内側の投稿")).toBeNull();
});

it("引用元と返信の親が同じなら返信行を出さない", () => {
  const quoted = stored("引用兼親", { key: withProfile({ name: "erin" }) });

  renderWithRouter(
    <NoteItem
      event={post(`nostr:${neventEncode({ id: quoted.id })}`, { tags: [["e", quoted.id, "", "reply"]] })}
    />,
  );

  expect(screen.getAllByRole("link", CARD)).toHaveLength(1);
  expect(screen.queryByText(/^erin: /)).toBeNull();
});

it("引用元に content-warning があればカードは「センシティブな内容」だけ", () => {
  const quoted = stored("隠す本文", { tags: [["content-warning", "spoiler"]] });

  renderWithRouter(<NoteItem event={post("", { tags: [["q", quoted.id]] })} />);

  const card = screen.getByRole("link", CARD);
  expect(within(card).getByText("センシティブな内容")).toBeInTheDocument();
  expect(card).not.toHaveTextContent("隠す本文");
});

it("引用元の画像 2 枚と動画 1 本をカードにサムネイルで並べる", () => {
  const quoted = stored("写真 https://i.test/1.jpg https://i.test/2.png https://v.test/1.mp4");

  renderWithRouter(<NoteItem event={post("", { tags: [["q", quoted.id]] })} />);

  const card = screen.getByRole("link", CARD);
  const images = card.querySelectorAll("img");
  expect(images).toHaveLength(2);
  for (const img of images) {
    expect(img.getAttribute("src")).toContain("w=640");
    expect(img.getAttribute("src")).toContain("q=80");
  }
  expect(within(card).getAllByText("動画")).toHaveLength(1);
});

it("client タグは時刻の title にだけ出す", () => {
  const { container } = renderWithRouter(<NoteItem event={post("hi", { tags: [["client", "Nostrism"]] })} />);

  const time = container.querySelector("time");
  expect(time?.getAttribute("title")).toMatch(/ · Nostrism から投稿$/);
  expect(container).not.toHaveTextContent("Nostrism");
});

it("NIP-05 を名前の横に出す", async () => {
  const key = withProfile({ name: "alice", nip05: "alice@example.com" });

  renderWithRouter(<NoteItem event={post("hi", { key })} />);

  expect(await screen.findByText("alice@example.com")).toBeInTheDocument();
});

it("相対時刻は時間が経つと進む", () => {
  vi.useFakeTimers();
  try {
    const { container } = renderWithRouter(<NoteItem event={post("時計")} />);
    const time = container.querySelector("time");
    expect(time).toHaveTextContent("now");

    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    expect(time).toHaveTextContent("1m");
  } finally {
    vi.useRealTimers();
  }
});

it("画像 URL 2 本だけの投稿は本文を出さず、2 列のグリッドに並べる（URL のリンクは出さない）", () => {
  const key = generateSecretKey();
  const urls = ["https://i.test/1.jpg", "https://i.test/2.png"];
  const event = post(urls.join("\n"), {
    key,
    tags: [
      ["imeta", `url ${urls[0]}`, "dim 1920x1080", "alt 一枚目"],
      ["imeta", `url ${urls[1]}`, "dim 800x600"],
    ],
  });
  const { container } = renderWithRouter(<NoteItem event={event} />);

  // 記事の文字は アバターの頭文字・名前（hex の先頭 10 字）・時刻だけ（本文も折りたたみのトグルも無い）
  const name = getPublicKey(key).slice(0, 10);
  expect(container.querySelector("article")?.textContent).toBe(`${name[0].toUpperCase()}${name}now`);
  const grid = container.getElementsByClassName(gridStyles.cols2)[0];
  const images = grid.querySelectorAll("img");
  expect(images).toHaveLength(2);
  expect(images[0]).toHaveAttribute("alt", "一枚目");
  expect(screen.getAllByRole("button", { name: /^画像 \d \/ 2 を拡大$/ })).toHaveLength(2);
  for (const url of urls) {
    expect(screen.queryByRole("link", { name: url })).toBeNull();
    expect(container.querySelector(`a[href="${url}"]`)).toBeNull();
  }
});

/** 今の URL のパス（クリックで移動したかを見る） */
function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

function renderNote(ui: ReactElement) {
  const result = renderWithRouter(
    <>
      {ui}
      <Where />
    </>,
  );
  return { ...result, where: () => screen.getByTestId("where").textContent };
}

/** /e/nevent1… の指す id（それ以外のパスは null） */
function threadIdOf(pathname: string | null): string | null {
  const match = pathname?.match(/^\/e\/(nevent1\w+)$/);
  if (!match) return null;
  const decoded = decode(match[1]);
  return decoded.type === "nevent" ? decoded.data.id : null;
}

it("本文を押すとスレッド（/e/nevent1…）を開く", async () => {
  const event = post("本文です");
  const { where } = renderNote(<NoteItem event={event} />);

  await userEvent.click(screen.getByText("本文です"));

  expect(threadIdOf(where())).toBe(event.id);
});

it("#タグ・引用カード・返信行・名前を押すとそれぞれのリンク先へ行き、スレッドは開かない", async () => {
  const user = userEvent.setup();
  const parent = stored("親の投稿", { key: withProfile({ name: "frank" }) });
  const quoted = stored("引用元");
  const quotedRef = neventEncode({ id: quoted.id });
  const key = withProfile({ name: "grace" });
  const event = post(`#nostr を見て nostr:${quotedRef}`, {
    key,
    tags: [
      ["e", parent.id, "", "reply"],
      ["t", "nostr"],
    ],
  });
  const { where } = renderNote(<NoteItem event={event} />);

  await user.click(screen.getByRole("link", { name: "#nostr" }));
  expect(where()).toBe("/t/nostr");

  await user.click(screen.getByRole("link", CARD));
  expect(where()).toBe(`/e/${quotedRef}`);

  await user.click(await screen.findByText("frank: 親の投稿"));
  expect(threadIdOf(where())).toBe(parent.id);

  await user.click(await screen.findByRole("link", { name: "grace" }));
  expect(where()).toMatch(/^\/p\/npub1/);
});

it("文字を選択しているとき・修飾キー付きのクリックではスレッドを開かない", () => {
  const { where } = renderNote(<NoteItem event={post("選べる本文")} />);
  const text = screen.getByText("選べる本文");

  const selection = vi
    .spyOn(window, "getSelection")
    .mockReturnValue({ toString: () => "選択" } as unknown as Selection);
  try {
    fireEvent.click(text);
  } finally {
    selection.mockRestore();
  }
  expect(where()).toBe("/");

  fireEvent.click(text, { ctrlKey: true });
  expect(where()).toBe("/");

  // 選択も修飾キーも無ければ開く
  fireEvent.click(text);
  expect(where()).toMatch(/^\/e\/nevent1/);
});

it("メニュー（role=menu）の中を押してもスレッドを開かない", () => {
  const { container, where } = renderNote(<NoteItem event={post("メニューのある投稿")} />);
  const menu = document.createElement("div");
  menu.setAttribute("role", "menu");
  const heading = document.createElement("span");
  heading.textContent = "Nostrism から投稿";
  menu.append(heading);
  container.querySelector("article")?.append(menu);

  fireEvent.click(heading);

  expect(where()).toBe("/");
});

it("openable={false} は押しても開かず、時刻もリンクにしない", async () => {
  const { container, where } = renderNote(<NoteItem event={post("開かない")} openable={false} />);

  await userEvent.click(screen.getByText("開かない"));

  expect(where()).toBe("/");
  expect(container.querySelector("time")?.closest("a")).toBeNull();
});

it("既定では時刻がスレッドへのリンク", () => {
  const event = post("時刻のリンク");
  const { container } = renderNote(<NoteItem event={event} />);

  const link = container.querySelector("time")?.closest("a");
  expect(threadIdOf(link?.getAttribute("href") ?? null)).toBe(event.id);
});

it("kind:6 は元投稿がストアにあるときだけ、押すと元投稿のスレッドを開く", async () => {
  const original = stored("リポスト元");
  const repost = (targetId: string) =>
    finalizeEvent(
      { kind: 6, created_at: unixNow(), tags: [["e", targetId]], content: "" },
      generateSecretKey(),
    );

  const resolved = renderNote(<NoteItem event={repost(original.id)} />);
  await userEvent.click(screen.getByText("リポスト元"));
  expect(threadIdOf(resolved.where())).toBe(original.id);
  resolved.unmount();

  const pending = renderNote(<NoteItem event={repost("c".repeat(64))} />);
  await userEvent.click(screen.getByText("元の投稿を読み込み中…"));
  expect(pending.where()).toBe("/");
});

it("kind:1 には操作の行に「返信」ボタンがある", () => {
  renderWithRouter(<NoteItem event={post("返信できる投稿")} />);
  expect(
    within(screen.getByRole("group", { name: "操作" })).getByRole("button", { name: "返信" }),
  ).toBeInTheDocument();
});

it("kind:1 の操作の行は 返信・リポスト・リアクション・絵文字でリアクション・その他の操作（#459）", () => {
  renderWithRouter(<NoteItem event={post("反応できる投稿")} />);
  expect(
    within(screen.getByRole("group", { name: "操作" }))
      .getAllByRole("button")
      .map((b) => b.getAttribute("aria-label")),
  ).toEqual(["返信", "リポスト", "リアクション", "絵文字でリアクション", "その他の操作"]);
});

it("本文の naddr（記事）は記事カードにし、埋め込み（本文）の後・操作の行の前に置く（#534）", () => {
  const naddr = naddrEncode({ kind: 30023, pubkey: getPublicKey(generateSecretKey()), identifier: "a" });
  const event = stored(`本文です nostr:${naddr}`);

  const { container } = renderWithRouter(<NoteItem event={event} />);

  // 本文中のメンション（↗naddr1…）とは別に、下に記事カード（解決中）が出る
  const card = screen.getByText("読み込み中…").closest("a") as HTMLAnchorElement;
  expect(card.getAttribute("href")).toMatch(/^\/e\/naddr1/);
  const body = container.querySelector(`.${contentStyles.content}`) as HTMLElement;
  const actions = screen.getByRole("group", { name: "操作" });
  expect(body.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(card.compareDocumentPosition(actions) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
});

it("リンクは OGP が取れたらメディアの下にカードを出し、本文からは消す（取得中は枠 + 本文のリンクのまま）", async () => {
  const url = "https://ogp-ok.test/article";
  const fetchMock = vi.fn<typeof fetch>(
    async () => new Response('<head><meta property="og:title" content="記事のタイトル"></head>'),
  );
  vi.stubGlobal("fetch", fetchMock);
  try {
    const { container } = renderWithRouter(<NoteItem event={post(`読んだ ${url}`)} />);
    expect(screen.getByRole("link", { name: url })).toBeInTheDocument();
    expect(container.getElementsByClassName(linkCardStyles.placeholder)).toHaveLength(1);

    const card = await screen.findByRole("link", { name: /記事のタイトル/ });
    expect(card).toHaveAttribute("href", url);
    expect(screen.queryByRole("link", { name: url })).toBeNull();
    expect(container.querySelectorAll(`a[href="${url}"]`)).toHaveLength(1);
    expect(container.getElementsByClassName(linkCardStyles.placeholder)).toHaveLength(0);
    expect(screen.getByText("読んだ")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(`/api/og?url=${encodeURIComponent(url)}`, expect.anything());
  } finally {
    vi.unstubAllGlobals();
  }
});

it("OGP が取れなければカードを出さず、本文のリンクを残す。CW を開くまでは取りに行かない", async () => {
  const url = "https://ogp-ng.test/article";
  const fetchMock = vi.fn<typeof fetch>(async () => new Response("{}", { status: 502 }));
  vi.stubGlobal("fetch", fetchMock);
  try {
    const { container } = renderWithRouter(
      <NoteItem event={post(`見て ${url}`, { tags: [["content-warning", "nsfw"]] })} />,
    );
    expect(fetchMock).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: /センシティブな内容/ }));
    await vi.waitFor(() =>
      expect(container.getElementsByClassName(linkCardStyles.placeholder)).toHaveLength(0),
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(container.getElementsByClassName(linkCardStyles.card)).toHaveLength(0);
    expect(screen.getByRole("link", { name: url })).toBeInTheDocument();
  } finally {
    vi.unstubAllGlobals();
  }
});

it("embedded（通知の本体）は返信先の 1 行と下線を出さない。既定では出す（#460）", async () => {
  const parent = stored("親の投稿", { key: withProfile({ name: "heidi" }) });
  const reply = post("通知の返信", { tags: [["e", parent.id, "", "reply"]] });

  const normal = renderWithRouter(<NoteItem event={reply} />);
  expect(await screen.findByText("heidi: 親の投稿")).toBeInTheDocument();
  expect(normal.container.querySelector("article")).toHaveClass(noteStyles.note);
  normal.unmount();

  const { container } = renderWithRouter(<NoteItem event={reply} embedded />);
  expect(screen.getByText("通知の返信")).toBeInTheDocument();
  expect(screen.queryByText("heidi: 親の投稿")).toBeNull();
  // 時刻（この投稿のスレッドへのリンク）以外に /e/ へのリンクが無い = 返信行が無い
  const links = [...container.querySelectorAll('a[href^="/e/"]')].filter((a) => !a.querySelector("time"));
  expect(links).toHaveLength(0);
  const article = container.querySelector("article");
  expect(article).not.toHaveClass(noteStyles.note);
  expect(article).toHaveClass(noteStyles.embedded);
});

it("画像の無いアバターは名前の頭文字を、名前から決めたグレーの丸に出す（ネイティブの Avatar）", () => {
  const key = withProfile({ name: "alice" });
  const { container } = renderWithRouter(<NoteItem event={post("頭文字", { key })} />);

  const avatar = container.getElementsByClassName(noteStyles.initial)[0] as HTMLElement;
  expect(avatar).toHaveTextContent(/^A$/);
  expect(avatar.style.background).toBe(avatarShade("alice"));
});

it("アバターの画像がプロキシでも元 URL でも読めなければ頭文字に替える", () => {
  const key = withProfile({ name: "bob", picture: "https://avatar-broken.test/bob.png" });
  const { container } = renderWithRouter(<NoteItem event={post("読めない画像", { key })} />);

  const avatarOf = () => container.querySelector("article a[aria-hidden] > *") as HTMLElement;
  fireEvent.error(avatarOf());
  expect(avatarOf()).toHaveAttribute("src", "https://avatar-broken.test/bob.png");
  fireEvent.error(avatarOf());
  expect(avatarOf()).toHaveTextContent(/^B$/);
});

it("プロフィールの無い人の名前は hex の先頭 10 字（ネイティブの toNoteUi）", () => {
  const key = generateSecretKey();
  renderWithRouter(<NoteItem event={post("名無し", { key })} />);
  expect(screen.getByRole("link", { name: getPublicKey(key).slice(0, 10) })).toBeInTheDocument();
});

it("kind:6 の元投稿が 8 秒で取れなければ行ごと隠し、後から届けば出す（作り直しても隠したまま）", () => {
  vi.useFakeTimers();
  try {
    const original = post("遅れて届く元投稿");
    const repost = finalizeEvent(
      { kind: 6, created_at: unixNow(), tags: [["e", original.id]], content: "" },
      generateSecretKey(),
    );
    const first = renderWithRouter(<NoteItem event={repost} />);
    expect(screen.getByText("元の投稿を読み込み中…")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(7_999);
    });
    expect(screen.getByText("元の投稿を読み込み中…")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText("元の投稿を読み込み中…")).toBeNull();
    expect(first.container.querySelector("article")).toBeNull();
    expect(first.container.getElementsByClassName(noteStyles.hiddenRow)).toHaveLength(1);
    first.unmount();

    // 仮想リストで作り直されても「読み込み中…」から始めない
    const again = renderWithRouter(<NoteItem event={repost} />);
    expect(screen.queryByText("元の投稿を読み込み中…")).toBeNull();

    act(() => {
      eventStore.add(original);
    });
    expect(screen.getByText("遅れて届く元投稿")).toBeInTheDocument();
    expect(again.container.querySelector("article")).not.toBeNull();
  } finally {
    vi.useRealTimers();
  }
});

it("[#464] 種別の視覚表示は既定オフ（なし）なら通常の投稿にクラスを付けない", () => {
  const reply = post("返信", { tags: [["e", "f".repeat(64), "", "reply"]] });
  const { container } = renderWithRouter(<NoteItem event={reply} />);
  const article = container.querySelector("article");
  expect(article).not.toHaveClass(noteStyles.accentLineReply);
  expect(article).not.toHaveClass(noteStyles.accentBgReply);
});

it("[#464] 返信は line / bg で --kind-reply のスタイルを付ける", () => {
  const reply = post("返信", { tags: [["e", "f".repeat(64), "", "reply"]] });

  setNoteAccent("line");
  const line = renderWithRouter(<NoteItem event={reply} />);
  expect(line.container.querySelector("article")).toHaveClass(noteStyles.accentLineReply);
  line.unmount();

  setNoteAccent("bg");
  const bg = renderWithRouter(<NoteItem event={reply} />);
  expect(bg.container.querySelector("article")).toHaveClass(noteStyles.accentBgReply);
});

it("[#464] 引用は返信より優先する（同じ投稿が両方でも quote になる）", () => {
  const quoted = stored("引用元");
  const event = post(`nostr:${neventEncode({ id: quoted.id })}`, {
    tags: [["e", "f".repeat(64), "", "reply"]],
  });
  setNoteAccent("line");

  const { container } = renderWithRouter(<NoteItem event={event} />);

  expect(container.querySelector("article")).toHaveClass(noteStyles.accentLineQuote);
  expect(container.querySelector("article")).not.toHaveClass(noteStyles.accentLineReply);
});

it("[#464] kind:7（リアクション）は reaction のスタイルを付ける", () => {
  setNoteAccent("bg");
  const reaction = post("+", { kind: 7 });

  const { container } = renderWithRouter(<NoteItem event={reaction} />);

  expect(container.querySelector("article")).toHaveClass(noteStyles.accentBgReaction);
});

it("[#464] kind:6（リポスト）は中身の種別に関係なく repost のスタイルを付ける", () => {
  const original = stored("リポスト元");
  const repost = finalizeEvent(
    { kind: 6, created_at: unixNow(), tags: [["e", original.id]], content: "" },
    generateSecretKey(),
  );
  setNoteAccent("line");

  const { container } = renderWithRouter(<NoteItem event={repost} />);

  expect(container.querySelector("article")).toHaveClass(noteStyles.accentLineRepost);
});

// ---- ⋯「翻訳」（#541） ----

/** モックの Translator / LanguageDetector（呼ぶたびに同じ配列・結果を返す） */
function stubTranslateApis(opts: {
  detected: string;
  availability?: "unavailable" | "downloadable" | "downloading" | "available";
  translated?: string;
}) {
  const detect = vi.fn(async () => [{ detectedLanguage: opts.detected, confidence: 1 }]);
  const availability = vi.fn(async () => opts.availability ?? "available");
  const translate = vi.fn(async () => opts.translated ?? "translated");
  vi.stubGlobal("LanguageDetector", { create: vi.fn(async () => ({ detect })) });
  vi.stubGlobal("Translator", { availability, create: vi.fn(async () => ({ translate })) });
  return { detect, availability, translate };
}

afterEach(() => {
  vi.unstubAllGlobals();
  useTranslateStore.setState({ entries: {}, pending: new Set() });
  useToast.setState({ queue: [] });
});

it("Translator / LanguageDetector が無ければ「翻訳」は出ない", async () => {
  const user = userEvent.setup();
  renderWithRouter(<NoteItem event={post("hello")} />);
  await user.click(
    within(screen.getByRole("group", { name: "操作" })).getByRole("button", { name: "その他の操作" }),
  );
  expect(screen.queryByRole("menuitem", { name: "翻訳" })).toBeNull();
});

it("本文が空（画像のみ）なら、対応ブラウザでも「翻訳」は出ない", async () => {
  stubTranslateApis({ detected: "ja" });
  const user = userEvent.setup();
  renderWithRouter(<NoteItem event={post("https://a.test/x.png")} />);
  await user.click(
    within(screen.getByRole("group", { name: "操作" })).getByRole("button", { name: "その他の操作" }),
  );
  expect(screen.queryByRole("menuitem", { name: "翻訳" })).toBeNull();
});

it("判定した言語が表示言語（jsdom既定 en）と同じなら、原文をそのまま本文の下に出す", async () => {
  const { translate } = stubTranslateApis({ detected: "en" });
  const user = userEvent.setup();
  renderWithRouter(<NoteItem event={post("hello world")} />);

  await user.click(
    within(screen.getByRole("group", { name: "操作" })).getByRole("button", { name: "その他の操作" }),
  );
  await user.click(await screen.findByRole("menuitem", { name: "翻訳" }));

  expect(await screen.findByText("翻訳")).toBeInTheDocument();
  expect(screen.getAllByText("hello world")).toHaveLength(2);
  expect(translate).not.toHaveBeenCalled();
});

it("違う言語なら訳文を本文の下に出し、隠して再表示しても訳し直さない", async () => {
  const { detect, translate } = stubTranslateApis({ detected: "ja", translated: "hello" });
  const user = userEvent.setup();
  renderWithRouter(<NoteItem event={post("こんにちは")} />);

  const openMenu = () =>
    user.click(
      within(screen.getByRole("group", { name: "操作" })).getByRole("button", { name: "その他の操作" }),
    );

  await openMenu();
  await user.click(await screen.findByRole("menuitem", { name: "翻訳" }));
  expect(await screen.findByText("hello")).toBeInTheDocument();
  expect(detect).toHaveBeenCalledTimes(1);
  expect(translate).toHaveBeenCalledTimes(1);

  await openMenu();
  await user.click(await screen.findByRole("menuitem", { name: "翻訳を隠す" }));
  expect(screen.queryByText("hello")).toBeNull();

  await openMenu();
  await user.click(await screen.findByRole("menuitem", { name: "翻訳" }));
  expect(await screen.findByText("hello")).toBeInTheDocument();
  expect(detect).toHaveBeenCalledTimes(1);
  expect(translate).toHaveBeenCalledTimes(1);
});

it("失敗（unavailable）はトーストで知らせ、本文の下には何も出さない", async () => {
  stubTranslateApis({ detected: "ja", availability: "unavailable" });
  const user = userEvent.setup();
  renderWithRouter(<NoteItem event={post("こんにちは")} />);

  await user.click(
    within(screen.getByRole("group", { name: "操作" })).getByRole("button", { name: "その他の操作" }),
  );
  await user.click(await screen.findByRole("menuitem", { name: "翻訳" }));

  await vi.waitFor(() => expect(useToast.getState().queue).toEqual(["翻訳できませんでした"]));
  expect(screen.queryByText("翻訳")).toBeNull();
});

/** /e/nevent1… の指す先（それ以外のパスは null） */
function linkOf(pathname: string | null): { id: string; kind?: number; relays?: string[] } | null {
  const match = pathname?.match(/^\/e\/(nevent1\w+)$/);
  if (!match) return null;
  const decoded = decode(match[1]);
  return decoded.type === "nevent" ? decoded.data : null;
}

it("[#796] パブリックチャットの発言は「#チャンネル名 で発言」の行を出し、行・本文・返信はそのチャンネルのルームを開く", async () => {
  const user = userEvent.setup();
  const channel = finalizeEvent(
    { kind: 40, created_at: 1, tags: [], content: JSON.stringify({ name: "雑談部屋" }) },
    generateSecretKey(),
  );
  eventStore.add(channel);
  const message = post("チャットの発言です", {
    kind: 42,
    tags: [["e", channel.id, "wss://chat.example", "root"]],
  });
  const room = { id: channel.id, kind: 40, relays: ["wss://chat.example"] };

  const first = renderNote(<NoteItem event={message} />);
  await user.click(await screen.findByRole("link", { name: "#雑談部屋 で発言" }));
  expect(linkOf(first.where())).toEqual(room);
  first.unmount();

  const second = renderNote(<NoteItem event={message} />);
  await user.click(screen.getByText("チャットの発言です"));
  expect(linkOf(second.where())).toEqual(room);
  second.unmount();

  const third = renderNote(<NoteItem event={message} />);
  await user.click(screen.getByRole("button", { name: "返信" }));
  expect(linkOf(third.where())).toEqual(room);
});

it("[#796] チャンネル名が分からない間は「チャットで発言」", () => {
  const message = post("名前の無い部屋の発言", { kind: 42, tags: [["e", "f".repeat(64), "", "root"]] });
  renderNote(<NoteItem event={message} />);
  expect(screen.getByRole("link", { name: "チャットで発言" })).toBeInTheDocument();
});

it("[#796] 発言への返信は返信元の 1 行も出す（root のチャンネルは返信元にしない）", async () => {
  const channelId = "f".repeat(64);
  const parent = stored("返信元の発言", {
    key: withProfile({ name: "heidi" }),
    kind: 42,
    tags: [["e", channelId, "", "root"]],
  });
  const message = post("返信の発言", {
    kind: 42,
    tags: [
      ["e", channelId, "", "root"],
      ["e", parent.id, "", "reply"],
    ],
  });
  renderNote(<NoteItem event={message} />);
  expect(await screen.findByText("heidi: 返信元の発言")).toBeInTheDocument();
});
