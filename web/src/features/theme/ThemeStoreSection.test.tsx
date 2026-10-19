import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, beforeAll, expect, it, vi } from "vitest";
import { useSession } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { useToast } from "../../ui/toast";
import { type CustomColors, DEFAULT_CUSTOM_COLORS } from "./customPalette";
import { ThemeStoreSection } from "./ThemeStoreSection";
import type { ThemeEntry } from "./themeEntry";
import { DEFAULT_THEME_PREFS, useThemePrefs, useThemeUndo } from "./themePrefs";

const sampleEntry: ThemeEntry = {
  name: "Sakura",
  colors: { bg: "#FDF3F5", text: "#2A1E22", accent: "#C2557A" },
  minAppVersion: "0.3.0",
  schema: 1,
  author: "a".repeat(64),
  dTag: "nostrism:theme:sakura",
  eventId: "eventid1",
  createdAt: 1_000,
};

// リレーへは繋がず、一覧はこのテスト用の1件で固定する（読み込み中も終えておく）
// アプリバージョン（ビルド時の値）はテストごとに差し替える（#642 S17）。既定はテーマの minAppVersion と同じ
const appVersion = vi.hoisted(() => ({ value: "0.3.0" as string | null }));
vi.mock("../../lib/appVersion", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/appVersion")>()),
  get APP_VERSION() {
    return appVersion.value;
  },
}));

vi.mock("./themeStore", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./themeStore")>()),
  useThemeStoreEntries: () => ({ loading: false, entries: [sampleEntry] }),
}));

beforeAll(() => {
  installDialogPolyfill();
});

afterEach(() => {
  appVersion.value = "0.3.0";
  useThemePrefs.setState(DEFAULT_THEME_PREFS);
  useThemeUndo.setState(null);
  useSession.setState({ status: "out", method: null, pubkey: null });
  useToast.setState({ queue: [] });
});

/** 呼び出し元（ThemeEditModal）の下書き状態を模した小さなラッパー。onSelect は draft へ反映する */
function Wrapper({ initialDraft = DEFAULT_CUSTOM_COLORS }: { initialDraft?: CustomColors }) {
  const [draft, setDraft] = useState(initialDraft);
  return <ThemeStoreSection draft={draft} onSelect={(colors) => setDraft(colors)} />;
}

it("行を押すと下書きへ反映するだけで、本体（useThemePrefs）は変えない", async () => {
  const onSelect = vi.fn();
  const user = userEvent.setup();
  render(<ThemeStoreSection draft={DEFAULT_CUSTOM_COLORS} onSelect={onSelect} />);

  await user.click(screen.getByRole("button", { name: /Sakura/ }));
  expect(onSelect).toHaveBeenCalledWith(sampleEntry.colors, "Sakura");
  expect(useThemePrefs.getState().custom).toEqual(DEFAULT_CUSTOM_COLORS);
  expect(useThemeUndo.getState()).toBeNull();
});

it("下書きに反映されると「プレビュー中」バッジが付く", async () => {
  const user = userEvent.setup();
  render(<Wrapper />);
  await user.click(screen.getByRole("button", { name: /Sakura/ }));
  expect(screen.getByText("プレビュー中")).toBeInTheDocument();
});

it("下書きと一致するが本体には未適用のテーマは「プレビュー中」（「適用中」ではない）", () => {
  render(<Wrapper initialDraft={sampleEntry.colors} />);
  // applied は現在の useThemePrefs.custom と比べる。下書きだけ一致しても「適用中」にはならない
  expect(screen.queryByText("適用中")).toBeNull();
  expect(screen.getByText("プレビュー中")).toBeInTheDocument();
});

it("検索に一致しなければ「条件に合うテーマがありません」", async () => {
  const user = userEvent.setup();
  render(<Wrapper />);
  await user.type(screen.getByPlaceholderText("テーマ名・作者名で検索"), "存在しない名前");
  expect(screen.getByText("条件に合うテーマがありません。")).toBeInTheDocument();
});

it("自分のテーマの行だけ削除をリクエストできる（確認してから送信）", async () => {
  useSession.setState({ status: "in", method: "nip07", pubkey: sampleEntry.author });
  const user = userEvent.setup();
  render(<Wrapper />);

  await user.click(screen.getByRole("button", { name: "削除をリクエスト" }));
  const dialog = screen.getByRole("dialog", { name: "このテーマの削除をリクエストしますか？" });
  await user.click(within(dialog).getByRole("button", { name: "削除をリクエスト" }));

  // EventStore に元イベントが無いテスト環境なので送信できないが、確認 → 送信の導線自体を検証する
  expect(screen.queryByRole("dialog")).toBeNull();
  await waitFor(() => expect(useToast.getState().queue).toContain("削除リクエストを送信できませんでした。"));
});

it("他人のテーマの行には削除をリクエストが出ない", () => {
  useSession.setState({ status: "in", method: "nip07", pubkey: "b".repeat(64) });
  render(<Wrapper />);
  expect(screen.queryByRole("button", { name: "削除をリクエスト" })).toBeNull();
});

it("この配色をコピーは下書き（draft）を共有コードにして書き込む", async () => {
  const user = userEvent.setup();
  render(<ThemeStoreSection draft={sampleEntry.colors} onSelect={vi.fn()} />);
  await user.click(screen.getByRole("button", { name: "この配色をコピー" }));
  const code = await navigator.clipboard.readText();
  expect(code.startsWith("nostrism-theme:1:MyTheme:")).toBe(true);
  expect(code).toContain("FDF3F5");
});

it("共有コードの取り込みは下書きへ反映するだけ（本体は変えない）。不正な形式はトーストで知らせる", async () => {
  const onSelect = vi.fn();
  const user = userEvent.setup();
  render(<ThemeStoreSection draft={DEFAULT_CUSTOM_COLORS} onSelect={onSelect} />);

  const input = screen.getByPlaceholderText("共有コード");
  await user.type(input, "不正なコード");
  await user.click(screen.getByRole("button", { name: "取り込む" }));
  await waitFor(() => expect(useToast.getState().queue).toContain("共有コードの形式が正しくありません。"));

  await user.clear(input);
  await user.type(input, "nostrism-theme:1:Mono:000000,FFFFFF,FF0000:0.3.0");
  await user.click(screen.getByRole("button", { name: "取り込む" }));
  expect(onSelect).toHaveBeenCalledWith({ bg: "#000000", text: "#FFFFFF", accent: "#FF0000" }, "Mono");
  expect(useThemePrefs.getState().custom).toEqual(DEFAULT_CUSTOM_COLORS);
});

it("テーマの minAppVersion がアプリより新しければ名前の横に ⚠ vX.Y.Z+ を出す（ネイティブと同じ）", () => {
  appVersion.value = "0.2.9";
  render(<Wrapper />);
  const row = screen.getByRole("button", { name: /Sakura/ });
  expect(within(row).getByText("⚠ v0.3.0+")).toBeInTheDocument();
});

it("minAppVersion がアプリと同じか古ければ ⚠ を出さない（桁は数値で比べる）", () => {
  appVersion.value = "0.3.0";
  const { unmount } = render(<Wrapper />);
  expect(screen.queryByText(/⚠/)).toBeNull();
  unmount();

  appVersion.value = "0.10.0";
  render(<Wrapper />);
  expect(screen.queryByText(/⚠/)).toBeNull();
});

it("アプリバージョンが取れないビルド（null）では ⚠ を出さない", () => {
  appVersion.value = null;
  render(<Wrapper />);
  expect(screen.queryByText(/⚠/)).toBeNull();
});
