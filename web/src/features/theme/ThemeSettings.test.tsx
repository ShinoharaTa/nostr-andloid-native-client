import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, expect, it } from "vitest";
import { useSession } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { DEFAULT_CUSTOM_COLORS } from "./customPalette";
import { ThemeSettings } from "./ThemeSettings";
import {
  CURRENT_THEME_VERSION,
  DEFAULT_THEME_PREFS,
  initTheme,
  THEME_KEY,
  useThemePrefs,
  useThemeUndo,
} from "./themePrefs";

beforeAll(() => {
  installDialogPolyfill();
});

let dispose: (() => void) | null = null;

afterEach(() => {
  dispose?.();
  dispose = null;
  localStorage.clear();
  useThemePrefs.setState(DEFAULT_THEME_PREFS);
  useThemeUndo.setState(null);
  useSession.setState({ status: "out", method: null, pubkey: null });
  const root = document.documentElement;
  root.removeAttribute("data-theme");
  root.removeAttribute("data-bold");
  root.removeAttribute("style");
  for (const meta of document.querySelectorAll('meta[name="theme-color"]')) meta.remove();
});

const html = () => document.documentElement;

it("現在の設定が選ばれた状態で出る（既定はダーク・小・太字オフ・表示サイズ標準・種別表示なし）", () => {
  render(<ThemeSettings />);
  expect(screen.getByRole("radio", { name: "ダーク" })).toBeChecked();
  expect(screen.getByRole("radio", { name: "小" })).toBeChecked();
  expect(screen.getByRole("radio", { name: "標準" })).toBeChecked();
  expect(screen.getByRole("radio", { name: "なし" })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: "太い文字を使う" })).not.toBeChecked();
  expect(screen.getByRole("group", { name: "テーマ" })).toBeInTheDocument();
  expect(screen.getByRole("group", { name: "文字サイズ" })).toBeInTheDocument();
  expect(screen.getByRole("group", { name: "表示サイズ" })).toBeInTheDocument();
  expect(screen.getByRole("group", { name: "種別の視覚表示" })).toBeInTheDocument();
  // カスタム以外では色の編集・テーマストアの導線行は出さない
  expect(screen.queryByRole("button", { name: /色をカスタマイズ/ })).toBeNull();
  expect(screen.queryByRole("button", { name: /テーマストアから取得/ })).toBeNull();
});

it("項目の順序はネイティブと同じ: テーマ → 種別の視覚表示 → 表示サイズ → 文字サイズ → 文字を太くする（#587）", () => {
  render(<ThemeSettings />);
  const groups = screen.getAllByRole("group").map((el) => el.querySelector("legend")?.textContent);
  expect(groups).toEqual(["テーマ", "種別の視覚表示", "表示サイズ", "文字サイズ", "文字を太くする"]);
});

it("選ぶとすぐ保存して <html> へ反映する", async () => {
  const user = userEvent.setup();
  dispose = initTheme();
  render(<ThemeSettings />);

  await user.click(screen.getByRole("radio", { name: "ライト" }));
  expect(screen.getByRole("radio", { name: "ライト" })).toBeChecked();
  expect(html().dataset.theme).toBe("light");

  await user.click(screen.getByRole("radio", { name: "大" }));
  expect(html().style.getPropertyValue("--text-scale")).toBe("1.35");

  await user.click(screen.getByRole("radio", { name: "最大" }));
  expect(html().style.getPropertyValue("--ui-scale")).toBe("1.3");

  await user.click(screen.getByRole("checkbox", { name: "太い文字を使う" }));
  expect(html().hasAttribute("data-bold")).toBe(true);

  await user.click(screen.getByRole("radio", { name: "縦ライン" }));

  expect(JSON.parse(localStorage.getItem(THEME_KEY) ?? "null")).toEqual({
    mode: "light",
    textScale: "l",
    bold: true,
    custom: DEFAULT_CUSTOM_COLORS,
    noteAccent: "line",
    uiScale: "l",
    density: "normal",
    version: CURRENT_THEME_VERSION,
  });
});

it("[#985] 文字サイズはスーパーハッカーが一番左。選ぶと xs（×0.75）になる", async () => {
  const user = userEvent.setup();
  dispose = initTheme();
  render(<ThemeSettings />);
  const group = screen.getByRole("group", { name: "文字サイズ" });
  const labels = within(group)
    .getAllByRole("radio")
    .map((el) => el.closest("label")?.textContent);
  expect(labels).toEqual(["スーパーハッカー", "小", "中", "大"]);

  await user.click(within(group).getByRole("radio", { name: "スーパーハッカー" }));
  expect(html().style.getPropertyValue("--text-scale")).toBe("0.75");
  expect(JSON.parse(localStorage.getItem(THEME_KEY) ?? "null").textScale).toBe("xs");
});

it("種別の視覚表示を選ぶと種別→色の凡例が出る。「なし」に戻すと消える（S11）", async () => {
  const user = userEvent.setup();
  render(<ThemeSettings />);
  expect(screen.queryByRole("list", { name: "種別の色の凡例" })).toBeNull();

  await user.click(screen.getByRole("radio", { name: "背景色" }));
  const legend = screen.getByRole("list", { name: "種別の色の凡例" });
  expect(legend.textContent).toContain("リポスト");
  expect(legend.textContent).toContain("引用");
  expect(legend.textContent).toContain("リプライ");
  expect(legend.textContent).toContain("リアクション");

  await user.click(screen.getByRole("radio", { name: "なし" }));
  expect(screen.queryByRole("list", { name: "種別の色の凡例" })).toBeNull();
});

it("文字サイズの説明文を出す（S12）", () => {
  render(<ThemeSettings />);
  expect(screen.getByText("文字だけを大きく・小さく。")).toBeInTheDocument();
});

it("テーマを「カスタム」にすると、色をカスタマイズ / テーマストアから取得の導線行が出る", async () => {
  const user = userEvent.setup();
  render(<ThemeSettings />);

  await user.click(screen.getByRole("radio", { name: "カスタム" }));
  expect(screen.getByRole("button", { name: /色をカスタマイズ/ })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /テーマストアから取得/ })).toBeInTheDocument();
});

it("「色をカスタマイズ」を押すとテーマ編集モーダル（カスタマイズタブ）を開く", async () => {
  const user = userEvent.setup();
  render(<ThemeSettings />);
  await user.click(screen.getByRole("radio", { name: "カスタム" }));

  await user.click(screen.getByRole("button", { name: /色をカスタマイズ/ }));
  const dialog = screen.getByRole("dialog", { name: "テーマ" });
  expect(screen.getByRole("button", { name: "カスタマイズ", pressed: true })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Midnight" })).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "閉じる" }));
  expect(dialog).not.toBeInTheDocument();
});

it("「テーマストアから取得」を押すとテーマ編集モーダル（ストアタブ）を開く", async () => {
  const user = userEvent.setup();
  render(<ThemeSettings />);
  await user.click(screen.getByRole("radio", { name: "カスタム" }));

  await user.click(screen.getByRole("button", { name: /テーマストアから取得/ }));
  expect(screen.getByRole("dialog", { name: "テーマ" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "ストア", pressed: true })).toBeInTheDocument();
});

it("取り消しバーはモーダルが開いている間は出さない（二重表示しない）", async () => {
  const user = userEvent.setup();
  render(<ThemeSettings />);
  await user.click(screen.getByRole("radio", { name: "カスタム" }));
  await user.click(screen.getByRole("button", { name: /色をカスタマイズ/ }));
  await user.click(screen.getByRole("button", { name: "Sakura" }));
  await user.click(screen.getByRole("button", { name: "適用" }));

  // モーダル内にだけ出る（設定画面側には出さない）
  const dialog = screen.getByRole("dialog", { name: "テーマ" });
  expect(screen.getAllByText("「Sakura」を適用しました")).toHaveLength(1);
  expect(screen.getByText("「Sakura」を適用しました").closest("dialog")).toBe(dialog);

  await user.click(screen.getByRole("button", { name: "閉じる" }));
  expect(screen.getByText("「Sakura」を適用しました")).toBeInTheDocument();
});
