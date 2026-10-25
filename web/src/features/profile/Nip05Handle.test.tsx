import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { Nip05Handle } from "./Nip05Handle";

afterEach(() => {
  vi.unstubAllGlobals();
});

/** テストごとに別の pubkey（検証結果は pubkey + nip05 で使い回されるため） */
function pubkey(n: number): string {
  return (0x4000 + n).toString(16).padStart(64, "0");
}

function stubFetch(impl: () => Promise<unknown>) {
  const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: impl }) as unknown as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

it("検証OK なら緑の ✓（NIP-05 検証OK）", async () => {
  const pk = pubkey(1);
  stubFetch(async () => ({ names: { bob: pk } }));
  render(<Nip05Handle pubkey={pk} nip05="bob@example.com" size="sub" />);

  expect(await screen.findByRole("img", { name: "NIP-05 検証OK" })).toBeInTheDocument();
  expect(screen.getByText("bob@example.com")).toBeInTheDocument();
});

it("不一致なら赤の !（NIP-05 検証エラー）", async () => {
  stubFetch(async () => ({ names: { bob: pubkey(99) } }));
  render(<Nip05Handle pubkey={pubkey(2)} nip05="bob@example.com" size="sub" />);

  expect(await screen.findByRole("img", { name: "NIP-05 検証エラー" })).toBeInTheDocument();
});

it("取得できない（CORS 等）ときは何も付けず、文字だけ", async () => {
  const fetchMock = vi.fn(async () => {
    throw new TypeError("Failed to fetch");
  });
  vi.stubGlobal("fetch", fetchMock);
  render(<Nip05Handle pubkey={pubkey(3)} nip05="bob@example.com" size="caption" />);

  await waitFor(() => expect(fetchMock).toHaveBeenCalled());
  await act(async () => {});
  expect(screen.getByText("bob@example.com")).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

it("確認中は何も付けない", () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(() => new Promise<Response>(() => {})),
  );
  render(<Nip05Handle pubkey={pubkey(4)} nip05="bob@example.com" size="sub" />);

  expect(screen.getByText("bob@example.com")).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

it("[#939] `_@domain` はドメインだけを出す", () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(() => new Promise<Response>(() => {})),
  );
  render(<Nip05Handle pubkey={pubkey(5)} nip05="_@Example.com" size="sub" />);

  expect(screen.getByText("example.com")).toBeInTheDocument();
  expect(screen.queryByText("_@Example.com")).not.toBeInTheDocument();
});
