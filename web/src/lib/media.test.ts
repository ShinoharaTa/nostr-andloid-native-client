import { finalizeEvent, generateSecretKey, type NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import { extractMedia, mediaKindOf, trimUrlTail, urlExtension, urlPositions, youtubeId } from "./media";

function note(content: string, tags: string[][] = []): NostrEvent {
  return finalizeEvent({ kind: 1, created_at: 1_800_000_000, tags, content }, generateSecretKey());
}

describe("urlExtension", () => {
  it("クエリ・フラグメントを除いた最後のパス要素の拡張子を小文字で返す", () => {
    expect(urlExtension("https://a/b/c.JPG?x=1#f")).toBe("jpg");
    expect(urlExtension("https://a/b/")).toBe("");
  });
});

describe("trimUrlTail", () => {
  it("末尾の . , ! ? と閉じ括弧を落とし、拡張子の . は残す（tokenize.ts の URL_TAIL と同じ集合。挙動2.3）", () => {
    expect(trimUrlTail("https://x.co/a.")).toBe("https://x.co/a");
    expect(trimUrlTail("https://x.co/a?q=1,")).toBe("https://x.co/a?q=1");
    expect(trimUrlTail("https://x.co/a.jpg")).toBe("https://x.co/a.jpg");
    expect(trimUrlTail("(https://x.co/a)")).toBe("(https://x.co/a");
    // : は URL_TAIL に含まれないので落とさない（ポート番号等を壊さないため）
    expect(trimUrlTail("https://x.co:8080")).toBe("https://x.co:8080");
  });
});

describe("mediaKindOf", () => {
  it.each(["png", "webp", "avif", "bmp"])(".%s は image", (ext) => {
    expect(mediaKindOf(`https://x.co/a.${ext}`)).toBe("image");
  });

  it.each(["mp4", "m4v", "mov", "webm"])(".%s は video", (ext) => {
    expect(mediaKindOf(`https://x.co/a.${ext}`)).toBe("video");
  });

  it(".svg と .mkv は対象外、http:// は可、ftp:// は対象外", () => {
    expect(mediaKindOf("https://x.co/a.svg")).toBeNull();
    expect(mediaKindOf("https://x.co/a.mkv")).toBeNull();
    expect(mediaKindOf("http://x.co/a.jpg")).toBe("image");
    expect(mediaKindOf("ftp://x.co/a.jpg")).toBeNull();
  });
});

describe("youtubeId", () => {
  it("watch / youtu.be / shorts / embed の 4 形式から ID を取り、他ドメインは null（ネイティブ EmbedTest と同じ）", () => {
    expect(youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).toBe("dQw4w9WgXcQ");
    expect(youtubeId("https://youtu.be/dQw4w9WgXcQ")).toBe("dQw4w9WgXcQ");
    expect(youtubeId("https://www.youtube.com/shorts/dQw4w9WgXcQ")).toBe("dQw4w9WgXcQ");
    expect(youtubeId("https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0")).toBe("dQw4w9WgXcQ");
    expect(youtubeId("https://example.com/watch?v=dQw4w9WgXcQ")).toBeNull();
  });
});

describe("extractMedia", () => {
  // applesauce の Tokens.link はホストにドットを要求するため、テストの URL は i.test / v.test にする
  const content =
    "a https://i.test/1.jpg https://i.test/1.jpg https://v.test/1.mp4 https://youtu.be/dQw4w9WgXcQ https://www.youtube.com/watch?v=dQw4w9WgXcQ";

  it("画像・動画・YouTube を出現順・重複なしで振り分け、imeta の寸法・blurhash・alt を添える", () => {
    const media = extractMedia(
      note(content, [
        [
          "imeta",
          "url https://i.test/1.jpg",
          "dim 1920x1080",
          "blurhash LEHV6nWB2yk8pyo0adR*.7kCMdnj",
          "alt cat",
        ],
      ]),
    );

    expect(media.images).toEqual([
      {
        url: "https://i.test/1.jpg",
        dim: { w: 1920, h: 1080 },
        blurhash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
        alt: "cat",
      },
    ]);
    expect(media.videos).toEqual([{ url: "https://v.test/1.mp4" }]);
    expect(media.youtube).toEqual([{ url: "https://youtu.be/dQw4w9WgXcQ", id: "dQw4w9WgXcQ" }]);
  });

  it.each(["0x10", "abc"])("dim %s は寸法にしない", (dim) => {
    const media = extractMedia(note(content, [["imeta", "url https://i.test/1.jpg", `dim ${dim}`]]));
    expect(media.images[0]).not.toHaveProperty("dim");
  });

  it("同じイベントで 2 回呼ぶと同じオブジェクトを返す", () => {
    const event = note(content);
    expect(extractMedia(event)).toBe(extractMedia(event));
  });

  it("imeta にしか無い URL は対象にしない", () => {
    const media = extractMedia(note("本文だけ", [["imeta", "url https://i.test/only.jpg"]]));
    expect(media).toEqual({ images: [], videos: [], youtube: [] });
  });
});

describe("urlPositions", () => {
  it("末尾の句読点を落とした URL ごとに、最初に出た位置を返す", () => {
    const content = "a https://o.test/x。 b https://v.test/1.mp4 https://o.test/x";

    expect([...urlPositions(content)]).toEqual([
      ["https://o.test/x", 2],
      ["https://v.test/1.mp4", content.indexOf("https://v.test/1.mp4")],
    ]);
  });
});
