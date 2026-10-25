import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ColumnSpec } from "../../lib/columns";
import { DEFAULT_COLUMNS } from "../../lib/columns";
import { useDeck } from "../../store/deck";
import { clearViewport, mockViewport, setBox, setViewportWidth } from "../../test/viewport";
import { DeckScreen } from "./DeckScreen";
import deckStyles from "./DeckScreen.module.css";

/** `(prefers-reduced-motion: reduce)` だけ差し替える。他のクエリは mockViewport の判定のまま */
function mockReducedMotion(reduce: boolean) {
  const original = window.matchMedia;
  window.matchMedia = ((query: string) =>
    query.includes("prefers-reduced-motion")
      ? ({
          matches: reduce,
          media: query,
          addEventListener() {},
          removeEventListener() {},
        } as unknown as MediaQueryList)
      : original(query)) as typeof window.matchMedia;
}

const { mounts } = vi.hoisted(() => ({ mounts: new Map<string, number>() }));

// カラムの中身（購読・仮想リスト）は描かず、マウント回数だけ数える
vi.mock("../../features/deck/DeckColumn", async () => {
  const { useState } = await import("react");
  return {
    DeckColumn({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) {
      useState(() => mounts.set(spec.id, (mounts.get(spec.id) ?? 0) + 1));
      return <div data-testid={`col-${spec.id}`} data-header={String(showHeader)} />;
    },
    ColumnMenu: () => <button type="button">カラムメニュー</button>,
  };
});

beforeEach(() => {
  vi.useFakeTimers();
  localStorage.clear();
  mounts.clear();
  useDeck.setState({
    columns: structuredClone([...DEFAULT_COLUMNS]),
    widths: {},
    jumpTarget: null,
    visibleColumnId: null,
    editingColumnId: null,
    showAddColumn: false,
  });
});

afterEach(() => {
  vi.useRealTimers();
  clearViewport();
});

/** カラムを並べる横スクロールの要素 */
function strip() {
  const slot = screen.getByRole("region", { name: "フォロー中" });
  if (!slot.parentElement) throw new Error("strip が無い");
  return slot.parentElement;
}

/** scrollLeft を動かして scroll を送り、rAF まで進める */
function scrollTo(el: HTMLElement, left: number) {
  el.scrollLeft = left;
  fireEvent.scroll(el);
  act(() => {
    vi.advanceTimersToNextFrame();
  });
}

/** [#938] マウント時のホームへの寄せが着いたことにする（jsdom はマウント時の幅が 0 なので、着いたと判定できない） */
function arriveAtHome(el: HTMLElement) {
  scrollTo(el, 0);
}

describe("compact", () => {
  beforeEach(() => {
    mockViewport(400, { hover: false });
  });

  it("[#597][#661] 599px まではタブ列に接続表示がある。600px 以降は左レール表示なのでタブ列側は出さない", () => {
    mockViewport(599, { hover: false });
    const first = render(<DeckScreen />);
    expect(screen.getByRole("button", { name: /^リレー接続/ })).toBeInTheDocument();
    first.unmount();

    mockViewport(600, { hover: false });
    render(<DeckScreen />);
    expect(screen.queryByRole("button", { name: /^リレー接続/ })).not.toBeInTheDocument();
  });

  it("[#661] ホバーできる端末は幅を問わず左レール扱い（440 の下限は廃止。タブ列に接続表示は出ない）", () => {
    mockViewport(300, { hover: true });
    render(<DeckScreen />);
    expect(screen.queryByRole("button", { name: /^リレー接続/ })).not.toBeInTheDocument();
  });

  it("カラムヘッダ無しで 3 カラム、タブ列と選択カラムの ⋯、カラム追加はタブ列の ＋ だけ", () => {
    render(<DeckScreen />);
    for (const id of ["c_following", "c_hashtag", "c_notif"]) {
      expect(screen.getByTestId(`col-${id}`)).toHaveAttribute("data-header", "false");
    }
    const tabs = screen.getByRole("navigation", { name: "カラム" });
    expect(screen.getByRole("button", { name: "カラムメニュー" })).toBeInTheDocument();
    const adds = screen.getAllByRole("button", { name: "カラム追加" });
    expect(adds).toHaveLength(1);
    expect(tabs).toContainElement(adds[0]);
  });

  it("スワイプでページが変わると visibleColumnId とタブの点灯が追従する", () => {
    render(<DeckScreen />);
    setBox(strip(), { clientWidth: 400 });
    arriveAtHome(strip());

    scrollTo(strip(), 800);
    expect(useDeck.getState().visibleColumnId).toBe("c_notif");
    expect(screen.getByRole("button", { name: "通知" })).toHaveAttribute("aria-current", "true");
  });

  it("タブを押すと即座に点灯してそのページへスクロールし、着くまで途中の位置を反映しない", () => {
    render(<DeckScreen />);
    setBox(strip(), { clientWidth: 400 });

    fireEvent.click(screen.getByRole("button", { name: "#nostr" }));
    expect(useDeck.getState().visibleColumnId).toBe("c_hashtag");
    expect(strip().scrollLeft).toBe(400);
    expect(useDeck.getState().jumpTarget).toBeNull();

    scrollTo(strip(), 0);
    expect(useDeck.getState().visibleColumnId).toBe("c_hashtag");
  });

  it("見ているカラムを移動しても画面に残り、削除したらスクロール位置のカラムになる", () => {
    render(<DeckScreen />);
    setBox(strip(), { clientWidth: 400 });
    arriveAtHome(strip());
    act(() => {
      useDeck.setState({ visibleColumnId: "c_hashtag" });
    });

    act(() => {
      useDeck.getState().moveColumn("c_hashtag", 1);
    });
    expect(strip().scrollLeft).toBe(800);
    expect(useDeck.getState().visibleColumnId).toBe("c_hashtag");

    act(() => {
      useDeck.getState().removeColumn("c_hashtag");
    });
    expect(useDeck.getState().visibleColumnId).toBe("c_notif");
  });

  describe("[#938] マウント時はホームのカラムから始める", () => {
    beforeEach(() => {
      // [通知, フォロー中, #nostr]。前回は #nostr を見ていた
      const [following, tag, notif] = DEFAULT_COLUMNS;
      useDeck.setState({
        columns: [
          { ...notif, order: 0 },
          { ...following, order: 1 },
          { ...tag, order: 2 },
        ],
        visibleColumnId: "c_hashtag",
      });
      vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(400);
    });

    afterEach(() => {
      vi.restoreAllMocks();
    });

    it("compact でマウントしたらフォロー中のページから始まる。ほかの画面から戻った（作り直した）ときも同じ", () => {
      const first = render(<DeckScreen />);
      expect(useDeck.getState().visibleColumnId).toBe("c_following");
      expect(strip().scrollLeft).toBe(400);
      expect(screen.getByRole("button", { name: "フォロー中" })).toHaveAttribute("aria-current", "true");

      scrollTo(strip(), 0);
      expect(useDeck.getState().visibleColumnId).toBe("c_notif");
      first.unmount();

      render(<DeckScreen />);
      expect(useDeck.getState().visibleColumnId).toBe("c_following");
      expect(strip().scrollLeft).toBe(400);
    });

    it("jumpTarget があればそちらが優先", () => {
      useDeck.setState({ jumpTarget: "c_hashtag" });
      render(<DeckScreen />);
      expect(useDeck.getState().visibleColumnId).toBe("c_hashtag");
      expect(strip().scrollLeft).toBe(800);
      expect(useDeck.getState().jumpTarget).toBeNull();
    });
  });
});

describe("expanded", () => {
  beforeEach(() => {
    mockViewport(1400);
  });

  it("カラムヘッダ付きで並べ、タブ列は無く、末尾のカラム追加でダイアログを開く。幅は widths どおり", () => {
    useDeck.setState({ widths: { c_hashtag: "S" } });
    render(<DeckScreen />);
    expect(screen.getByTestId("col-c_following")).toHaveAttribute("data-header", "true");
    expect(screen.queryByRole("navigation", { name: "カラム" })).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "#nostr" })).toHaveAttribute("data-width", "S");
    expect(screen.getByRole("region", { name: "フォロー中" })).toHaveAttribute("data-width", "M");
    expect(screen.getByRole("region", { name: "通知" })).toHaveAttribute("data-width", "M");

    fireEvent.click(screen.getByRole("button", { name: "カラム追加" }));
    expect(useDeck.getState().showAddColumn).toBe(true);
  });

  it("横スクロールしなければ visibleColumnId は null、スクロールすると左端のカラム。jump でそのカラムの位置へ", () => {
    useDeck.setState({ visibleColumnId: "c_notif" });
    render(<DeckScreen />);
    expect(useDeck.getState().visibleColumnId).toBeNull();

    const el = strip();
    setBox(el, { scrollWidth: 2000, clientWidth: 700 });
    ["フォロー中", "#nostr", "通知"].forEach((name, i) => {
      setBox(screen.getByRole("region", { name }), { offsetLeft: [0, 348, 696][i] });
    });
    scrollTo(el, 350);
    expect(useDeck.getState().visibleColumnId).toBe("c_hashtag");

    act(() => {
      useDeck.getState().jumpTo("c_notif");
    });
    expect(el.scrollLeft).toBe(696);
    expect(useDeck.getState().jumpTarget).toBeNull();
  });

  it("[#336][#540] ◀ ▶ で動いたカラムだけに flip クラスが付き、時間で外れる", () => {
    render(<DeckScreen />);

    act(() => {
      useDeck.getState().moveColumn("c_hashtag", 1);
    });
    // following(index 不変) には付かず、入れ替わった hashtag/notif には付く
    expect(screen.getByRole("region", { name: "フォロー中" })).not.toHaveClass(deckStyles.flip);
    expect(screen.getByRole("region", { name: "#nostr" })).toHaveClass(deckStyles.flip);
    expect(screen.getByRole("region", { name: "通知" })).toHaveClass(deckStyles.flip);

    act(() => {
      vi.advanceTimersByTime(400);
    });
    expect(screen.getByRole("region", { name: "#nostr" })).not.toHaveClass(deckStyles.flip);
    expect(screen.getByRole("region", { name: "通知" })).not.toHaveClass(deckStyles.flip);
  });

  it("[#540] reduced-motion なら flip クラスを付けない", () => {
    mockReducedMotion(true);
    render(<DeckScreen />);

    act(() => {
      useDeck.getState().moveColumn("c_hashtag", 1);
    });
    expect(screen.getByRole("region", { name: "#nostr" })).not.toHaveClass(deckStyles.flip);
    expect(screen.getByRole("region", { name: "通知" })).not.toHaveClass(deckStyles.flip);
  });
});

it("[#661] 幅が変わって expanded ⇔ それ以外になってもカラムを作り直さない", () => {
  mockViewport(1400);
  render(<DeckScreen />);
  setViewportWidth(400);
  expect(screen.getByRole("navigation", { name: "カラム" })).toBeInTheDocument();
  setViewportWidth(1400);
  expect(screen.queryByRole("navigation", { name: "カラム" })).not.toBeInTheDocument();

  expect(Object.fromEntries(mounts)).toEqual({ c_following: 1, c_hashtag: 1, c_notif: 1 });
  expect(screen.getByTestId("col-c_notif")).toHaveAttribute("data-header", "true");
});

it("カラムが 0 件なら案内を出す", () => {
  mockViewport(400, { hover: false });
  useDeck.setState({ columns: [] });
  render(<DeckScreen />);
  expect(screen.getByText(/カラムがありません/)).toBeInTheDocument();
  expect(within(screen.getByRole("navigation", { name: "カラム" })).getAllByRole("button")).toHaveLength(1);
});
