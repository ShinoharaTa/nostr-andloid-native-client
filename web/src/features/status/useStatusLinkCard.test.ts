import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { DEFAULT_EMBED_PREFS, useEmbedPrefs } from "../linkcard/embedPrefs";
import { useStatusLinkCard } from "./useStatusLinkCard";

afterEach(() => {
  useEmbedPrefs.setState(DEFAULT_EMBED_PREFS);
  vi.unstubAllGlobals();
});

/** /api/oembed は Spotify の oEmbed、/api/og は og:title を返す fetch */
function stubFetch() {
  const fetchMock = vi.fn<typeof fetch>(async (input) =>
    String(input).startsWith("/api/oembed")
      ? Response.json({
          title: "曲名",
          thumbnail_url: "https://i.scdn.co/image/abc",
          provider_name: "Spotify",
        })
      : new Response(`<head><meta property="og:title" content="ページ"></head>`),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

it("Spotify の r は /api/oembed のタイトル・ジャケット・サイト名でカードにする", async () => {
  const fetchMock = stubFetch();
  const url = "https://open.spotify.com/track/StatusHookTrack0000001?si=abc";
  const { result } = renderHook(() => useStatusLinkCard(url, "spotify"));

  expect(result.current).toEqual({ url, kind: "spotify", ogp: undefined });
  await waitFor(() =>
    expect(result.current?.ogp).toEqual({
      url: "https://open.spotify.com/track/StatusHookTrack0000001",
      title: "曲名",
      image: "https://i.scdn.co/image/abc",
      siteName: "Spotify",
    }),
  );
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(String(fetchMock.mock.calls[0][0])).toBe(
    `/api/oembed?url=${encodeURIComponent("https://open.spotify.com/track/StatusHookTrack0000001")}`,
  );
});

it("Spotify 以外の r は従来どおり /api/og", async () => {
  const fetchMock = stubFetch();
  const url = "https://example.test/status-hook-page";
  const { result } = renderHook(() => useStatusLinkCard(url, "ogp"));

  await waitFor(() => expect(result.current?.ogp).toEqual({ url, title: "ページ" }));
  expect(String(fetchMock.mock.calls[0][0])).toBe(`/api/og?url=${encodeURIComponent(url)}`);
});

it("埋め込み設定 spotify が OFF なら null で、取りに行かない", () => {
  const fetchMock = stubFetch();
  useEmbedPrefs.setState({ spotify: false });
  const { result } = renderHook(() =>
    useStatusLinkCard("https://open.spotify.com/track/StatusHookTrack0000002", "spotify"),
  );
  expect(result.current).toBeNull();
  expect(fetchMock).not.toHaveBeenCalled();
});
