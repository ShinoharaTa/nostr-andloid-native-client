import { act, render, renderHook, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useT } from "../i18n";
import { LOCALE_KEY, setLocaleSetting } from "../i18n/locale";
import { DEFAULT_COLUMNS } from "../lib/columns";
import { useSession } from "../signer/session";
import { useDeck } from "../store/deck";
import { installDialogPolyfill } from "../test/dialog";
import { PUBKEY, resetSession } from "../test/fakeNostr";
import { clearViewport, mockViewport } from "../test/viewport";
import { routes } from "./routes";

// カラムの中身（購読・仮想リスト）は描かない（AppShell.test.tsx と同じ）
vi.mock("../features/deck/DeckColumn", () => ({
  DeckColumn: () => <div />,
  ColumnMenu: () => <button type="button">menu</button>,
}));
vi.mock("../features/deck/useColumnFeed", () => ({
  useColumnFeed: () => ({
    mode: "column",
    loading: false,
    events: [],
    loadingOlder: false,
    loadOlder: () => {},
    refresh: () => {},
  }),
}));

beforeEach(() => {
  installDialogPolyfill();
  localStorage.clear();
  window.history.replaceState(null, "");
  useDeck.setState({ columns: structuredClone([...DEFAULT_COLUMNS]), widths: {}, visibleColumnId: null });
  useSession.setState({ status: "in", method: "nip07", pubkey: PUBKEY });
});

afterEach(() => {
  setLocaleSetting("ja");
  clearViewport();
  resetSession();
});

it("useT() は言語の切替で値が変わる", () => {
  const { result } = renderHook(() => useT());
  expect(result.current("nav_home")).toBe("ホーム");
  act(() => setLocaleSetting("en"));
  expect(result.current("nav_home")).toBe("Home");
});

it("設定 → 表示 で English を選ぶと、ナビ・設定画面が即座に英語になり、保存される", async () => {
  const user = userEvent.setup();
  mockViewport(400);
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ["/settings/display"] })} />);

  // ja: ナビ・言語ブロック（自動 / 日本語 / English）
  const nav = screen.getByRole("navigation", { name: "メイン" });
  expect(within(nav).getByRole("button", { name: "ホーム" })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "言語 / Language" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "日本語" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "自動（ブラウザに合わせる）" })).toHaveAttribute(
    "aria-pressed",
    "false",
  );

  await user.click(screen.getByRole("button", { name: "English" }));

  // en: ナビ・設定画面が英語。選択肢のラベルは固定
  const navEn = screen.getByRole("navigation", { name: "Main" });
  expect(within(navEn).getByRole("button", { name: "Home" })).toBeInTheDocument();
  expect(within(navEn).getByRole("button", { name: "Public chat" })).toBeInTheDocument();
  expect(within(navEn).getByRole("button", { name: "Account menu" })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Data saver" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "English" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "日本語" })).toHaveAttribute("aria-pressed", "false");
  expect(screen.getByRole("button", { name: "Auto (match browser)" })).toBeInTheDocument();
  expect(localStorage.getItem(LOCALE_KEY)).toBe("en");

  await user.click(screen.getByRole("button", { name: "日本語" }));
  expect(screen.getByRole("navigation", { name: "メイン" })).toBeInTheDocument();
  expect(localStorage.getItem(LOCALE_KEY)).toBe("ja");
});

it("デッキのヘッダ（カラムのタブ）も言語の切替で再描画される", () => {
  mockViewport(400);
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ["/"] })} />);
  expect(
    within(screen.getByRole("navigation", { name: "カラム" })).getByRole("button", { name: "フォロー中" }),
  ).toBeInTheDocument();

  act(() => setLocaleSetting("en"));

  expect(
    within(screen.getByRole("navigation", { name: "Columns" })).getByRole("button", { name: "Following" }),
  ).toBeInTheDocument();
});
