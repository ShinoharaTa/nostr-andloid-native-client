import { fireEvent, screen } from "@testing-library/react";
import { naddrEncode, neventEncode, noteEncode, npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { afterEach, expect, it } from "vitest";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { renderWithRouter } from "../../test/renderWithRouter";
import { useNyanMode } from "../../ui/nyan";
import { NoteContent } from "./NoteContent";

function note(content: string, tags: string[][] = []): NostrEvent {
  return finalizeEvent({ kind: 1, created_at: 1_800_000_000, tags, content }, generateSecretKey());
}

const ID = "a".repeat(64);

afterEach(() => {
  useNyanMode.setState({ mode: "off" });
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

it("テキスト・リンク・#タグ・メンション（名前解決）を要素にする", async () => {
  const aliceKey = generateSecretKey();
  const alice = getPublicKey(aliceKey);
  eventStore.add(
    finalizeEvent(
      {
        kind: 0,
        created_at: 1_800_000_000,
        tags: [],
        content: JSON.stringify({ name: "alice", display_name: "Alice" }),
      },
      aliceKey,
    ),
  );

  const { container } = renderWithRouter(
    <NoteContent
      event={note(`こんにちは https://example.com/page #nostr\nnostr:${npubEncode(alice)} おわり`, [
        ["t", "nostr"],
      ])}
    />,
  );

  const link = screen.getByRole("link", { name: "https://example.com/page" });
  expect(link).toHaveAttribute("href", "https://example.com/page");
  expect(link).toHaveAttribute("target", "_blank");
  expect(link).toHaveAttribute("rel", "noopener noreferrer nofollow ugc");
  expect(screen.getByRole("link", { name: "#nostr" })).toHaveAttribute("href", "/t/nostr");
  expect(await screen.findByRole("link", { name: "@Alice" })).toHaveAttribute(
    "href",
    `/p/${npubEncode(alice)}`,
  );
  expect(container).toHaveTextContent(/^こんにちは https:\/\/example\.com\/page #nostr @Alice おわり$/);
});

it("名前が無いメンションは npub の先頭 12 文字、画像 URL は本文に残さず、HTML は文字として出す", () => {
  const unknown = getPublicKey(generateSecretKey());
  const npub = npubEncode(unknown);
  const { container } = renderWithRouter(
    <NoteContent
      event={note(`<b>bold</b> nostr:${npub}\nhttps://img.example/a.png\nhttps://img.example/b.jpg`)}
    />,
  );

  expect(container.querySelector("b")).toBeNull();
  expect(container).toHaveTextContent("<b>bold</b>");
  expect(screen.getByRole("link", { name: `@${npub.slice(0, 12)}…` })).toHaveAttribute("href", `/p/${npub}`);
  expect(container).not.toHaveTextContent("img.example");
  expect(container.querySelector("img")).toBeNull();
  expect(container.querySelectorAll("a")).toHaveLength(1);
});

it("URL 末尾の句読点はリンクに含めず、後ろに文字として出す", () => {
  const { container } = renderWithRouter(<NoteContent event={note("見て https://x.co/a.")} />);

  const link = screen.getByRole("link", { name: "https://x.co/a" });
  expect(link).toHaveAttribute("href", "https://x.co/a");
  expect(link.nextSibling?.textContent).toBe(".");
  expect(container).toHaveTextContent(/^見て https:\/\/x\.co\/a\.$/);
});

it("note / naddr は ↗ + 先頭 12 文字のアプリ内リンク（naddr も /e/:ref が受ける。#534）", () => {
  const encodedNote = noteEncode(ID);
  const naddr = naddrEncode({
    kind: 30023,
    pubkey: getPublicKey(generateSecretKey()),
    identifier: "article",
  });
  renderWithRouter(<NoteContent event={note(`nostr:${encodedNote} と nostr:${naddr}`)} />);

  const noteLink = screen.getByRole("link", { name: `↗${encodedNote.slice(0, 12)}…` });
  expect(noteLink).toHaveAttribute("href", `/e/${encodedNote}`);
  const articleLink = screen.getByRole("link", { name: `↗${naddr.slice(0, 12)}…` });
  expect(articleLink).toHaveAttribute("href", `/e/${naddr}`);
});

it("quote バリアントでは note / naddr の装飾だけでリンクにしない", () => {
  const naddr = naddrEncode({
    kind: 30023,
    pubkey: getPublicKey(generateSecretKey()),
    identifier: "article",
  });
  renderWithRouter(<NoteContent event={note(`nostr:${naddr}`)} variant="quote" />);

  const article = screen.getByText(`↗${naddr.slice(0, 12)}…`);
  expect(article.tagName).toBe("SPAN");
  expect(article.closest("a")).toBeNull();
});

it("カスタム絵文字はプロキシ経由の画像にし、失敗したら元 URL で取り直す。タグに無い :code: は文字のまま", () => {
  const { container } = renderWithRouter(
    <NoteContent event={note(":cat: hi :dog:", [["emoji", "cat", "https://e/cat.png"]])} />,
  );

  const images = container.querySelectorAll("img");
  expect(images).toHaveLength(1);
  const img = images[0];
  expect(img).toHaveAttribute("alt", ":cat:");
  const src = img.getAttribute("src") ?? "";
  expect(src.startsWith("https://wsrv.nl/?url=")).toBe(true);
  expect(src).toContain("&h=96&");
  expect(src).toContain("n=-1");
  expect(container).toHaveTextContent(":dog:");

  fireEvent.error(img);

  expect(container.querySelector("img")).toHaveAttribute("src", "https://e/cat.png");
});

it('variant="quote" はリンク・メンション・タグを <a> にせず、URL を 28 文字に縮める', () => {
  const { container } = renderWithRouter(
    <NoteContent
      event={note(
        `https://www.example.com/path/that/is/long #tag nostr:${npubEncode(getPublicKey(generateSecretKey()))} nostr:${noteEncode(ID)}`,
      )}
      variant="quote"
    />,
  );

  expect(container.querySelector("a")).toBeNull();
  expect(screen.getByText("example.com/path/that/is/lon…")).toBeInTheDocument();
  expect(screen.getByText("#tag")).toBeInTheDocument();
});

it("hideMention に一致する参照を描かず、直後の改行も消す", () => {
  const nevent = neventEncode({ id: ID });
  const { container } = renderWithRouter(
    <NoteContent event={note(`見て\nnostr:${nevent}\n続き`)} hideMention={nevent} />,
  );

  expect(screen.queryByText(`↗${nevent.slice(0, 12)}…`)).toBeNull();
  expect(container.textContent).toBe("見て\n続き");
});

// ---- [#540] にゃんモード ----

it("全員モードでは本文のプレーンテキストだけ nyaize し、URL・メンション・タグ・shortcode は変わらない", async () => {
  useNyanMode.setState({ mode: "all" });
  const aliceKey = generateSecretKey();
  const alice = getPublicKey(aliceKey);
  eventStore.add(
    finalizeEvent(
      { kind: 0, created_at: 1_800_000_000, tags: [], content: JSON.stringify({ name: "なな" }) },
      aliceKey,
    ),
  );

  const { container } = renderWithRouter(
    <NoteContent
      event={note(`みんな見て https://na-example.com/a #nanika nostr:${npubEncode(alice)} :nabe:`, [
        ["emoji", "nabe", "https://e/nabe.png"],
      ])}
    />,
  );

  expect(await screen.findByRole("link", { name: "@なな" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "https://na-example.com/a" })).toHaveAttribute(
    "href",
    "https://na-example.com/a",
  );
  expect(screen.getByRole("link", { name: "#nanika" })).toBeInTheDocument();
  expect(container.querySelector("img")).toHaveAttribute("alt", ":nabe:");
  expect(container).toHaveTextContent(/^みんにゃ見て https:\/\/na-example\.com\/a #nanika @なな$/);
});

it("自分のみモードでは他人の投稿は変わらない", () => {
  useNyanMode.setState({ mode: "self" });
  useSession.setState({ status: "in", method: "nip07", pubkey: getPublicKey(generateSecretKey()) });

  const { container } = renderWithRouter(<NoteContent event={note("みんな元気かな")} />);

  expect(container).toHaveTextContent("みんな元気かな");
});

it("自分のみモードでは自分の投稿は nyaize される", () => {
  const key = generateSecretKey();
  const me = getPublicKey(key);
  useNyanMode.setState({ mode: "self" });
  useSession.setState({ status: "in", method: "nip07", pubkey: me });

  const { container } = renderWithRouter(
    <NoteContent
      event={finalizeEvent({ kind: 1, created_at: 1_800_000_000, tags: [], content: "みんな元気かな" }, key)}
    />,
  );

  expect(container).toHaveTextContent("みんにゃ元気かにゃ");
});

it("オフでは nyaize しない", () => {
  const { container } = renderWithRouter(<NoteContent event={note("みんな元気かな")} />);
  expect(container).toHaveTextContent("みんな元気かな");
});
