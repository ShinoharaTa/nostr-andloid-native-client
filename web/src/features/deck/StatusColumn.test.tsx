import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { VirtuosoMockContext } from "react-virtuoso";
import { afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest";
import { buildColumn, type ColumnSpec } from "../../lib/columns";
import { unixNow } from "../../lib/time";
import { subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { renderWithRouter } from "../../test/renderWithRouter";
import { EMPTY_MUTE_LIST, setMuteList } from "../mute/muteList";
import { DeckColumn } from "./DeckColumn";

// リレーには繋がず、REQ ごとに Subject を返す（張った回数だけを見る）
vi.mock("../../nostr/pool", async (importOriginal) => {
  const { Subject } = await import("rxjs");
  const relays = ["wss://relay.example"];
  return {
    ...(await importOriginal<typeof import("../../nostr/pool")>()),
    useReadRelays: () => relays,
    subscribe: vi.fn(() => new Subject<"EOSE">()),
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
    requestOnce: vi.fn(() => new Subject<NostrEvent>()),
  };
});

// jsdom に ResizeObserver が無い（Virtuoso が使う。寸法は VirtuosoMockContext が与える）
beforeAll(() => {
  if (typeof globalThis.ResizeObserver !== "function") {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
});

let meKey: Uint8Array;
let spec: ColumnSpec;

beforeEach(() => {
  meKey = generateSecretKey();
  useSession.setState({ status: "in", method: "nip07", pubkey: getPublicKey(meKey) });
  const built = buildColumn("STATUS", {}, new Set(), unixNow());
  if (!built) throw new Error("buildColumn returned null");
  spec = built;
  useDeck.setState({ columns: [spec], widths: {}, revealMuted: [], statusType: {} });
  vi.mocked(subscribeTo).mockClear();
});

afterEach(() => {
  vi.useRealTimers();
  setMuteList(null);
  localStorage.clear();
  useSession.setState({ status: "loading", method: null, pubkey: null });
});

/** 自分のフォロー（kind:3）を入れ、フォロー先の鍵を返す */
function follow(count: number): Uint8Array[] {
  const keys = Array.from({ length: count }, () => generateSecretKey());
  eventStore.add(
    finalizeEvent(
      { kind: 3, created_at: unixNow(), tags: keys.map((k) => ["p", getPublicKey(k)]), content: "" },
      meKey,
    ),
  );
  return keys;
}

function status(key: Uint8Array, d: string, content: string, tags: string[][] = [], ago = 60): NostrEvent {
  return finalizeEvent({ kind: 30315, created_at: unixNow() - ago, tags: [["d", d], ...tags], content }, key);
}

function renderColumn() {
  return renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 2000, itemHeight: 100 }}>
      <DeckColumn spec={spec} showHeader />
    </VirtuosoMockContext.Provider>,
  );
}

it("⋯ の「表示」を Now Playing にすると general のカードが消え、REQ は張り直さない。サブタイトルも替わる", async () => {
  const user = userEvent.setup();
  const [alice] = follow(1);
  act(() => {
    eventStore.add(status(alice, "music", "DEEP BREATH - ROLLY"));
    eventStore.add(status(alice, "general", "作業中"));
  });
  renderColumn();

  expect(await screen.findByText("DEEP BREATH - ROLLY")).toBeInTheDocument();
  expect(screen.getByText("作業中")).toBeInTheDocument();
  expect(screen.getByText("NIP-38")).toBeInTheDocument();
  const requests = vi.mocked(subscribeTo).mock.calls.length;
  expect(requests).toBeGreaterThan(0);

  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
  expect(screen.getByRole("menuitemradio", { name: "すべて" })).toHaveAttribute("aria-checked", "true");
  await user.click(screen.getByRole("menuitemradio", { name: "Now Playing" }));

  // メニューは閉じない（幅と同じ）
  expect(screen.getByRole("menuitemradio", { name: "Now Playing" })).toHaveAttribute("aria-checked", "true");
  expect(screen.queryByText("作業中")).not.toBeInTheDocument();
  expect(screen.getByText("DEEP BREATH - ROLLY")).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "ステータス" }).nextElementSibling).toHaveTextContent(
    "Now Playing",
  );
  expect(vi.mocked(subscribeTo).mock.calls.length).toBe(requests);

  await user.click(screen.getByRole("menuitemradio", { name: "ステータス" }));
  expect(screen.getByText("作業中")).toBeInTheDocument();
  expect(screen.queryByText("DEEP BREATH - ROLLY")).not.toBeInTheDocument();
  expect(vi.mocked(subscribeTo).mock.calls.length).toBe(requests);
});

it("期限 5 秒後のステータスは、時計を 15 秒進めると一覧から消える", () => {
  vi.useFakeTimers({ now: Date.now() });
  const [alice] = follow(1);
  act(() => {
    eventStore.add(status(alice, "music", "まもなく終わる曲", [["expiration", String(unixNow() + 5)]]));
    eventStore.add(status(alice, "general", "残るひとこと"));
  });
  renderColumn();
  expect(screen.getByText("まもなく終わる曲")).toBeInTheDocument();

  act(() => {
    vi.advanceTimersByTime(15_000);
  });
  expect(screen.queryByText("まもなく終わる曲")).not.toBeInTheDocument();
  expect(screen.getByText("残るひとこと")).toBeInTheDocument();
});

it("空表示: 未ログインはログインを促し、REQ を張らない", () => {
  useSession.setState({ status: "out", method: null, pubkey: null });
  renderColumn();
  expect(screen.getByText("ログインすると、フォロー中の人のステータスが出ます")).toBeInTheDocument();
  expect(vi.mocked(subscribeTo)).not.toHaveBeenCalled();
});

it("空表示: 該当なし（すべて / ステータス）と、Now Playing に絞り中の該当なし", () => {
  follow(1);
  const timer = vi.useFakeTimers({ now: Date.now() });
  renderColumn();
  // 最初の EOSE が来ない間は「読み込み中…」、8 秒で該当なしの文言に替わる
  expect(screen.getByText("読み込み中…")).toBeInTheDocument();
  act(() => {
    timer.advanceTimersByTime(8_000);
  });
  expect(screen.getByText("いまステータスを出している人はいません")).toBeInTheDocument();

  act(() => useDeck.getState().setStatusType(spec.id, "music"));
  expect(screen.getByText("いま Now Playing を出している人はいません")).toBeInTheDocument();

  act(() => useDeck.getState().setStatusType(spec.id, "general"));
  expect(screen.getByText("いまステータスを出している人はいません")).toBeInTheDocument();
});

it("ミュートした著者のステータスは出ず、「ミュートを表示」で出る", async () => {
  const [alice, muted] = follow(2);
  act(() => {
    eventStore.add(status(alice, "general", "見えるひとこと"));
    eventStore.add(status(muted, "general", "ミュートした人のひとこと"));
    setMuteList({
      ...EMPTY_MUTE_LIST,
      entries: [{ category: "p", value: getPublicKey(muted), isPublic: true, isPrivate: false }],
    });
  });
  renderColumn();

  expect(await screen.findByText("見えるひとこと")).toBeInTheDocument();
  expect(screen.queryByText("ミュートした人のひとこと")).not.toBeInTheDocument();

  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "カラムメニュー" }));
  await user.click(screen.getByRole("menuitem", { name: /ミュートを表示/ }));
  expect(screen.getByText("ミュートした人のひとこと")).toBeInTheDocument();
});
