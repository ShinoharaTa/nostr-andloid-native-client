import { act } from "@testing-library/react";

type ChangeListener = (e: MediaQueryListEvent) => void;

/** [#648] レールを出すかの判定に使う「ホバーできる端末」クエリ（useLayoutMode.ts と同じ文字列） */
const HOVER_FINE_QUERY = "(hover: hover) and (pointer: fine)";

let width = 0;
/** [#648] 既定はホバーあり（PC 相当。hover を意識しない既存テストの前提を保つ） */
let hover = true;
const listeners = new Set<{ query: string; listener: ChangeListener; last: boolean }>();

/** `(min-width: Npx)` と [#648] のホバー判定クエリだけを評価する。それ以外のクエリは常に false */
function evaluate(query: string): boolean {
  const trimmed = query.trim();
  if (trimmed === HOVER_FINE_QUERY) return hover;
  const m = /^\(min-width:\s*(\d+)px\)$/.exec(trimmed);
  return m ? width >= Number(m[1]) : false;
}

/** 閾値をまたいだクエリの `change` リスナーを act の中で呼ぶ（setViewportWidth / setViewportHover 共通） */
function notifyListeners(): void {
  act(() => {
    for (const entry of [...listeners]) {
      const matches = evaluate(entry.query);
      if (matches === entry.last) continue;
      entry.last = matches;
      entry.listener({ matches, media: entry.query } as MediaQueryListEvent);
    }
  });
}

/**
 * window.matchMedia を幅 width・ホバー可否 hover のスタブに差し替える（jsdom には matchMedia が無い）。
 * hover 省略時は true（PC 相当）。`change` リスナーは保持し、setViewportWidth / setViewportHover で
 * 閾値をまたいだものだけ呼ぶ。
 */
export function mockViewport(w: number, options?: { hover?: boolean }): void {
  width = w;
  hover = options?.hover ?? true;
  listeners.clear();
  window.matchMedia = (query: string): MediaQueryList => {
    const mql = {
      media: query,
      get matches() {
        return evaluate(query);
      },
      onchange: null,
      addEventListener(type: string, listener: ChangeListener) {
        if (type === "change") listeners.add({ query, listener, last: evaluate(query) });
      },
      removeEventListener(type: string, listener: ChangeListener) {
        if (type !== "change") return;
        for (const entry of listeners) {
          if (entry.query === query && entry.listener === listener) listeners.delete(entry);
        }
      },
      addListener() {},
      removeListener() {},
      dispatchEvent: () => false,
    };
    return mql as unknown as MediaQueryList;
  };
}

/** 幅を変え、閾値をまたいだクエリの `change` リスナーを act の中で呼ぶ */
export function setViewportWidth(w: number): void {
  width = w;
  notifyListeners();
}

/** [#648] ホバー可否を変え、変化したクエリの `change` リスナーを act の中で呼ぶ */
export function setViewportHover(h: boolean): void {
  hover = h;
  notifyListeners();
}

/** matchMedia を消す（jsdom の既定に戻す） */
export function clearViewport(): void {
  listeners.clear();
  // jsdom の window には matchMedia が無い。スタブを外して「無い」状態に戻す
  Reflect.deleteProperty(window, "matchMedia");
}

type Box = { clientWidth?: number; scrollWidth?: number; offsetLeft?: number; offsetWidth?: number };

/** jsdom はレイアウトを計算しない（寸法が常に 0）ので、要素に寸法を与える */
export function setBox(el: Element, box: Box): void {
  for (const [key, value] of Object.entries(box)) {
    Object.defineProperty(el, key, { configurable: true, get: () => value });
  }
}

type ResizeListener = () => void;

let vvHeight = 0;
const vvListeners = new Set<ResizeListener>();

/** window.visualViewport をスタブに差し替える（jsdom には無い） */
export function mockVisualViewport(height: number): void {
  vvHeight = height;
  vvListeners.clear();
  const vv = {
    get height() {
      return vvHeight;
    },
    addEventListener(type: string, listener: ResizeListener) {
      if (type === "resize") vvListeners.add(listener);
    },
    removeEventListener(type: string, listener: ResizeListener) {
      if (type === "resize") vvListeners.delete(listener);
    },
  };
  Object.defineProperty(window, "visualViewport", { configurable: true, value: vv });
}

/** 高さを変えて resize リスナーを呼ぶ */
export function setVisualViewportHeight(height: number): void {
  vvHeight = height;
  for (const listener of [...vvListeners]) listener();
}

/** window.visualViewport を消す（jsdom の既定に戻す） */
export function clearVisualViewport(): void {
  vvListeners.clear();
  Reflect.deleteProperty(window, "visualViewport");
}
