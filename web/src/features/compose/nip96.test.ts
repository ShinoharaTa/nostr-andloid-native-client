import { type NostrEvent, verifyEvent } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createTestSigner } from "../../test/fakeSigner";
import type { MediaServer } from "./mediaServer";
import {
  discoverApiUrl,
  nip98Header,
  parseUploadResponse,
  uploadMedia,
  uploadToServer,
  waitUntilAvailable,
} from "./nip96";

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
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

/** 最終 URL への HEAD の応答（既定は配られている: 200 + image/webp） */
function head(contentType = "image/webp", init: { status?: number; reason?: string } = {}): Response {
  const headers: Record<string, string> = { "Content-Type": contentType };
  if (init.reason) headers["X-Reason"] = init.reason;
  return new Response(null, { status: init.status ?? 200, headers });
}

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
      .mockResolvedValueOnce(json(UPLOADED))
      .mockResolvedValueOnce(head());

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
    fetchMock
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce(json(UPLOADED))
      .mockResolvedValueOnce(head());
    await uploadToServer("https://s.example", FILE, signer);
    expect(requestUrl(fetchMock.mock.calls[1][0])).toBe("https://s.example/api/v1/media");
  });
});

describe("uploadMedia", () => {
  it("失敗したサーバーは飛ばして次を試す。全滅なら null", async () => {
    const { signer } = createTestSigner();
    fetchMock.mockImplementation(async (input, init) => {
      const url = requestUrl(input);
      if (url.startsWith("https://down.example")) throw new TypeError("Failed to fetch");
      if (url.endsWith("nip96.json")) return json({ api_url: "/upload" });
      if (init?.method === "HEAD") return head();
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
      "https://media.example/abc.webp",
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

describe("[#895] NIP-96 の遅延処理（202 / processing_url）", () => {
  const API = "https://s.example/upload";
  const PROCESSING = "https://s.example/upload/abc";
  const FINAL = "https://cdn.example/final.webp";
  const DONE = { status: "success", nip94_event: { tags: [["url", FINAL]] } };

  /** ディスカバリ → POST → processing_url（polls を順に返し、尽きたら最後を繰り返す）→ HEAD（200 + image/webp） */
  function serve(post: () => Response, polls: (() => Response)[]) {
    let i = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const url = requestUrl(input);
      if (url.endsWith("nip96.json")) return json({ api_url: API });
      if (init?.method === "POST") return post();
      if (init?.method === "HEAD") return head();
      const respond = polls[Math.min(i, polls.length - 1)];
      i += 1;
      return respond();
    });
  }

  /** processing_url への GET */
  const pollCalls = (url = PROCESSING) =>
    fetchMock.mock.calls.filter(([input, init]) => requestUrl(input) === url && init?.method === undefined);

  it("202 → processing → processing → 201 の順で待ち、201 の URL を返す（進み具合も渡す）", async () => {
    vi.useFakeTimers();
    const { signer } = createTestSigner();
    serve(
      () =>
        json(
          {
            status: "processing",
            processing_url: PROCESSING,
            nip94_event: { tags: [["url", "https://s.example/tmp.png"]] },
          },
          202,
        ),
      [
        () => json({ status: "processing", percentage: 10 }, 202),
        () => json({ status: "processing", percentage: 60 }),
        () => json(DONE, 201),
      ],
    );
    const onProgress = vi.fn();
    const promise = uploadToServer("https://s.example", FILE, signer, undefined, onProgress);
    await vi.advanceTimersByTimeAsync(5_000);
    const result = await promise;

    expect(result?.url).toBe(FINAL);
    expect(pollCalls()).toHaveLength(3);
    expect(onProgress.mock.calls).toEqual([[10], [60]]);
    // 最終 URL を HEAD で確かめる
    const last = fetchMock.mock.calls.at(-1);
    expect(last && requestUrl(last[0])).toBe(FINAL);
    expect(last?.[1]?.method).toBe("HEAD");
  });

  it("200 でも本文に processing_url があればポーリングに入る", async () => {
    vi.useFakeTimers();
    const { signer } = createTestSigner();
    serve(
      () => json({ status: "success", processing_url: PROCESSING, url: "https://s.example/tmp.png" }),
      [() => json({ status: "processing" }), () => json(DONE, 201)],
    );
    const promise = uploadToServer("https://s.example", FILE, signer);
    await vi.advanceTimersByTimeAsync(5_000);
    expect((await promise)?.url).toBe(FINAL);
    expect(pollCalls()).toHaveLength(2);
  });

  it("同じオリジンの processing_url には NIP-98（GET・その URL）を付け、別オリジンには付けない", async () => {
    const { signer } = createTestSigner();
    serve(() => json({ processing_url: PROCESSING }, 202), [() => json(DONE, 201)]);
    await uploadToServer("https://s.example", FILE, signer);
    const auth = new Headers(pollCalls()[0][1]?.headers).get("Authorization");
    expect(decodeAuth(auth ?? "").tags).toEqual([
      ["u", PROCESSING],
      ["method", "GET"],
    ]);

    fetchMock.mockReset();
    const other = "https://queue.example/abc";
    serve(() => json({ processing_url: other }, 202), [() => json(DONE, 201)]);
    await uploadToServer("https://s.example", FILE, signer);
    expect(pollCalls(other)).toHaveLength(1);
    expect(new Headers(pollCalls(other)[0][1]?.headers).has("Authorization")).toBe(false);
  });

  /** a.example は processing_url を返し、その GET は poll()。b.example は通常の応答 */
  function serveTwo(poll: () => Response) {
    fetchMock.mockImplementation(async (input, init) => {
      const url = requestUrl(input);
      if (url.endsWith("nip96.json")) return json({ api_url: "/upload" });
      if (init?.method === "HEAD") return head();
      if (url.startsWith("https://a.example")) {
        if (init?.method === "POST") return json({ processing_url: "https://a.example/upload/1" }, 202);
        return poll();
      }
      return json(UPLOADED);
    });
  }
  const touchedB = () =>
    fetchMock.mock.calls.some(([input]) => requestUrl(input).startsWith("https://b.example"));

  it("status: error なら失敗にして次のサーバーへ進む", async () => {
    vi.useFakeTimers();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    const { signer } = createTestSigner();
    serveTwo(() => json({ status: "error", message: "transcode failed" }));
    const promise = uploadMedia(FILE, [nip96("https://a.example"), nip96("https://b.example")], signer);
    await vi.advanceTimersByTimeAsync(5_000);
    expect((await promise)?.url).toBe("https://media.example/abc.webp");
    expect(pollCalls("https://a.example/upload/1")).toHaveLength(1);
  });

  it("15 秒たっても終わらなければ失敗にして次のサーバーへ進む", async () => {
    vi.useFakeTimers();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    const { signer } = createTestSigner();
    serveTwo(() => json({ status: "processing", percentage: 1 }));
    const promise = uploadMedia(FILE, [nip96("https://a.example"), nip96("https://b.example")], signer);
    await vi.advanceTimersByTimeAsync(14_000);
    expect(touchedB()).toBe(false);
    await vi.advanceTimersByTimeAsync(2_000);
    expect((await promise)?.url).toBe("https://media.example/abc.webp");
    expect(pollCalls("https://a.example/upload/1").length).toBeGreaterThanOrEqual(15);
  });

  it("中止されたらポーリングを止めて AbortError（次のサーバーへ進まない）", async () => {
    vi.useFakeTimers();
    const { signer } = createTestSigner();
    serve(() => json({ processing_url: PROCESSING }, 202), [() => json({ status: "processing" })]);
    const ac = new AbortController();
    const settled = uploadMedia(
      FILE,
      [nip96("https://s.example"), nip96("https://b.example")],
      signer,
      ac.signal,
    ).then(
      () => "resolved",
      (e: unknown) => (e instanceof Error ? e.name : String(e)),
    );
    await vi.advanceTimersByTimeAsync(2_500);
    const polled = pollCalls().length;
    expect(polled).toBeGreaterThan(0);
    ac.abort();
    expect(await settled).toBe("AbortError");
    await vi.advanceTimersByTimeAsync(5_000);
    expect(pollCalls()).toHaveLength(polled);
    expect(touchedB()).toBe(false);
  });
});

describe("[#895] waitUntilAvailable", () => {
  const FINAL = "https://cdn.example/a.webp";
  const heads = () => fetchMock.mock.calls.filter(([, init]) => init?.method === "HEAD").length;

  it("X-Reason が File not found の間は待ち、配られたら返す", async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(head("image/webp", { reason: "File not found" }))
      .mockResolvedValueOnce(head("image/webp", { reason: "FILE NOT FOUND" }))
      .mockResolvedValueOnce(head("image/webp"));
    const promise = waitUntilAvailable(FINAL, "image/png");
    await vi.advanceTimersByTimeAsync(5_000);
    expect(await promise).toBe(FINAL);
    expect(heads()).toBe(3);
    expect(requestUrl(fetchMock.mock.calls[0][0])).toBe(FINAL);
  });

  it("200 でも Content-Type が text/html の間は待つ", async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(head("text/html; charset=utf-8"))
      .mockResolvedValueOnce(head("text/html"))
      .mockResolvedValueOnce(head("image/webp"));
    const promise = waitUntilAvailable(FINAL, "image/png");
    await vi.advanceTimersByTimeAsync(5_000);
    expect(await promise).toBe(FINAL);
    expect(heads()).toBe(3);
  });

  it("15 秒たっても配られなければ、その URL をそのまま返す", async () => {
    vi.useFakeTimers();
    fetchMock.mockImplementation(async () => head("image/webp", { reason: "processing" }));
    let done = false;
    const promise = waitUntilAvailable(FINAL, "image/png").then((url) => {
      done = true;
      return url;
    });
    await vi.advanceTimersByTimeAsync(14_000);
    expect(done).toBe(false);
    await vi.advanceTimersByTimeAsync(2_000);
    expect(await promise).toBe(FINAL);
    expect(heads()).toBeGreaterThanOrEqual(15);
  });

  it("HEAD が TypeError（通信 / CORS）なら待たずに返す。Content-Type が読めないときは成立とみなす", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    expect(await waitUntilAvailable(FINAL, "image/png")).toBe(FINAL);
    expect(heads()).toBe(1);

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 200 }));
    expect(await waitUntilAvailable(FINAL, "video/mp4")).toBe(FINAL);
    expect(heads()).toBe(2);
  });
});
