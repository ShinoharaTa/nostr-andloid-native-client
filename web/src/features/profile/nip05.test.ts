import { describe, expect, it, vi } from "vitest";
import { nip05Label, parseNip05, verifyNip05 } from "./nip05";

/** テストごとに別の pubkey（同じ pubkey + nip05 は結果が使い回されるため） */
function pubkey(n: number): string {
  return n.toString(16).padStart(64, "0");
}

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

function fetchReturning(response: Response | (() => Promise<Response>)) {
  return vi.fn<typeof fetch>(async () => (typeof response === "function" ? response() : response));
}

describe("parseNip05", () => {
  it("name@domain を分けて domain を小文字に、@ 無しは _@domain", () => {
    expect(parseNip05("bob@Example.COM")).toEqual({ local: "bob", domain: "example.com" });
    expect(parseNip05("  example.com ")).toEqual({ local: "_", domain: "example.com" });
  });

  it("ドット無し・パス・ポート・local 空・空文字は不正", () => {
    for (const value of ["bob@localhost", "a@b.com/x", "a@b.com:8443", "@b.com", "", "   ", "a@user@b.com"]) {
      expect(parseNip05(value)).toBeNull();
    }
  });
});

describe("verifyNip05", () => {
  it("names[local] が pubkey と一致（大文字小文字は無視）すれば verified。URL とオプションを確かめる", async () => {
    const pk = pubkey(1);
    const fetchImpl = fetchReturning(jsonResponse({ names: { bob: pk.toUpperCase() } }));

    await expect(verifyNip05(pk, "bob@Example.com", fetchImpl)).resolves.toBe("verified");

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe("https://example.com/.well-known/nostr.json?name=bob");
    expect(init).toMatchObject({ redirect: "error", credentials: "omit" });
  });

  it("別の pubkey・names に無い・names が無いは invalid", async () => {
    await expect(
      verifyNip05(pubkey(2), "bob@example.com", fetchReturning(jsonResponse({ names: { bob: pubkey(99) } }))),
    ).resolves.toBe("invalid");
    await expect(
      verifyNip05(
        pubkey(3),
        "bob@example.com",
        fetchReturning(jsonResponse({ names: { alice: pubkey(3) } })),
      ),
    ).resolves.toBe("invalid");
    await expect(verifyNip05(pubkey(4), "bob@example.com", fetchReturning(jsonResponse({})))).resolves.toBe(
      "invalid",
    );
  });

  it("取得できなければ（CORS 等の例外・404・JSON 不正）unverified", async () => {
    const cors = vi.fn<typeof fetch>(async () => {
      throw new TypeError("Failed to fetch");
    });
    await expect(verifyNip05(pubkey(5), "bob@example.com", cors)).resolves.toBe("unverified");
    await expect(
      verifyNip05(pubkey(6), "bob@example.com", fetchReturning(jsonResponse({ names: {} }, 404))),
    ).resolves.toBe("unverified");
    const broken = {
      ok: true,
      status: 200,
      json: async () => {
        throw new SyntaxError("Unexpected token");
      },
    } as unknown as Response;
    await expect(verifyNip05(pubkey(7), "bob@example.com", fetchReturning(broken))).resolves.toBe(
      "unverified",
    );
  });

  it("書式が不正なら取りに行かずに invalid", async () => {
    const fetchImpl = fetchReturning(jsonResponse({ names: {} }));
    await expect(verifyNip05(pubkey(8), "bob@localhost", fetchImpl)).resolves.toBe("invalid");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("同じ pubkey + nip05 の 2 回目は取りに行かない", async () => {
    const pk = pubkey(9);
    const fetchImpl = fetchReturning(jsonResponse({ names: { _: pk } }));
    await expect(verifyNip05(pk, "example.org", fetchImpl)).resolves.toBe("verified");
    await expect(verifyNip05(pk, "example.org", fetchImpl)).resolves.toBe("verified");
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0][0]).toBe("https://example.org/.well-known/nostr.json?name=_");
  });
});

/** [#939] `_@domain` はドメインだけを出し、それ以外はそのまま出す（ネイティブ Nip05DisplayTest） */
describe("nip05Label", () => {
  it("root_identifier_shows_domain_only", () => {
    expect(nip05Label("_@example.com")).toBe("example.com");
    expect(nip05Label("  _@Example.COM ")).toBe("example.com");
  });

  it("other_identifiers_stay_as_is", () => {
    expect(nip05Label("alice@example.com")).toBe("alice@example.com");
    expect(nip05Label("Alice@Example.COM")).toBe("Alice@Example.COM"); // 小文字にするのはルートだけ
    expect(nip05Label(" alice@example.com ")).toBe("alice@example.com");
    expect(nip05Label("@example.com")).toBe("@example.com");
    expect(nip05Label("_")).toBe("_");
    expect(nip05Label("_alice@example.com")).toBe("_alice@example.com");
    expect(nip05Label("example.com")).toBe("example.com");
    expect(nip05Label("")).toBe("");
  });

  it("malformed_values_stay_as_is", () => {
    // 照会できない形は畳まない（verifyNip05 はドメインに `.` が無い値を照会しない）
    expect(nip05Label("_@")).toBe("_@");
    expect(nip05Label("_@a@b.com")).toBe("_@a@b.com");
    expect(nip05Label("_@ example.com")).toBe("_@ example.com");
    expect(nip05Label("_@localhost")).toBe("_@localhost");
  });

  it("never_blank_for_non_blank_input", () => {
    // 表示箇所は生の値が空でないかで出し分けるので、空を返すと空行だけが残る
    for (const value of ["_@", "_", "@", "_@example.com", "a@b"]) {
      expect(nip05Label(value).trim()).not.toBe("");
    }
  });
});
