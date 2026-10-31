import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { npubEncode } from "nostr-tools/nip19";
import { finalizeEvent, generateSecretKey, type NostrEvent } from "nostr-tools/pure";
import { VirtuosoMockContext } from "react-virtuoso";
import type { Subject } from "rxjs";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { SEARCH_RELAYS } from "../../lib/columnRequest";
import { DEFAULT_COLUMNS } from "../../lib/columns";
import { unixNow } from "../../lib/time";
import { type RelayProgress, subscribeProgress, subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { COLUMNS_KEY, useDeck } from "../../store/deck";
import { renderWithRouter } from "../../test/renderWithRouter";
import { clearViewport, mockViewport } from "../../test/viewport";
import { SearchScreen } from "./SearchScreen";
import { useSearchHistory } from "./searchHistory";

// リレーには繋がず、REQ ごとに Subject を返す（EOSE はテストから流す）
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  const { Subject } = await import("rxjs");
  const RELAYS = ["wss://relay.example"];
  return {
    ...actual,
    readRelays: () => RELAYS,
    writeRelays: () => RELAYS,
    // 毎回同じ配列を返す（新しい配列だと購読の張り直しが止まらない）
    useReadRelays: () => RELAYS,
    subscribe: vi.fn(() => new Subject<"EOSE">()),
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
    subscribeProgress: vi.fn(() => new Subject<RelayProgress>()),
    requestOnce: vi.fn(() => new Subject<NostrEvent>()),
  };
});

beforeAll(() => {
  // jsdom に ResizeObserver が無い（Virtuoso が使う。寸法は VirtuosoMockContext が与える）
  if (typeof globalThis.ResizeObserver !== "function") {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

beforeEach(() => {
  localStorage.clear();
  useSearchHistory.setState({ history: [] });
  useDeck.setState({ columns: structuredClone([...DEFAULT_COLUMNS]), widths: {}, jumpTarget: null });
  vi.mocked(subscribeTo).mockClear();
  vi.mocked(subscribeProgress).mockClear();
});

afterEach(() => {
  clearViewport();
});

function renderScreen(width: number) {
  mockViewport(width);
  return renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      <SearchScreen />
    </VirtuosoMockContext.Provider>,
  );
}

function input() {
  return screen.getByRole("searchbox", { name: "検索語" });
}

/** kinds[0] が kind の最後の REQ の Subject */
function lastReqOf(kind: number): Subject<"EOSE"> {
  const { calls, results } = vi.mocked(subscribeTo).mock;
  const index = calls.findLastIndex(([, filters]) => filters[0]?.kinds?.[0] === kind);
  return results[index]?.value as Subject<"EOSE">;
}

function note(content: string, tags: string[][] = []): NostrEvent {
  const event = finalizeEvent({ kind: 1, created_at: unixNow(), tags, content }, generateSecretKey());
  eventStore.add(event);
  return event;
}

describe("compact", () => {
  it("積んで検索 → 投稿・ユーザー → 履歴へ戻って履歴から再検索・削除・クリア", async () => {
    const user = userEvent.setup();
    renderScreen(400);
    expect(screen.getByText("検索履歴")).toBeInTheDocument();
    expect(screen.getByText("検索履歴はありません")).toBeInTheDocument();

    await user.type(input(), "rally #wrc");
    await user.click(screen.getByRole("button", { name: "＋追加" }));
    expect(screen.getAllByRole("button", { name: "条件を削除" })).toHaveLength(2);
    expect(input()).toHaveValue("");
    expect(vi.mocked(subscribeTo)).not.toHaveBeenCalled();

    await user.type(input(), "{Enter}");
    expect(screen.getByText("検索: rally #wrc")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "← 履歴" })).toBeInTheDocument();
    expect(useSearchHistory.getState().history).toEqual(["rally #wrc"]);
    expect(vi.mocked(subscribeTo)).toHaveBeenCalledWith(SEARCH_RELAYS, [
      { kinds: [1], search: "rally", limit: 300 },
      { kinds: [1], "#t": ["wrc"], limit: 300 },
    ]);

    act(() => {
      note("WRC Rally Japan");
      note("タグだけ", [["t", "wrc"]]);
      note("関係ない投稿");
    });
    expect(await screen.findByRole("tab", { name: "投稿 2" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByRole("article")).toHaveLength(2);

    const fanKey = generateSecretKey();
    const fan = finalizeEvent(
      { kind: 0, created_at: unixNow(), tags: [], content: JSON.stringify({ name: "rally fan" }) },
      fanKey,
    );
    act(() => {
      eventStore.add(fan);
    });
    await user.click(await screen.findByRole("tab", { name: /^ユーザー/ }));
    expect(screen.getByRole("tab", { name: /^ユーザー/ })).toHaveAttribute("aria-selected", "true");
    const link = await screen.findByRole("link", { name: /rally fan/ });
    expect(link).toHaveAttribute("href", `/p/${npubEncode(fan.pubkey)}`);
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(vi.mocked(subscribeTo)).toHaveBeenCalledWith(SEARCH_RELAYS, [
      { kinds: [0], search: "rally", limit: 100 },
    ]);

    await user.click(screen.getByRole("button", { name: "← 履歴" }));
    const history = screen.getByRole("region", { name: "検索履歴" });
    await user.click(within(history).getByRole("button", { name: "rally #wrc" }));
    expect(screen.getByText("検索: rally #wrc")).toBeInTheDocument();
    expect(useSearchHistory.getState().history).toEqual(["rally #wrc"]);
    expect(screen.getByRole("tab", { name: /^投稿/ })).toHaveAttribute("aria-selected", "true");

    await user.click(screen.getByRole("button", { name: "← 履歴" }));
    await user.click(screen.getByRole("button", { name: "削除" }));
    expect(useSearchHistory.getState().history).toEqual([]);
    expect(screen.getByText("検索履歴はありません")).toBeInTheDocument();

    act(() => {
      useSearchHistory.getState().add("a");
      useSearchHistory.getState().add("b");
    });
    await user.click(screen.getByRole("button", { name: "クリア" }));
    expect(useSearchHistory.getState().history).toEqual([]);
    expect(screen.getByText("検索履歴はありません")).toBeInTheDocument();
  });

  it("チップを全部消すと履歴に戻る。タグだけなら自分のリレーへ #t、ユーザーは探さない", async () => {
    const user = userEvent.setup();
    renderScreen(400);
    await user.type(input(), "#nostr{Enter}");
    // 検索画面の結果は続きを取りに行かないので、リレーごとの取得範囲は数えない（#955）
    expect(vi.mocked(subscribeTo)).toHaveBeenCalledWith(
      ["wss://relay.example"],
      [{ kinds: [1], "#t": ["nostr"], limit: 100 }],
    );
    expect(vi.mocked(subscribeTo)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(subscribeProgress)).not.toHaveBeenCalled();

    await user.click(screen.getByRole("tab", { name: /^ユーザー/ }));
    expect(screen.getByText("条件に合うユーザーがいません。")).toBeInTheDocument();
    expect(screen.queryByRole("status")).toBeNull();

    await user.click(screen.getByRole("button", { name: "条件を削除" }));
    expect(screen.queryByRole("button", { name: "条件を削除" })).toBeNull();
    expect(screen.getByRole("region", { name: "検索履歴" })).toBeInTheDocument();
    expect(screen.queryByRole("tablist")).toBeNull();
  });

  it("結果が 0 件: EOSE までは読み込み中、後は「検索結果がありません」", async () => {
    const user = userEvent.setup();
    renderScreen(400);
    await user.type(input(), "zzqqxxnomatch{Enter}");
    expect(screen.getByRole("status")).toHaveTextContent("読み込み中…");
    act(() => lastReqOf(1).next("EOSE"));
    expect(screen.queryByRole("status")).toBeNull();
    expect(screen.getByText("検索結果がありません")).toBeInTheDocument();
  });

  it("「Deckに追加」で条件のカラムを固定で足し、そこへ jump する", async () => {
    const user = userEvent.setup();
    renderScreen(400);
    await user.type(input(), "rally #wrc{Enter}");
    await user.click(screen.getByRole("button", { name: "Deckに追加" }));

    const { columns, jumpTarget } = useDeck.getState();
    const added = columns.at(-1);
    expect(added?.id).toMatch(/^col_search_\d+$/);
    expect(added).toMatchObject({ title: "rally #wrc", kind: "GLOBAL", pinned: true });
    expect(added?.filter.words).toEqual(["rally"]);
    expect(added?.filter.hashtags).toEqual(["wrc"]);
    expect(jumpTarget).toBe(added?.id);
    const saved: { id: string }[] = JSON.parse(localStorage.getItem(COLUMNS_KEY) ?? "[]");
    expect(saved.at(-1)?.id).toBe(added?.id);
  });

  it("見出し「検索」が 1 つある（読み上げ用）", () => {
    renderScreen(400);
    expect(screen.getAllByRole("heading", { name: "検索" })).toHaveLength(1);
  });
});

describe("expanded", () => {
  it("履歴と案内が並び、実行後は履歴を残したまま右に結果（← 履歴は無い）", async () => {
    const user = userEvent.setup();
    renderScreen(1400);
    expect(screen.getByRole("region", { name: "検索履歴" })).toBeInTheDocument();
    expect(
      screen.getByText("単語・#タグを追加して検索してください（複数は OR で並びます）"),
    ).toBeInTheDocument();

    await user.type(input(), "expandedword{Enter}");
    expect(screen.getByText("検索: expandedword")).toBeInTheDocument();
    const history = screen.getByRole("region", { name: "検索履歴" });
    expect(within(history).getByRole("button", { name: "expandedword" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "← 履歴" })).toBeNull();
    expect(screen.queryByText("単語・#タグを追加して検索してください（複数は OR で並びます）")).toBeNull();
  });
});
