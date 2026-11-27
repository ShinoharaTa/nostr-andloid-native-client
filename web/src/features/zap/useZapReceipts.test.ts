import { act, renderHook } from "@testing-library/react";
import { finalizeEvent, generateSecretKey, type NostrEvent } from "nostr-tools/pure";
import type { Subject } from "rxjs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import {
  useInvoicePaid,
  useNoteZaps,
  useZapReceipts,
  useZapSats,
  ZAP_NOTE_LIMIT,
  zapTargetIds,
} from "./useZapReceipts";

// リレーには繋がず、REQ ごとに Subject を返す
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  const { Subject } = await import("rxjs");
  const relays = ["wss://relay.example"];
  return {
    ...actual,
    useReadRelays: () => relays,
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
  };
});

beforeEach(() => {
  vi.mocked(subscribeTo).mockClear();
});

function signed(kind: number, tags: string[][] = [], content = ""): NostrEvent {
  return finalizeEvent({ kind, created_at: 1_000, tags, content }, generateSecretKey());
}

const id = (n: number) => n.toString(16).padStart(64, "0");

describe("zapTargetIds", () => {
  it("投稿は自分の id、リポストは元投稿、リアクション・Zap 受領は除く（重複なし・並びのまま）", () => {
    const note = signed(1);
    const other = signed(1);
    const repost = signed(6, [["e", other.id]]);
    const generic = signed(16, [["e", note.id]]);
    const reaction = signed(7, [["e", note.id]]);
    const zap = signed(9735, [["e", note.id]]);
    expect(zapTargetIds([note, repost, generic, reaction, zap])).toEqual([note.id, other.id]);
  });
});

describe("useZapReceipts", () => {
  it("先頭 300 件の id で kinds:[9735] #e limit:500 を read リレーへ張り、アンマウントで CLOSE", () => {
    const ids = Array.from({ length: ZAP_NOTE_LIMIT + 5 }, (_, i) => id(i));
    const { unmount } = renderHook(() => useZapReceipts(ids));

    expect(subscribeTo).toHaveBeenCalledTimes(1);
    const [relays, filters] = vi.mocked(subscribeTo).mock.calls[0];
    expect(relays).toEqual(["wss://relay.example"]);
    expect(filters).toHaveLength(1);
    expect(filters[0]).toMatchObject({ kinds: [9735], limit: 500 });
    expect([...(filters[0]["#e"] ?? [])].sort()).toEqual(ids.slice(0, ZAP_NOTE_LIMIT).sort());

    const subject = vi.mocked(subscribeTo).mock.results[0].value as Subject<"EOSE">;
    expect(subject.observed).toBe(true);
    unmount();
    expect(subject.observed).toBe(false);
  });

  it("id の集合が変わったら張り直し、並びが変わっただけなら張り直さない。空なら張らない", () => {
    const { rerender } = renderHook(({ ids }) => useZapReceipts(ids), {
      initialProps: { ids: [] as string[] },
    });
    expect(subscribeTo).not.toHaveBeenCalled();

    rerender({ ids: [id(1), id(2)] });
    expect(subscribeTo).toHaveBeenCalledTimes(1);
    const first = vi.mocked(subscribeTo).mock.results[0].value as Subject<"EOSE">;

    rerender({ ids: [id(2), id(1)] });
    expect(subscribeTo).toHaveBeenCalledTimes(1);

    rerender({ ids: [id(3), id(2), id(1)] });
    expect(subscribeTo).toHaveBeenCalledTimes(2);
    expect(first.observed).toBe(false);
    expect(vi.mocked(subscribeTo).mock.calls[1][1][0]["#e"]).toEqual([id(3), id(2), id(1)].sort());
  });
});

describe("useNoteZaps / useZapSats", () => {
  it("ストアに届いた receipt をその投稿の分だけ集計する", () => {
    const note = signed(1);
    const { result: sats } = renderHook(() => useZapSats(note.id));
    const { result: zaps } = renderHook(() => useNoteZaps(note.id));
    expect(sats.current).toBe(0);
    expect(zaps.current.zaps).toEqual([]);

    act(() => {
      eventStore.add(
        signed(9735, [
          ["e", note.id],
          ["bolt11", "lnbc12340n1pxxxxxx"],
        ]),
      );
      eventStore.add(
        signed(9735, [
          ["e", signed(1).id],
          ["bolt11", "lnbc10u1pxxxxxx"],
        ]),
      );
    });
    expect(sats.current).toBe(1_234);
    expect(zaps.current.totalSats).toBe(1_234);
    expect(zaps.current.zaps).toHaveLength(1);
  });
});

describe("[#1012] useInvoicePaid", () => {
  it("この投稿への receipt で bolt11 が一致したら true（ほかの請求書・投稿では false）", () => {
    const note = signed(1);
    const pr = "lnbc210n1pinvoicepaid";
    const { result } = renderHook(() => useInvoicePaid(note.id, pr));
    expect(result.current).toBe(false);

    act(() => {
      eventStore.add(
        signed(9735, [
          ["e", note.id],
          ["bolt11", "lnbc210n1pother"],
        ]),
      );
      eventStore.add(
        signed(9735, [
          ["e", signed(1).id],
          ["bolt11", pr],
        ]),
      );
    });
    expect(result.current).toBe(false);

    act(() => {
      eventStore.add(
        signed(9735, [
          ["e", note.id],
          ["bolt11", pr.toUpperCase()],
        ]),
      );
    });
    expect(result.current).toBe(true);
  });

  it("投稿 id か請求書が無ければ false", () => {
    const { result } = renderHook(() => useInvoicePaid(undefined, "lnbc1"));
    expect(result.current).toBe(false);
    const { result: noInvoice } = renderHook(() => useInvoicePaid(signed(1).id, null));
    expect(noInvoice.current).toBe(false);
  });
});
