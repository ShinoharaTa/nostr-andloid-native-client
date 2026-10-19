import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { NoteMedia as Media, MediaItem } from "../../lib/media";
import { DEFAULT_EMBED_PREFS, setEmbedPref, useEmbedPrefs } from "../linkcard/embedPrefs";
import type { LinkCardState } from "../linkcard/useLinkCards";
import gridStyles from "./ImageGrid.module.css";
import { NoteMedia } from "./NoteMedia";

beforeEach(() => {
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
  return () => vi.restoreAllMocks();
});

afterEach(() => {
  localStorage.clear();
  useEmbedPrefs.setState(DEFAULT_EMBED_PREFS);
});

function items(count: number, prefix: string): MediaItem[] {
  return Array.from({ length: count }, (_, i) => ({ url: `https://m.test/${prefix}${i}` }));
}

function youtube(count: number): Media["youtube"] {
  // 11 文字の動画 ID
  return Array.from({ length: count }, (_, i) => ({
    url: `https://youtu.be/video00000${i}`,
    id: `video00000${i}`,
  }));
}

function media(partial: Partial<Media>): Media {
  return { images: [], videos: [], youtube: [], ...partial };
}

it("画像 → 動画 → YouTube の順に出す", () => {
  const { container } = render(
    <NoteMedia media={media({ images: items(3, "i"), videos: items(1, "v"), youtube: youtube(1) })} />,
  );

  const blocks = [...(container.firstElementChild?.children ?? [])];
  expect(blocks).toHaveLength(3);
  expect(blocks[0]).toHaveClass(gridStyles.grid, gridStyles.cols3);
  expect(blocks[1]).toBe(screen.getByRole("button", { name: "動画を再生" }));
  // YouTube は押すまでサムネ（iframe は出さない。データセーバーに関係なく常に）
  expect(blocks[2]).toBe(screen.getByRole("button", { name: "YouTube を再生" }));
});

it("動画と YouTube は動画を先に数えて合計 4 件まで（画像は数えない）", () => {
  const { unmount } = render(<NoteMedia media={media({ videos: items(3, "v"), youtube: youtube(3) })} />);
  expect(screen.getAllByRole("button", { name: "動画を再生" })).toHaveLength(3);
  expect(screen.getAllByRole("button", { name: "YouTube を再生" })).toHaveLength(1);
  unmount();

  render(<NoteMedia media={media({ images: items(12, "i"), videos: items(5, "v") })} />);
  expect(screen.getAllByRole("button", { name: "動画を再生" })).toHaveLength(4);
  expect(screen.getAllByRole("button", { name: /^画像 \d+ \/ 12 を拡大$/ })).toHaveLength(12);
});

it("embed_video が OFF なら動画を出さない。空いた枠は YouTube に回さない（#532）", () => {
  setEmbedPref("video", false);
  render(<NoteMedia media={media({ videos: items(3, "v"), youtube: youtube(3) })} />);
  expect(screen.queryByRole("button", { name: "動画を再生" })).toBeNull();
  // 動画 3 本が枠 3 つを占めたまま OFF になっているので、YouTube は残り 1 枠のまま
  expect(screen.getAllByRole("button", { name: "YouTube を再生" })).toHaveLength(1);
});

it("embed_youtube が OFF なら YouTube を出さない", () => {
  setEmbedPref("youtube", false);
  render(<NoteMedia media={media({ videos: items(1, "v"), youtube: youtube(1) })} />);
  expect(screen.getAllByRole("button", { name: "動画を再生" })).toHaveLength(1);
  expect(screen.queryByRole("button", { name: "YouTube を再生" })).toBeNull();
});

it("何も無ければ何も描かない", () => {
  const { container } = render(<NoteMedia media={media({})} />);

  expect(container).toBeEmptyDOMElement();
});

it("画像を押すとライトボックスが開き、「閉じる」で消える", async () => {
  render(<NoteMedia media={media({ images: items(2, "i") })} />);
  expect(screen.queryByRole("dialog")).toBeNull();

  await userEvent.click(screen.getByRole("button", { name: "画像 2 / 2 を拡大" }));

  const dialog = screen.getByRole("dialog", { name: "画像" });
  expect(dialog).toHaveTextContent("2 / 2");
  expect(dialog.querySelector("img")).toHaveAttribute("src", "https://m.test/i1");

  await userEvent.click(screen.getByRole("button", { name: "閉じる" }));

  expect(screen.queryByRole("dialog")).toBeNull();
});

function card(url: string, title: string | null): LinkCardState {
  return { url, kind: "ogp", ogp: title === null ? null : { url, title } };
}

it("動画・YouTube・リンクカードは本文の出現順に混ぜ、画像はまとめて先に出す（ネイティブ LinkEmbeds.kt）", () => {
  const content =
    "https://youtu.be/video000000 https://m.test/i0 https://o.test/a https://m.test/v0 https://m.test/i1";
  const { container } = render(
    <NoteMedia
      media={media({ images: items(2, "i"), videos: items(1, "v"), youtube: youtube(1) })}
      cards={[card("https://o.test/a", "記事 A")]}
      content={content}
    />,
  );

  const blocks = [...(container.firstElementChild?.children ?? [])];
  expect(blocks).toHaveLength(4);
  expect(blocks[0]).toHaveClass(gridStyles.grid);
  expect(blocks[1]).toBe(screen.getByRole("button", { name: "YouTube を再生" }));
  expect(blocks[2]).toBe(screen.getByRole("link", { name: /記事 A/ }));
  expect(blocks[3]).toBe(screen.getByRole("button", { name: "動画を再生" }));
});

it("同じ URL が何度も出るときは最初の位置で並べる", () => {
  const content = "https://o.test/a https://m.test/v0 https://o.test/a";
  const { container } = render(
    <NoteMedia
      media={media({ videos: items(1, "v") })}
      cards={[card("https://o.test/a", "記事 A")]}
      content={content}
    />,
  );

  const blocks = [...(container.firstElementChild?.children ?? [])];
  expect(blocks[0]).toBe(screen.getByRole("link", { name: /記事 A/ }));
  expect(blocks[1]).toBe(screen.getByRole("button", { name: "動画を再生" }));
});

it("取れなかった（null）リンクカードは出さず、ほかに何も無ければ何も描かない", () => {
  const { container } = render(
    <NoteMedia media={media({})} cards={[card("https://o.test/a", null)]} content="https://o.test/a" />,
  );

  expect(container).toBeEmptyDOMElement();
});
