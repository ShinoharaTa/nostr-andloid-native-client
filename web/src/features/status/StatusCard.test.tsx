import { screen } from "@testing-library/react";
import { npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { afterEach, describe, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { eventStore } from "../../nostr/store";
import { renderWithRouter } from "../../test/renderWithRouter";
import { DEFAULT_EMBED_PREFS, useEmbedPrefs } from "../linkcard/embedPrefs";
import linkCardStyles from "../linkcard/LinkCard.module.css";
import { StatusCard } from "./StatusCard";

afterEach(() => {
  useEmbedPrefs.setState(DEFAULT_EMBED_PREFS);
  vi.unstubAllGlobals();
});

/** 名前を持つプロフィールを入れ、その鍵を返す */
function withProfile(name: string): Uint8Array {
  const key = generateSecretKey();
  eventStore.add(
    finalizeEvent({ kind: 0, created_at: unixNow(), tags: [], content: JSON.stringify({ name }) }, key),
  );
  return key;
}

function status(
  content: string,
  {
    d = "general",
    tags = [],
    key = generateSecretKey(),
  }: { d?: string; tags?: string[][]; key?: Uint8Array } = {},
): NostrEvent {
  return finalizeEvent({ kind: 30315, created_at: unixNow() - 120, tags: [["d", d], ...tags], content }, key);
}

/** /api/og が og:title を返す fetch */
function stubOgp(title: string) {
  const fetchMock = vi.fn<typeof fetch>(
    async () => new Response(`<head><meta property="og:title" content="${title}"></head>`),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("StatusCard", () => {
  it("名前・本文・種類の印（aria-label）を出す", () => {
    const key = withProfile("alice");
    renderWithRouter(<StatusCard event={status("DEEP BREATH - ROLLY", { d: "music", key })} />);

    expect(screen.getByRole("link", { name: "alice" })).toHaveAttribute(
      "href",
      `/p/${npubEncode(getPublicKey(key))}`,
    );
    expect(screen.getByText("DEEP BREATH - ROLLY")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Now Playing" })).toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "ステータス" })).toBeNull();
  });

  it("general は吹き出しの印（ステータス）", () => {
    renderWithRouter(<StatusCard event={status("作業中")} />);
    expect(screen.getByRole("img", { name: "ステータス" })).toBeInTheDocument();
  });

  it("https の r はサービス名の外部リンク（新しいタブ）", () => {
    stubOgp("曲");
    const url = "https://open.spotify.com/track/status-test-1";
    renderWithRouter(
      <StatusCard event={status("曲名", { d: "music", tags: [["r", url]] })} linkCard={false} />,
    );

    const link = screen.getByRole("link", { name: "Spotify で開く" });
    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute("href", url);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener noreferrer");
    expect(link).toHaveTextContent("Spotify");
  });

  it("spotify: の r はリンクにせず文字のまま出す", () => {
    const { container } = renderWithRouter(
      <StatusCard event={status("曲名", { d: "music", tags: [["r", "spotify:search:abc"]] })} />,
    );

    expect(screen.getByText("spotify:search:abc").closest("a")).toBeNull();
    expect(container.querySelector('a[href^="spotify:"]')).toBeNull();
  });

  it("壊れた URL（hittps://）もリンクにしない", () => {
    renderWithRouter(<StatusCard event={status("予定", { tags: [["r", "hittps://example.test/x"]] })} />);
    expect(screen.getByText("hittps://example.test/x").closest("a")).toBeNull();
  });

  it("p はその人のプロフィール（@名前）へのリンク", () => {
    const friend = withProfile("bob");
    const pubkey = getPublicKey(friend);
    renderWithRouter(<StatusCard event={status("一緒に作業中", { tags: [["p", pubkey]] })} />);

    expect(screen.getByRole("link", { name: "@bob" })).toHaveAttribute("href", `/p/${npubEncode(pubkey)}`);
  });

  it("Spotify の r は oEmbed のカード（ジャケット付き）を出す。埋め込み設定 spotify を OFF にすると出さない（取りにも行かない）", async () => {
    const fetchMock = vi.fn<typeof fetch>(async () =>
      Response.json({
        title: "Spotify の曲のタイトル",
        thumbnail_url: "https://i.scdn.co/image/status-test-2",
        provider_name: "Spotify",
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const url = "https://open.spotify.com/track/StatusCardTrack0000002";
    const event = status("曲名", { d: "music", tags: [["r", url]] });

    const on = renderWithRouter(<StatusCard event={event} />);
    expect(await screen.findByRole("link", { name: /Spotify の曲のタイトル/ })).toHaveAttribute("href", url);
    expect(on.container.querySelector(`.${linkCardStyles.thumb}`)?.getAttribute("src")).toContain(
      "status-test-2",
    );
    expect(String(fetchMock.mock.calls[0][0])).toBe(`/api/oembed?url=${encodeURIComponent(url)}`);
    on.unmount();

    fetchMock.mockClear();
    useEmbedPrefs.setState({ spotify: false });
    const off = renderWithRouter(
      <StatusCard event={status("曲名", { d: "music", tags: [["r", `${url}b`]] })} />,
    );
    expect(off.container.getElementsByClassName(linkCardStyles.card)).toHaveLength(0);
    expect(screen.getByRole("link", { name: "Spotify で開く" })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("期限が無ければ残り時間を出さない。あれば出す", () => {
    const { unmount } = renderWithRouter(<StatusCard event={status("期限なし")} />);
    expect(screen.queryByText(/残り|まもなく終了|まで$/)).toBeNull();
    unmount();

    renderWithRouter(
      <StatusCard
        event={status("期限あり", { d: "music", tags: [["expiration", String(unixNow() + 180)]] })}
      />,
    );
    expect(screen.getByText(/^残り [23] 分$/)).toBeInTheDocument();
  });
});
