import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { decode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { createMemoryRouter, RouterProvider } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { NotificationRow } from "./NotificationRow";
import { type NotificationItem, toNotification } from "./notificationModel";

// applesauce の Tokens.link はホストにドットを要求するため、テストの URL は *.test にする

const CARD = { name: "引用元の投稿を開く" } as const;

let meKey: Uint8Array;
let me: string;

beforeEach(() => {
  meKey = generateSecretKey();
  me = getPublicKey(meKey);
});

/** 名前を持つプロフィールを入れ、その鍵を返す */
function withProfile(name: string): Uint8Array {
  const key = generateSecretKey();
  eventStore.add(
    finalizeEvent({ kind: 0, created_at: unixNow(), tags: [], content: JSON.stringify({ name }) }, key),
  );
  return key;
}

function signed(kind: number, key: Uint8Array, tags: string[][], content = ""): NostrEvent {
  return finalizeEvent({ kind, created_at: unixNow() - 120, tags, content }, key);
}

/** ストアに入れた自分の投稿（通知の対象） */
function myPost(content: string): NostrEvent {
  const event = finalizeEvent({ kind: 1, created_at: unixNow() - 3600, tags: [], content }, meKey);
  eventStore.add(event);
  return event;
}

function itemOf(event: NostrEvent): NotificationItem {
  const item = toNotification(event);
  if (!item) throw new Error("not a notification");
  return item;
}

/** 行を描き、移動した先のパスを順に記録する（行とリンクの両方が移動したら 2 件になる） */
function renderRow(item: NotificationItem) {
  const router = createMemoryRouter([{ path: "*", element: <NotificationRow item={item} /> }], {
    initialEntries: ["/"],
  });
  const visited: string[] = [];
  let lastKey = router.state.location.key;
  router.subscribe((state) => {
    if (state.location.key === lastKey) return;
    lastKey = state.location.key;
    visited.push(state.location.pathname);
  });
  const result = render(<RouterProvider router={router} />);
  return { ...result, visited, where: () => visited.at(-1) };
}

/** /e/nevent1… の指す id（それ以外は null） */
function threadIdOf(href: string | null | undefined): string | null {
  const match = href?.match(/^\/e\/(nevent1\w+)$/);
  if (!match) return null;
  const decoded = decode(match[1]);
  return decoded.type === "nevent" ? decoded.data.id : null;
}

describe("リアクション", () => {
  it("マークは絵文字、名前はプロフィール、時刻は対象のスレッド、本体は対象の引用カード（メディア無し）", async () => {
    const target = myPost("自分の投稿 https://i.test/photo.jpg");
    const reaction = signed(
      7,
      withProfile("alice"),
      [
        ["e", target.id],
        ["p", me],
      ],
      "+",
    );

    const { container } = renderRow(itemOf(reaction));

    expect(screen.getByRole("img", { name: "リアクション ❤️" })).toBeInTheDocument();
    const name = await screen.findByRole("link", { name: "alice" });
    expect(name.getAttribute("href")).toMatch(/^\/p\/npub1/);
    const time = container.querySelector("time")?.closest("a");
    expect(threadIdOf(time?.getAttribute("href"))).toBe(target.id);
    const card = screen.getByRole("link", CARD);
    expect(card).toHaveTextContent("自分の投稿");
    expect(card.querySelectorAll("img")).toHaveLength(0);
  });

  it("カスタム絵文字は画像（wsrv の 64px・全フレーム）。2 回読めなければ :code: の文字", () => {
    const reaction = signed(
      7,
      generateSecretKey(),
      [
        ["e", "d".repeat(64)],
        ["p", me],
        ["emoji", "cat", "https://emoji-a.test/cat.png"],
      ],
      ":cat:",
    );

    renderRow(itemOf(reaction));

    const mark = screen.getByRole("img", { name: "リアクション :cat:" });
    const img = mark.querySelector("img");
    expect(img?.getAttribute("src")).toContain("https://wsrv.nl/");
    expect(img?.getAttribute("src")).toContain("w=64");
    expect(img?.getAttribute("src")).toContain("n=-1");
    if (!img) throw new Error("no img");
    fireEvent.error(img);
    const origin = mark.querySelector("img");
    expect(origin?.getAttribute("src")).toBe("https://emoji-a.test/cat.png");
    if (!origin) throw new Error("no img");
    fireEvent.error(origin);
    expect(mark.querySelector("img")).toBeNull();
    expect(mark).toHaveTextContent(":cat:");
  });

  it("絵文字の画像が http:// なら文字のまま", () => {
    const reaction = signed(
      7,
      generateSecretKey(),
      [
        ["e", "d".repeat(64)],
        ["p", me],
        ["emoji", "cat", "http://emoji-b.test/cat.png"],
      ],
      ":cat:",
    );

    renderRow(itemOf(reaction));

    const mark = screen.getByRole("img", { name: "リアクション :cat:" });
    expect(mark.querySelector("img")).toBeNull();
    expect(mark).toHaveTextContent(":cat:");
  });
});

it("リポストは引用カードに対象の画像を出す（compact でない）", () => {
  const target = myPost("写真 https://i.test/repost.jpg");
  const repost = signed(6, generateSecretKey(), [
    ["e", target.id],
    ["p", me],
  ]);

  renderRow(itemOf(repost));

  expect(screen.getByRole("img", { name: "リポスト" })).toBeInTheDocument();
  expect(screen.getByRole("link", CARD).querySelectorAll("img")).toHaveLength(1);
});

it("Zap は送った人の名前と「⚡ 21」、マークは Zap", async () => {
  const target = myPost("Zap された投稿");
  const zapperKey = withProfile("zed");
  const zapper = getPublicKey(zapperKey);
  const request = JSON.stringify({ kind: 9734, pubkey: zapper, content: "", tags: [["amount", "21000"]] });
  const receipt = signed(9735, generateSecretKey(), [
    ["p", me],
    ["P", zapper],
    ["e", target.id],
    ["description", request],
  ]);

  renderRow(itemOf(receipt));

  expect(screen.getByText("⚡ 21")).toBeInTheDocument();
  expect(await screen.findByRole("link", { name: "zed" })).toBeInTheDocument();
  expect(screen.getByRole("img", { name: "Zap" })).toBeInTheDocument();
});

describe("返信・メンション", () => {
  it("返信は見出しに自分の投稿の抜粋、本体は相手の投稿（返信先の行は無い）。本文を押すと自分の投稿のスレッド", async () => {
    const user = userEvent.setup();
    const target = myPost("自分の\n元の投稿 https://i.test/x.jpg");
    const reply = signed(
      1,
      withProfile("bob"),
      [
        ["e", target.id, "", "reply"],
        ["p", me],
      ],
      "返信の本文",
    );

    const { container, where } = renderRow(itemOf(reply));

    const snippet = screen.getByRole("link", { name: "自分の 元の投稿" });
    expect(threadIdOf(snippet.getAttribute("href"))).toBe(target.id);
    expect(screen.getByText("返信の本文")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "返信" })).toBeInTheDocument();
    expect(
      within(screen.getByRole("group", { name: "操作" })).getByRole("button", { name: "返信" }),
    ).toBeInTheDocument();
    // 返信先の 1 行（「名前: 本文」のリンク）は無い = 対象へのリンクは見出しの 1 本だけ
    const toTarget = [...container.querySelectorAll('a[href^="/e/"]')].filter(
      (a) => threadIdOf(a.getAttribute("href")) === target.id,
    );
    expect(toTarget).toEqual([snippet]);

    await user.click(screen.getByText("返信の本文"));
    expect(threadIdOf(where())).toBe(target.id);
  });

  it("返信の対象がストアに無ければ見出し行を出さない", () => {
    const reply = signed(
      1,
      generateSecretKey(),
      [
        ["e", "e".repeat(64)],
        ["p", me],
      ],
      "対象が無い返信",
    );

    const { container } = renderRow(itemOf(reply));

    expect(screen.getByText("対象が無い返信")).toBeInTheDocument();
    expect(container.querySelectorAll('a[href^="/e/"]')).toHaveLength(0);
  });

  it("メンションは本文を押すとメンションそのもののスレッド", async () => {
    const user = userEvent.setup();
    const mention = signed(1, generateSecretKey(), [["p", me]], "メンションの本文");

    const { where } = renderRow(itemOf(mention));

    expect(screen.getByRole("img", { name: "メンション" })).toBeInTheDocument();
    await user.click(screen.getByText("メンションの本文"));
    expect(threadIdOf(where())).toBe(mention.id);
  });
});

it("名前・引用カードを押すとそれぞれのリンク先へ行き、行の移動は起きない", async () => {
  const user = userEvent.setup();
  const target = myPost("カードの投稿");
  const reaction = signed(
    7,
    withProfile("carol"),
    [
      ["e", target.id],
      ["p", me],
    ],
    "+",
  );

  const name = renderRow(itemOf(reaction));
  await user.click(await screen.findByRole("link", { name: "carol" }));
  expect(name.visited).toHaveLength(1);
  expect(name.where()).toMatch(/^\/p\/npub1/);
  name.unmount();

  const card = renderRow(itemOf(reaction));
  await user.click(screen.getByRole("link", CARD));
  expect(card.visited).toHaveLength(1);
  expect(threadIdOf(card.where())).toBe(target.id);
});

it("[#817] 対象が自分のチャット発言（kind:42）なら、行と時刻は /channels ではなく発言の nevent（kind:42）の /e/", async () => {
  const user = userEvent.setup();
  const channelId = "c".repeat(64);
  const message = finalizeEvent(
    {
      kind: 42,
      created_at: unixNow() - 3600,
      tags: [["e", channelId, "wss://relay.test/", "root"]],
      content: "チャットの発言",
    },
    meKey,
  );
  eventStore.add(message);
  const reaction = signed(
    7,
    withProfile("dave"),
    [
      ["e", message.id],
      ["p", me],
    ],
    "+",
  );

  const { container, where } = renderRow(itemOf(reaction));

  const time = container.querySelector("time")?.closest("a")?.getAttribute("href") ?? "";
  const pointer = decode(time.replace(/^\/e\//, ""));
  expect(pointer.type === "nevent" ? pointer.data : null).toMatchObject({ id: message.id, kind: 42 });
  await user.click(screen.getByRole("img", { name: "リアクション ❤️" }));
  expect(where()).toBe(time);
  expect(where()).not.toMatch(/^\/channels\//);
});
