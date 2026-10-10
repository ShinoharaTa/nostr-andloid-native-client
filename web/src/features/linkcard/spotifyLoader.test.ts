import { IDBFactory, IDBKeyRange } from "fake-indexeddb";
import { describe, expect, it, vi } from "vitest";
import { OGP_TTL_NG_SEC, OGP_TTL_OK_SEC } from "../../db/ogpCache";
import { createDatabase, type NostrismDb } from "../../db/schema";
import type { OgpLoader } from "./ogpLoader";
import { canonicalSpotifyUrl, createSpotifyCardLoader, ogpFromSpotifyOembed } from "./spotifyLoader";

const NOW = 1_800_000_000;
const TRACK = "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8";
const BODY = {
  title: "Never Gonna Give You Up",
  thumbnail_url: "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02abc",
  provider_name: "Spotify",
  type: "rich",
};
const CARD = {
  url: TRACK,
  title: "Never Gonna Give You Up",
  image: "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02abc",
  siteName: "Spotify",
};

function testDb() {
  return createDatabase({ name: `sp-${crypto.randomUUID()}`, indexedDB: new IDBFactory(), IDBKeyRange });
}

function setup(options: { db?: NostrismDb | null; response?: () => Promise<Response> } = {}) {
  const fetchImpl = vi.fn<typeof fetch>(options.response ?? (async () => Response.json(BODY)));
  const fallback = {
    peek: vi.fn<OgpLoader["peek"]>(() => undefined),
    load: vi.fn<OgpLoader["load"]>(async (url) => ({ url, title: "Spotify – Web Player" })),
  };
  let now = NOW;
  const loader = createSpotifyCardLoader({
    fetch: fetchImpl,
    db: () => options.db ?? null,
    now: () => now,
    fallback,
  });
  return { loader, fetchImpl, fallback, setNow: (v: number) => (now = v) };
}

describe("canonicalSpotifyUrl", () => {
  it.each([
    [TRACK, TRACK],
    [`${TRACK}?si=abc`, TRACK],
    [`${TRACK}#x`, TRACK],
    [`${TRACK}/`, TRACK],
    ["http://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8", TRACK],
    ["https://open.spotify.com/intl-ja/track/4PTG3Z6ehGkBFwjybzWkR8?si=1", TRACK],
    [
      "https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX",
      "https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX",
    ],
    [
      "https://open.spotify.com/show/2MAi0BvDc6GTFvKFPXnkCL",
      "https://open.spotify.com/show/2MAi0BvDc6GTFvKFPXnkCL",
    ],
  ])("%s → %s", (input, expected) => {
    expect(canonicalSpotifyUrl(input)).toBe(expected);
  });

  it.each([
    "https://open.spotify.com/",
    "https://open.spotify.com/user/spotify",
    "https://open.spotify.com/track/short",
    "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8/extra",
    "https://open.spotify.com.evil.example/track/4PTG3Z6ehGkBFwjybzWkR8",
    "spotify:track:4PTG3Z6ehGkBFwjybzWkR8",
    "not a url",
  ])("%s は対象外（null）", (input) => {
    expect(canonicalSpotifyUrl(input)).toBeNull();
  });
});

describe("ogpFromSpotifyOembed", () => {
  it("title・thumbnail_url・provider_name をカードのタイトル・画像・サイト名にする", () => {
    expect(ogpFromSpotifyOembed(BODY, TRACK)).toEqual(CARD);
  });

  it("provider_name が無ければサイト名は Spotify。http(s) 以外の thumbnail_url は使わない", () => {
    expect(ogpFromSpotifyOembed({ title: "曲", thumbnail_url: "javascript:alert(1)" }, TRACK)).toEqual({
      url: TRACK,
      title: "曲",
      siteName: "Spotify",
    });
  });

  it("タイトルもジャケットも無い・JSON の形が違うなら null", () => {
    expect(ogpFromSpotifyOembed({ provider_name: "Spotify" }, TRACK)).toBeNull();
    expect(ogpFromSpotifyOembed(null, TRACK)).toBeNull();
    expect(ogpFromSpotifyOembed("x", TRACK)).toBeNull();
  });
});

describe("createSpotifyCardLoader", () => {
  it("/api/oembed?url=<正規化した URL> を同一オリジンで取り、oEmbed からカードを作る", async () => {
    const { loader, fetchImpl, fallback } = setup();
    await expect(
      loader.load(`https://open.spotify.com/intl-ja/track/4PTG3Z6ehGkBFwjybzWkR8?si=abc`),
    ).resolves.toEqual(CARD);
    expect(fetchImpl).toHaveBeenCalledWith(`/api/oembed?url=${encodeURIComponent(TRACK)}`, {
      credentials: "same-origin",
    });
    expect(fallback.load).not.toHaveBeenCalled();
  });

  it("メモリに覚え、同じ URL（クエリ違いも）の同時の取得は 1 本にまとめる。peek でも読める", async () => {
    const { loader, fetchImpl } = setup();
    expect(loader.peek(TRACK)).toBeUndefined();
    await Promise.all([loader.load(TRACK), loader.load(`${TRACK}?si=1`)]);
    await loader.load(TRACK);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(loader.peek(`${TRACK}?si=2`)).toEqual(CARD);
  });

  it("oEmbed に渡せない Spotify の URL（/user/… 等）は従来の OGP の取得口に渡す", async () => {
    const { loader, fetchImpl, fallback } = setup();
    const user = "https://open.spotify.com/user/spotify";
    await expect(loader.load(user)).resolves.toEqual({ url: user, title: "Spotify – Web Player" });
    loader.peek(user);
    expect(fallback.load).toHaveBeenCalledWith(user);
    expect(fallback.peek).toHaveBeenCalledWith(user);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("DB（ogpCache の spotify-oembed:<URL>）に書き、TTL 内なら通信しない。成功 7 日・失敗 1 日", async () => {
    const db = testDb();
    const first = setup({ db });
    await first.loader.load(TRACK);
    await vi.waitFor(async () =>
      expect(await db.ogpCache.get(`spotify-oembed:${TRACK}`)).toEqual({
        url: `spotify-oembed:${TRACK}`,
        fetchedAt: NOW,
        ok: true,
        title: CARD.title,
        image: CARD.image,
        siteName: "Spotify",
      }),
    );

    const second = setup({ db });
    await expect(second.loader.load(TRACK)).resolves.toEqual(CARD);
    expect(second.fetchImpl).not.toHaveBeenCalled();

    const stale = setup({ db });
    stale.setNow(NOW + OGP_TTL_OK_SEC);
    await stale.loader.load(TRACK);
    expect(stale.fetchImpl).toHaveBeenCalledTimes(1);

    const failDb = testDb();
    const failing = setup({ db: failDb, response: async () => new Response("x", { status: 502 }) });
    await expect(failing.loader.load(TRACK)).resolves.toBeNull();
    await vi.waitFor(async () =>
      expect(await failDb.ogpCache.get(`spotify-oembed:${TRACK}`)).toMatchObject({
        ok: false,
        fetchedAt: NOW,
      }),
    );
    const retry = setup({ db: failDb });
    await expect(retry.loader.load(TRACK)).resolves.toBeNull();
    expect(retry.fetchImpl).not.toHaveBeenCalled();
    const retryLater = setup({ db: failDb });
    retryLater.setNow(NOW + OGP_TTL_NG_SEC);
    await expect(retryLater.loader.load(TRACK)).resolves.toEqual(CARD);
  });

  it("通信できなければ null で、DB に残さない", async () => {
    const db = testDb();
    const offline = setup({
      db,
      response: async () => {
        throw new TypeError("offline");
      },
    });
    await expect(offline.loader.load(TRACK)).resolves.toBeNull();
    expect(await db.ogpCache.get(`spotify-oembed:${TRACK}`)).toBeUndefined();
  });
});
