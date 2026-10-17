import { type NostrEvent, verifyEvent } from "nostr-tools/pure";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createTestSigner } from "../../test/fakeSigner";
import { blossomAuthHeader, blossomUploadUrl, parseDescriptor, sha256Hex, uploadToBlossom } from "./blossom";

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function requestUrl(input: Parameters<typeof fetch>[0]): string {
  return typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
}

/** "Nostr <base64url>" の中身のイベント（パディングなし・URL 安全な文字だけであること） */
function decodeAuth(header: string): NostrEvent {
  expect(header.startsWith("Nostr ")).toBe(true);
  const encoded = header.slice("Nostr ".length);
  expect(encoded).toMatch(/^[A-Za-z0-9_-]+$/);
  const base64 = encoded.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(base64 + "=".repeat((4 - (base64.length % 4)) % 4));
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes)) as NostrEvent;
}

/** sha256("abc") */
const ABC_SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
const FILE = { blob: new Blob(["abc"], { type: "image/png" }), mime: "image/png", name: "a.png" };

describe("部品", () => {
  it("upload の URL と sha256", async () => {
    expect(blossomUploadUrl(" https://blossom.band/ ")).toBe("https://blossom.band/upload");
    expect(await sha256Hex(new Blob(["abc"]))).toBe(ABC_SHA);
  });

  it("認証は kind:24242（content は Upload <名前>、t=upload・x・expiration=now+300）", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(1_000_000);
    const { signer, pubkey } = createTestSigner();
    const event = decodeAuth(await blossomAuthHeader(signer, ABC_SHA, "photo.webp"));
    expect(verifyEvent(event)).toBe(true);
    expect(event.pubkey).toBe(pubkey);
    expect(event.kind).toBe(24242);
    expect(event.created_at).toBe(1_000);
    expect(event.content).toBe("Upload photo.webp");
    expect(event.tags).toEqual([
      ["t", "upload"],
      ["x", ABC_SHA],
      ["expiration", "1300"],
    ]);
  });

  it("記述子は http(s) の url があるときだけ。sha256 は小文字にそろえる", () => {
    expect(
      parseDescriptor(`{"url":"https://cdn.example/a.png","sha256":"${ABC_SHA.toUpperCase()}","size":3}`),
    ).toEqual({ url: "https://cdn.example/a.png", sha256: ABC_SHA, size: 3 });
    expect(parseDescriptor(`{"url":"https://cdn.example/a.png","sha256":"${ABC_SHA}"}`)?.size).toBeNull();
    expect(parseDescriptor(`{"sha256":"${ABC_SHA}"}`)).toBeNull();
    expect(parseDescriptor(`{"url":"ftp://x","sha256":"${ABC_SHA}"}`)).toBeNull();
    expect(parseDescriptor("not json")).toBeNull();
  });
});

describe("uploadToBlossom", () => {
  it("本文をそのまま <server>/upload へ PUT し、sha256（大小無視）と size が合えば url を使う", async () => {
    const { signer } = createTestSigner();
    fetchMock.mockResolvedValueOnce(
      json({ url: "https://cdn.example/abc.png", sha256: ABC_SHA.toUpperCase(), size: 3, type: "image/png" }),
    );

    const result = await uploadToBlossom("https://blossom.example/", FILE, signer);

    expect(result).toEqual({ url: "https://cdn.example/abc.png", tags: [] });
    const [input, init] = fetchMock.mock.calls[0];
    expect(requestUrl(input)).toBe("https://blossom.example/upload");
    expect(init?.method).toBe("PUT");
    const headers = new Headers(init?.headers);
    expect(headers.get("Content-Type")).toBe("image/png");
    expect(init?.body).toBe(FILE.blob);
    const auth = decodeAuth(headers.get("Authorization") ?? "");
    expect(auth.kind).toBe(24242);
    expect(auth.tags).toContainEqual(["x", ABC_SHA]);
  });

  it("sha256 が違えばエラー（url を使わない）", async () => {
    const { signer } = createTestSigner();
    fetchMock.mockResolvedValueOnce(
      json({ url: "https://cdn.example/x.png", sha256: "0".repeat(64), size: 3 }),
    );
    await expect(uploadToBlossom("https://blossom.example", FILE, signer)).rejects.toThrow(/sha256/);
  });

  it("size が違えばエラー（url を使わない）", async () => {
    const { signer } = createTestSigner();
    fetchMock.mockResolvedValueOnce(json({ url: "https://cdn.example/abc.png", sha256: ABC_SHA, size: 4 }));
    await expect(uploadToBlossom("https://blossom.example", FILE, signer)).rejects.toThrow(/size/);
  });

  it("失敗したら X-Reason をエラーに入れる", async () => {
    const { signer } = createTestSigner();
    fetchMock.mockResolvedValueOnce(
      new Response("", { status: 413, headers: { "X-Reason": "File too large. Max allowed size is 100MB" } }),
    );
    await expect(uploadToBlossom("https://blossom.example", FILE, signer)).rejects.toThrow(
      "Blossom upload rejected: 413 File too large. Max allowed size is 100MB",
    );
  });
});
