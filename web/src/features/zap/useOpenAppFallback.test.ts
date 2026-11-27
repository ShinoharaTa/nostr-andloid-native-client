import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { OPEN_APP_TIMEOUT_MS, useOpenAppFallback } from "./useOpenAppFallback";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  Reflect.deleteProperty(document, "visibilityState");
});

function setup() {
  const onFail = vi.fn();
  const hook = renderHook(() => useOpenAppFallback(onFail));
  return { onFail, start: hook.result.current, unmount: hook.unmount };
}

it("押して 1500ms 何も来なければ onFail（1 回だけ）", () => {
  const { onFail, start } = setup();
  start();
  vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS - 1);
  expect(onFail).not.toHaveBeenCalled();
  vi.advanceTimersByTime(1);
  expect(onFail).toHaveBeenCalledTimes(1);
  // 判定が済んだ後の blur は何もしない
  window.dispatchEvent(new Event("blur"));
  vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS);
  expect(onFail).toHaveBeenCalledTimes(1);
});

it.each([
  ["blur", () => window.dispatchEvent(new Event("blur"))],
  ["pagehide", () => window.dispatchEvent(new Event("pagehide"))],
  [
    "visibilitychange（hidden）",
    () => {
      Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
      document.dispatchEvent(new Event("visibilitychange"));
    },
  ],
])("%s が来たら開けたとみなして onFail を呼ばない", (_, leave) => {
  const { onFail, start } = setup();
  start();
  vi.advanceTimersByTime(500);
  leave();
  vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS);
  expect(onFail).not.toHaveBeenCalled();
  expect(vi.getTimerCount()).toBe(0);
});

it("visible のままの visibilitychange では止めない", () => {
  const { onFail, start } = setup();
  start();
  document.dispatchEvent(new Event("visibilitychange"));
  vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS);
  expect(onFail).toHaveBeenCalledTimes(1);
});

it("もう一度押したら前の判定は捨てて測り直す", () => {
  const { onFail, start } = setup();
  start();
  vi.advanceTimersByTime(1000);
  start();
  vi.advanceTimersByTime(1000);
  expect(onFail).not.toHaveBeenCalled();
  expect(vi.getTimerCount()).toBe(1);
  vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS - 1000);
  expect(onFail).toHaveBeenCalledTimes(1);
});

it("アンマウントでタイマーとリスナーを片付ける", () => {
  const removeWindow = vi.spyOn(window, "removeEventListener");
  const removeDocument = vi.spyOn(document, "removeEventListener");
  const { onFail, start, unmount } = setup();
  start();
  unmount();
  expect(vi.getTimerCount()).toBe(0);
  expect(removeWindow).toHaveBeenCalledWith("blur", expect.any(Function));
  expect(removeWindow).toHaveBeenCalledWith("pagehide", expect.any(Function));
  expect(removeDocument).toHaveBeenCalledWith("visibilitychange", expect.any(Function));
  vi.advanceTimersByTime(OPEN_APP_TIMEOUT_MS);
  expect(onFail).not.toHaveBeenCalled();
  removeWindow.mockRestore();
  removeDocument.mockRestore();
});
