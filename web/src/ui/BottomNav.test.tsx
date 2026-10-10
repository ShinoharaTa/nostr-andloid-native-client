import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import type { NavKey } from "../app/navState";
import { useSession } from "../signer/session";
import { PUBKEY, resetSession } from "../test/fakeNostr";
import { BottomNav } from "./BottomNav";

const NONE: Record<NavKey, boolean> = {
  home: false,
  search: false,
  channels: false,
  notifications: false,
  account: false,
};

afterEach(() => {
  resetSession();
});

/** 自分のアイコンのメニューが画面を移るので Router の中で描く */
function renderNav(ui: ReactElement) {
  return render(ui, { wrapper: ({ children }) => <MemoryRouter>{children}</MemoryRouter> });
}

it("ホーム・検索・パブリックチャット・通知・自分のアイコンの順に並び、ラベル文字は描かない", () => {
  renderNav(<BottomNav selected={NONE} onSelect={() => {}} />);
  const nav = screen.getByRole("navigation", { name: "メイン" });
  expect(
    within(nav)
      .getAllByRole("button")
      .map((b) => b.getAttribute("aria-label")),
  ).toEqual(["ホーム", "検索", "パブリックチャット", "通知", "アカウントのメニュー"]);
  expect(screen.queryByText("ホーム")).not.toBeInTheDocument();
});

it("選択中の宛先だけ aria-current=page、押すと onSelect", async () => {
  const onSelect = vi.fn();
  renderNav(<BottomNav selected={{ ...NONE, notifications: true }} onSelect={onSelect} />);

  for (const button of screen.getAllByRole("button")) {
    if (button.getAttribute("aria-label") === "通知") expect(button).toHaveAttribute("aria-current", "page");
    else expect(button).not.toHaveAttribute("aria-current");
  }

  await userEvent.click(screen.getByRole("button", { name: "検索" }));
  expect(onSelect).toHaveBeenCalledWith("search");
  await userEvent.click(screen.getByRole("button", { name: "パブリックチャット" }));
  expect(onSelect).toHaveBeenCalledWith("channels");
});

it("[#797] 未読 DM の数を自分のアイコンに重ねる（99+ まで）。0 なら出さない", () => {
  const { rerender } = renderNav(<BottomNav selected={NONE} badges={{ account: 3 }} onSelect={() => {}} />);
  expect(screen.getByRole("button", { name: "アカウントのメニュー（未読 3 件）" })).toHaveTextContent("3");

  rerender(<BottomNav selected={NONE} badges={{ account: 150 }} onSelect={() => {}} />);
  expect(screen.getByRole("button", { name: "アカウントのメニュー（未読 150 件）" })).toHaveTextContent(
    "99+",
  );

  rerender(<BottomNav selected={NONE} badges={{ account: 0 }} onSelect={() => {}} />);
  expect(screen.getByRole("button", { name: "アカウントのメニュー" })).not.toHaveTextContent(/\d/);
});

it("[#797] 自分のアイコン: ログイン中はメニューを開く（onSelect は呼ばない）、未ログインは onSelect('account')（設定へ）", async () => {
  const onSelect = vi.fn();
  renderNav(<BottomNav selected={{ ...NONE, account: true }} onSelect={onSelect} />);
  const icon = screen.getByRole("button", { name: "アカウントのメニュー" });
  expect(icon).toHaveAttribute("aria-current", "page");
  expect(icon).not.toHaveAttribute("aria-haspopup");
  await userEvent.click(icon);
  expect(onSelect).toHaveBeenCalledWith("account");
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  onSelect.mockClear();

  act(() => useSession.setState({ status: "in", method: "nip07", pubkey: PUBKEY }));
  const trigger = screen.getByRole("button", { name: "アカウントのメニュー" });
  expect(trigger).toHaveAttribute("aria-haspopup", "menu");
  expect(trigger).toHaveAttribute("aria-current", "page");
  await userEvent.click(trigger);
  expect(screen.getByRole("menu", { name: "アカウントのメニュー" })).toBeInTheDocument();
  expect(onSelect).not.toHaveBeenCalled();
});
