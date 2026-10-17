import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createTestSigner } from "../../test/fakeSigner";
import {
  type Attachment,
  humanSize,
  reprocessImages,
  toPostMedia,
  UPLOAD_CONCURRENCY,
  UploadFailedError,
  uploadAttachments,
} from "./attachments";
import { NO_EDIT } from "./imageEdit";
import { uploadMedia } from "./nip96";
import type { ProcessedMedia } from "./processMedia";

vi.mock("./nip96", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./nip96")>();
  return { ...actual, uploadMedia: vi.fn() };
});

beforeEach(() => {
  vi.mocked(uploadMedia).mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

function attachment(i: number, kind: "image" | "video" = "image"): Attachment {
  const name = `${kind}-${i}`;
  const file = new File([name], name, { type: kind === "image" ? "image/png" : "video/mp4" });
  const processed: ProcessedMedia = { blob: file, mime: file.type, name };
  return {
    id: name,
    kind,
    file,
    preview: `blob:${name}`,
    edit: NO_EDIT,
    processed: Promise.resolve(processed),
  };
}

describe("humanSize", () => {
  it("ネイティブと同じ表記（MB は小数 1 桁で切り捨て）", () => {
    expect(humanSize(512)).toBe("512B");
    expect(humanSize(300 * 1024 + 5)).toBe("300KB");
    expect(humanSize(1024 * 1024)).toBe("1.0MB");
    expect(humanSize(Math.floor(1.59 * 1024 * 1024))).toBe("1.5MB");
  });
});

describe("reprocessImages", () => {
  it("画像だけ processed を作り直す（動画はそのまま）", () => {
    const image = attachment(0, "image");
    const video = attachment(1, "video");
    const next = reprocessImages([image, video], 640, 85);
    expect(next[0].processed).not.toBe(image.processed);
    expect(next[1]).toBe(video);
  });
});

describe("toPostMedia", () => {
  const local: ProcessedMedia = {
    blob: new Blob(),
    mime: "image/webp",
    name: "a.webp",
    dim: { w: 1200, h: 900 },
    blurhash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
  };

  it("サーバーの NIP-94 の値を優先し、無ければ手元の値。x はサーバーの値だけ", () => {
    expect(
      toPostMedia(
        "image",
        {
          url: "https://m/a.jpg",
          tags: [
            ["url", "https://m/a.jpg"],
            ["m", "image/jpeg"],
            ["dim", "600x450"],
            ["x", "ab"],
          ],
        },
        local,
      ),
    ).toEqual({
      kind: "image",
      url: "https://m/a.jpg",
      m: "image/jpeg",
      dim: "600x450",
      blurhash: local.blurhash,
      x: "ab",
    });
    expect(toPostMedia("image", { url: "https://m/a.webp", tags: [] }, local)).toEqual({
      kind: "image",
      url: "https://m/a.webp",
      m: "image/webp",
      dim: "1200x900",
      blurhash: local.blurhash,
      x: undefined,
    });
  });
});

describe("uploadAttachments", () => {
  it("順序を保ち、同時は UPLOAD_CONCURRENCY 件まで。1 件ごとに進捗", async () => {
    const { signer } = createTestSigner();
    let running = 0;
    let peak = 0;
    vi.mocked(uploadMedia).mockImplementation(async (file) => {
      running += 1;
      peak = Math.max(peak, running);
      // 後ろのものほど早く終わる
      await new Promise((r) => setTimeout(r, 20 - Number(file.name.split("-")[1])));
      running -= 1;
      return { url: `https://m/${file.name}`, tags: [] };
    });
    const list = Array.from({ length: 8 }, (_, i) => attachment(i));
    const progress: number[] = [];

    const media = await uploadAttachments(list, {
      servers: [{ url: "https://s", protocol: "nip96" }],
      signer,
      onProgress: (done) => progress.push(done),
    });

    expect(media.map((m) => m.url)).toEqual(list.map((a) => `https://m/${a.id}`));
    expect(peak).toBe(UPLOAD_CONCURRENCY);
    expect(progress).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    expect(vi.mocked(uploadMedia).mock.calls[0][1]).toEqual([{ url: "https://s", protocol: "nip96" }]);
  });

  it("1 件でも失敗したら（残りを終えてから）UploadFailedError", async () => {
    const { signer } = createTestSigner();
    vi.mocked(uploadMedia).mockImplementation(async (file) =>
      file.name === "image-1" ? null : { url: `https://m/${file.name}`, tags: [] },
    );
    const progress: number[] = [];
    await expect(
      uploadAttachments([attachment(0), attachment(1), attachment(2)], {
        servers: [{ url: "https://s", protocol: "nip96" }],
        signer,
        onProgress: (done) => progress.push(done),
      }),
    ).rejects.toBeInstanceOf(UploadFailedError);
    expect(progress).toEqual([1, 2, 3]);
  });
});
