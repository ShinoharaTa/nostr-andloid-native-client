import { screen } from "@testing-library/react";
import { decode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { renderWithRouter } from "../../test/renderWithRouter";
import { resetChannelsForTest, useChannels } from "../chat/channels";
import { QuoteCard } from "./QuoteCard";

const CARD = { name: "引用元の投稿を開く" } as const;

function storedWithImages() {
  const event = finalizeEvent(
    {
      kind: 1,
      created_at: unixNow(),
      tags: [],
      content: "写真 2 枚 https://i.test/1.jpg https://i.test/2.png",
    },
    generateSecretKey(),
  );
  eventStore.add(event);
  return event;
}

it("既定では引用元の画像をサムネイルで出す", () => {
  const quoted = storedWithImages();
  renderWithRouter(<QuoteCard pointer={{ id: quoted.id }} encoded={null} />);

  const card = screen.getByRole("link", CARD);
  expect(card.querySelectorAll("img")).toHaveLength(2);
});

it("compact ではメディアを出さず、本文は出す（#460）", () => {
  const quoted = storedWithImages();
  renderWithRouter(<QuoteCard pointer={{ id: quoted.id }} encoded={null} compact />);

  const card = screen.getByRole("link", CARD);
  expect(card.querySelectorAll("img")).toHaveLength(0);
  expect(card).toHaveTextContent("写真 2 枚");
});

describe("[#818] チャンネル作成（kind:40）の引用", () => {
  /** /api/nchan/channels の応答（既定は空の一覧） */
  let apiRows: Record<string, unknown>[] = [];

  beforeEach(() => {
    apiRows = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ data: apiRows }))),
    );
  });

  afterEach(() => {
    resetChannelsForTest();
    vi.unstubAllGlobals();
  });

  function storedChannel(meta: Record<string, unknown>) {
    const event = finalizeEvent(
      { kind: 40, created_at: unixNow(), tags: [], content: JSON.stringify(meta) },
      generateSecretKey(),
    );
    eventStore.add(event);
    return event;
  }

  it("content の JSON ではなく、画像（無ければ頭文字）・名前・説明のカード。押すとその kind:40 の /e/", () => {
    const created = storedChannel({ name: "Nostr 雑談", about: "なんでも話す部屋", picture: "" });
    renderWithRouter(<QuoteCard pointer={{ id: created.id }} encoded={null} />);

    const card = screen.getByRole("link", CARD);
    expect(card).toHaveTextContent("Nostr 雑談");
    expect(card).toHaveTextContent("なんでも話す部屋");
    expect(card).not.toHaveTextContent("{");
    expect(card).toHaveTextContent("N");
    expect(card.querySelectorAll("img")).toHaveLength(0);
    const href = card.getAttribute("href") ?? "";
    const decoded = decode(href.replace(/^\/e\//, ""));
    expect(decoded.type === "nevent" ? decoded.data.id : null).toBe(created.id);
  });

  it("チャンネル一覧にあれば一覧の中身（kind:41 の更新後）を使う", () => {
    const created = storedChannel({ name: "古い名前", about: "古い説明", picture: "" });
    useChannels.setState({
      channels: [
        {
          id: created.id,
          name: "新しい名前",
          about: "新しい説明",
          picture: "https://i.test/icon.png",
          relays: [],
          createdAt: created.created_at,
          lastAt: created.created_at,
        },
      ],
    });
    renderWithRouter(<QuoteCard pointer={{ id: created.id }} encoded={null} />);

    const card = screen.getByRole("link", CARD);
    expect(card).toHaveTextContent("新しい名前");
    expect(card).toHaveTextContent("新しい説明");
    expect(card).not.toHaveTextContent("古い名前");
    expect(card.querySelectorAll("img")).toHaveLength(1);
  });

  it("一覧がまだ無ければ /api/nchan/channels を取りに行き、届いたら一覧の中身に替える", async () => {
    const created = storedChannel({ name: "古い名前", about: "", picture: "" });
    apiRows = [
      {
        id: created.id,
        content: JSON.stringify({ name: "届いた名前", about: "届いた説明" }),
        created_at: created.created_at,
      },
    ];
    renderWithRouter(<QuoteCard pointer={{ id: created.id }} encoded={null} />);

    expect(screen.getByRole("link", CARD)).toHaveTextContent("古い名前");
    expect(vi.mocked(fetch)).toHaveBeenCalledWith("/api/nchan/channels", expect.anything());
    expect(await screen.findByText("届いた名前")).toBeInTheDocument();
    expect(screen.getByRole("link", CARD)).toHaveTextContent("届いた説明");
  });

  it("名前が空・content が JSON でなければ「パブリックチャット」", () => {
    const created = finalizeEvent(
      { kind: 40, created_at: unixNow(), tags: [], content: "not json" },
      generateSecretKey(),
    );
    eventStore.add(created);
    renderWithRouter(<QuoteCard pointer={{ id: created.id }} encoded={null} compact />);

    const card = screen.getByRole("link", CARD);
    expect(card).toHaveTextContent("パブリックチャット");
    expect(card).not.toHaveTextContent("not json");
  });
});
