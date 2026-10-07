import { act, fireEvent, screen } from "@testing-library/react";
import { finalizeEvent, generateSecretKey } from "nostr-tools/pure";
import { forwardRef, type Ref } from "react";
import { type VirtuosoHandle, VirtuosoMockContext } from "react-virtuoso";
import { beforeAll, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { renderWithRouter } from "../../test/renderWithRouter";
import { KbColumn } from "../keyboard/KbList";
import { Timeline } from "./Timeline";

// Timeline が内部の ref に持つ VirtuosoHandle を拾う（jsdom では実スクロールしないので、呼び出しを spy で確かめる）
const captured = vi.hoisted(() => ({ handle: null as VirtuosoHandle | null }));
vi.mock("react-virtuoso", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-virtuoso")>();
  const Virtuoso = forwardRef(function CapturingVirtuoso(
    props: Parameters<typeof actual.Virtuoso>[0],
    ref: Ref<VirtuosoHandle>,
  ) {
    return (
      <actual.Virtuoso
        {...props}
        ref={(handle: VirtuosoHandle | null) => {
          captured.handle = handle;
          if (typeof ref === "function") ref(handle);
          else if (ref) ref.current = handle;
        }}
      />
    );
  });
  return { ...actual, Virtuoso };
});

const KEY = generateSecretKey();
const NOW = unixNow();
// 新しい順に 20 件（描画のたびに同じ配列を渡す）
const EVENTS = Array.from({ length: 20 }, (_, i) =>
  finalizeEvent({ kind: 1, created_at: NOW - i * 60, tags: [], content: `投稿 ${i}` }, KEY),
);

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

function renderTimeline() {
  const view = renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 300, itemHeight: 100 }}>
      <Timeline events={EVENTS} loading={false} />
    </VirtuosoMockContext.Provider>,
  );
  const found = view.container.querySelector<HTMLElement>('[data-testid="virtuoso-scroller"]');
  if (!found) throw new Error("scroller が無い");
  const scroller: HTMLElement = found;
  function scrollTo(top: number) {
    act(() => {
      scroller.scrollTop = top;
      fireEvent.scroll(scroller);
    });
  }
  return { scrollTo };
}

it("先頭では何も出さず、3 件目以降まで下りると「最新へ戻る」を出す（ネイティブの FeedTopPill）", () => {
  const { scrollTo } = renderTimeline();
  expect(screen.queryByRole("button", { name: /最新へ戻る/ })).toBeNull();

  // 2 件目（位置 1）ではまだ出さない
  scrollTo(150);
  expect(screen.queryByRole("button", { name: /最新へ戻る/ })).toBeNull();

  scrollTo(500);
  expect(screen.getByRole("button", { name: "↑ 最新へ戻る" })).toBeInTheDocument();

  scrollTo(0);
  expect(screen.queryByRole("button", { name: /最新へ戻る/ })).toBeNull();
});

it("ピルを押すと行の番号で先頭へ戻る（scrollTo({ top: 0 }) では新着の差し込み分だけ手前で止まる。#782）", () => {
  const { scrollTo } = renderTimeline();
  scrollTo(500);
  const handle = captured.handle;
  if (!handle) throw new Error("VirtuosoHandle が無い");
  const scrollToIndex = vi.spyOn(handle, "scrollToIndex").mockImplementation(() => {});
  const scrollToTop = vi.spyOn(handle, "scrollTo").mockImplementation(() => {});

  fireEvent.click(screen.getByRole("button", { name: "↑ 最新へ戻る" }));

  expect(scrollToIndex).toHaveBeenCalledWith({ index: 0, align: "start", behavior: "smooth" });
  expect(scrollToTop).not.toHaveBeenCalled();
});

it("header は一覧の先頭（スクロール領域の中）に出るが、item の index には含まれない（PROFILE カラムの上部カード用）", () => {
  const view = renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 300, itemHeight: 100 }}>
      <KbColumn id="col1">
        <Timeline events={EVENTS} loading={false} header={<p>プロフィールカード</p>} />
      </KbColumn>
    </VirtuosoMockContext.Provider>,
  );
  expect(screen.getByText("プロフィールカード")).toBeInTheDocument();
  // header は data の要素ではないので data-kb-index を持たない。先頭の投稿が index 0 のまま
  const first = view.container.querySelector('[data-kb-index="0"]');
  expect(first).toHaveTextContent("投稿 0");
  expect(first).not.toHaveTextContent("プロフィールカード");
});

it("投稿が 0 件でも header は出す（ネイティブの LazyColumn と同じ）", () => {
  renderWithRouter(
    <VirtuosoMockContext.Provider value={{ viewportHeight: 300, itemHeight: 100 }}>
      <Timeline events={[]} loading={false} header={<p>プロフィールカード</p>} />
    </VirtuosoMockContext.Provider>,
  );
  expect(screen.getByText("プロフィールカード")).toBeInTheDocument();
  expect(screen.getByText("投稿がありません")).toBeInTheDocument();
});
