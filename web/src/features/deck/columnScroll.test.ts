import { expect, it } from "vitest";
import { columnScrollOf, registerColumnScroll } from "./columnScroll";

const handle = (atTop: boolean) => ({ atTop: () => atTop, scrollToTop: () => {} });

it("登録したカラムの窓口を引け、解除すると消える", () => {
  const h = handle(false);
  const unregister = registerColumnScroll("c_a", h);
  expect(columnScrollOf("c_a")).toBe(h);
  expect(columnScrollOf("c_b")).toBeNull();
  unregister();
  expect(columnScrollOf("c_a")).toBeNull();
});

it("同じ id で上書きしたら新しい方を引き、古い方の解除では消えない", () => {
  const first = handle(true);
  const second = handle(false);
  const unregisterFirst = registerColumnScroll("c_a", first);
  const unregisterSecond = registerColumnScroll("c_a", second);
  expect(columnScrollOf("c_a")).toBe(second);
  unregisterFirst();
  expect(columnScrollOf("c_a")).toBe(second);
  unregisterSecond();
  expect(columnScrollOf("c_a")).toBeNull();
});
