import { type NostrEvent, verifyEvent } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createTestSigner } from "../../test/fakeSigner";
import type { MediaServer } from "./mediaServer";
import { discoverApiUrl, nip98Header, parseUploadResponse, uploadMedia, uploadToServer } from "./nip96";

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** "Nostr <base64>" の中身のイベント */
function decodeAuth(header: string): NostrEvent {
  expect(header.startsWith("Nostr ")).toBe(true);
  const binary = atob(header.slice("Nostr ".length));
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes)) as NostrEvent;
}

function requestUrl(input: Parameters<typeof fetch>[0]): string {
  return typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
}

const nip96 = (url: string): MediaServer => ({ url, protocol: "nip96" });

const FILE = { blob: new Blob(["png"], { type: "image/png" }), mime: "image/png", name: "a.png" };
const UPLOADED = {
  status: "success",
  nip94_event: {
    tags: [
      ["url", "https://media.example/abc.webp"],
      ["m", "image/webp"],
      ["x", "f".repeat(64)],
      ["dim", "800x600"],
    ],
  },
};

describe("discoverApiUrl", () => {
  it("絶対 URL はそのまま、相対はサーバーにつなぐ。取れなければ null", async () => {
    fetchMock.mockResolvedValueOnce(json({ api_url: "https://api.example/upload" }));
    expect(await discoverApiUrl("https://s.example")).toBe("https://api.example/upload");
    expect(requestUrl(fetchMock.mock.calls[0][0])).toBe("https://s.example/.well-known/nostr/nip96.json");

    fetchMock.mockResolvedValueOnce(json({ api_url: "/api/v2/media" }));
    expect(await discoverApiUrl("https://s.example")).toBe("https://s.example/api/v2/media");
    fetchMock.mockResolvedValueOnce(json({ api_url: "api/v2/media" }));
    expect(await discoverApiUrl("https://s.example")).toBe("https://s.example/api/v2/media");

    fetchMock.mockResolvedValueOnce(json({ api_url: " " }));
    expect(await discoverApiUrl("https://s.example")).toBeNull();
    fetchMock.mockResolvedValueOnce(new Response("error code: 522", { status: 522 }));
    expect(await discoverApiUrl("https://s.example")).toBeNull();
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    expect(await discoverApiUrl("https://s.example")).toBeNull();
  });
});

describe("parseUploadResponse", () => {
  it("nip94_event.tags の url を優先し、無ければトップレベルの url", () => {
    expect(parseUploadResponse(JSON.stringify(UPLOADED))).toEqual({
      url: "https://media.example/abc.webp",
      tags: UPLOADED.nip94_event.tags,
    });
    expect(parseUploadResponse(JSON.stringify({ url: "https://m.example/x.png" }))).toEqual({
      url: "https://m.example/x.png",
      tags: [],
    });
  });

  it("URL が無い・JSON でないものは null", () => {
    expect(parseUploadResponse(JSON.stringify({ status: "error", message: "too large" }))).toBeNull();
    expect(parseUploadResponse("<html>")).toBeNull();
    expect(parseUploadResponse("null")).toBeNull();
  });
});

describe("nip98Header", () => {
  it("kind:27235（content 空・u / method）を署名して base64 にする", async () => {
    const { signer, pubkey } = createTestSigner();
    const event = decodeAuth(await nip98Header(signer, "https://api.example/upload", "POST"));
    expect(event).toMatchObject({
      kind: 27235,
      pubkey,
      content: "",
      tags: [
        ["u", "https://api.example/upload"],
        ["method", "POST"],
      ],
    });
    expect(verifyEvent(event)).toBe(true);
  });
});

describe("uploadToServer", () => {
  it("api_url へ multipart（part 名 file）を POST し、Authorization の u は api_url", async () => {
    const { signer } = createTestSigner();
    fetchMock
      .mockResolvedValueOnce(json({ api_url: "https://api.example/upload" }))
      .mockResolvedValueOnce(json(UPLOADED));

    const result = await uploadToServer("https://s.example/", FILE, signer);

    expect(result?.url).toBe("https://media.example/abc.webp");
    const [input, init] = fetchMock.mock.calls[1];
    expect(requestUrl(input)).toBe("https://api.example/upload");
    expect(init?.method).toBe("POST");
    const auth = decodeAuth(new Headers(init?.headers).get("Authorization") ?? "");
    expect(auth.tags).toContainEqual(["u", "https://api.example/upload"]);
    const form = init?.body;
    if (!(form instanceof FormData)) throw new Error("body is not FormData");
    const part = form.get("file") as File;
    expect(part.name).toBe("a.png");
    expect(part.type).toBe("image/png");
  });

  it("ディスカバリに失敗したら <server>/api/v1/media へ送る", async () => {
    const { signer } = createTestSigner();
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch")).mockResolvedValueOnce(json(UPLOADED));
    await uploadToServer("https://s.example", FILE, signer);
    expect(requestUrl(fetchMock.mock.calls[1][0])).toBe("https://s.example/api/v1/media");
  });
});

describe("uploadMedia", () => {
  it("失敗したサーバーは飛ばして次を試す。全滅なら null", async () => {
    const { signer } = createTestSigner();
    fetchMock.mockImplementation(async (input) => {
      const url = requestUrl(input);
      if (url.startsWith("https://down.example")) throw new TypeError("Failed to fetch");
      if (url.endsWith("nip96.json")) return json({ api_url: "/upload" });
      return json(UPLOADED);
    });
    const result = await uploadMedia(
      FILE,
      [nip96("https://down.example"), nip96("https://up.example")],
      signer,
    );
    expect(result?.url).toBe("https://media.example/abc.webp");
    expect(fetchMock.mock.calls.map(([input]) => requestUrl(input))).toEqual([
      "https://down.example/.well-known/nostr/nip96.json",
      "https://down.example/api/v1/media",
      "https://up.example/.well-known/nostr/nip96.json",
      "https://up.example/upload",
    ]);

    expect(await uploadMedia(FILE, [nip96("https://down.example")], signer)).toBeNull();
  });

  it("[#873] Blossom の送信先は <server>/upload へ PUT する（NIP-96 のディスカバリはしない）", async () => {
    const { signer } = createTestSigner();
    fetchMock.mockImplementation(async () =>
      json({ url: "https://cdn.example/x.png", sha256: "0".repeat(64) }),
    );
    const result = await uploadMedia(FILE, [{ url: "https://b.example", protocol: "blossom" }], signer);
    // sha256 が違うので採用しない（記述子の照合は blossom.test.ts）。送り先だけ見る
    expect(result).toBeNull();
    expect(fetchMock.mock.calls.map(([input, init]) => [requestUrl(input), init?.method])).toEqual([
      ["https://b.example/upload", "PUT"],
    ]);
  });

  it("中止されたら次のサーバーへ進まず AbortError", async () => {
    const { signer } = createTestSigner();
    const ac = new AbortController();
    fetchMock.mockImplementation(async (input) => {
      if (requestUrl(input).endsWith("nip96.json")) return json({ api_url: "/upload" });
      ac.abort();
      throw new DOMException("aborted", "AbortError");
    });
    await expect(
      uploadMedia(FILE, [nip96("https://a.example"), nip96("https://b.example")], signer, ac.signal),
    ).rejects.toThrow();
    expect(fetchMock.mock.calls.some(([input]) => requestUrl(input).startsWith("https://b.example"))).toBe(
      false,
    );
  });
});
