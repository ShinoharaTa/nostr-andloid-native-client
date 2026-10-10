import { render, screen } from "@testing-library/react";
import { finalizeEvent, generateSecretKey, type NostrEvent } from "nostr-tools/pure";
import { afterEach, expect, it, vi } from "vitest";
import { DEFAULT_EMBED_PREFS, useEmbedPrefs } from "./embedPrefs";
import { LinkCards } from "./LinkCard";
import styles from "./LinkCard.module.css";
import { useLinkCards } from "./useLinkCards";

afterEach(() => {
  useEmbedPrefs.setState(DEFAULT_EMBED_PREFS);
  vi.unstubAllGlobals();
});

function post(content: string): NostrEvent {
  return finalizeEvent({ kind: 1, created_at: 1_800_000_000, tags: [], content }, generateSecretKey());
}

function Cards({ event }: { event: NostrEvent }) {
  const { cards } = useLinkCards(event, true);
  return <LinkCards cards={cards} />;
}

/** /api/oembed は Spotify の oEmbed、/api/og は og:title を返す fetch */
function stubFetch() {
  const fetchMock = vi.fn<typeof fetch>(async (input) =>
    String(input).startsWith("/api/oembed")
      ? Response.json({
          title: "Never Gonna Give You Up",
          thumbnail_url: "https://i.scdn.co/image/ab67616d00001e02",
          provider_name: "Spotify",
        })
      : new Response('<head><meta property="og:title" content="記事"></head>'),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

it("Spotify のリンクは OGP の代わりに /api/oembed からカード（サイト名 Spotify・タイトル・ジャケット）を作る", async () => {
  const fetchMock = stubFetch();
  const url = "https://open.spotify.com/intl-ja/track/LinkCardsTrack00000001?si=abc";
  const { container } = render(<Cards event={post(`聴いてる ${url}`)} />);

  const card = await screen.findByRole("link", { name: /Never Gonna Give You Up/ });
  expect(card).toHaveAttribute("href", url);
  expect(card).toHaveTextContent("Spotify");
  expect(container.querySelector(`.${styles.thumb}`)?.getAttribute("src")).toContain("ab67616d00001e02");
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(String(fetchMock.mock.calls[0][0])).toBe(
    `/api/oembed?url=${encodeURIComponent("https://open.spotify.com/track/LinkCardsTrack00000001")}`,
  );
});

it("Spotify と他のリンクが混ざっていれば、それぞれ oEmbed と OGP から取る", async () => {
  const fetchMock = stubFetch();
  const spotify = "https://open.spotify.com/album/LinkCardsAlbum00000001";
  const page = "https://linkcards.test/article";
  render(<Cards event={post(`${spotify} ${page}`)} />);

  expect(await screen.findByRole("link", { name: /Never Gonna Give You Up/ })).toHaveAttribute(
    "href",
    spotify,
  );
  expect(await screen.findByRole("link", { name: /記事/ })).toHaveAttribute("href", page);
  expect(fetchMock.mock.calls.map(([input]) => String(input)).sort()).toEqual([
    `/api/oembed?url=${encodeURIComponent(spotify)}`,
    `/api/og?url=${encodeURIComponent(page)}`,
  ]);
});

it("埋め込み設定 spotify が OFF なら Spotify のカードは取りに行かない", () => {
  const fetchMock = stubFetch();
  useEmbedPrefs.setState({ spotify: false });
  const { container } = render(
    <Cards event={post("https://open.spotify.com/track/LinkCardsTrack00000002")} />,
  );
  expect(container.getElementsByClassName(styles.card)).toHaveLength(0);
  expect(fetchMock).not.toHaveBeenCalled();
});
