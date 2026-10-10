import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { renderWithRouter } from "../test/renderWithRouter";
import { MenuButton, type MenuEntry } from "./MenuButton";

// [#540] 開いている間の「戻る」を history で扱うため、Router の中で描く
function renderMenu(entries: MenuEntry[]) {
  renderWithRouter(
    <>
      <MenuButton label="未送信" triggerClassName="trigger" entries={entries}>
        未送信
      </MenuButton>
      <p>外側</p>
    </>,
  );
  return screen.getByRole("button", { name: "未送信" });
}

it("押すと開き（aria-expanded）、最初の項目に focus。項目を押すと閉じてから onSelect", async () => {
  const user = userEvent.setup();
  const retry = vi.fn();
  const trigger = renderMenu([
    { type: "item", label: "再送", onSelect: retry },
    { type: "item", label: "下書きに戻す", onSelect: () => {} },
  ]);
  expect(trigger).toHaveAttribute("aria-haspopup", "menu");
  expect(trigger).toHaveAttribute("aria-expanded", "false");

  await user.click(trigger);
  expect(trigger).toHaveAttribute("aria-expanded", "true");
  expect(screen.getByRole("menu", { name: "未送信" })).toBeInTheDocument();
  expect(screen.getByRole("menuitem", { name: "再送" })).toHaveFocus();

  await user.keyboard("{ArrowDown}");
  expect(screen.getByRole("menuitem", { name: "下書きに戻す" })).toHaveFocus();
  await user.keyboard("{ArrowUp}");
  expect(screen.getByRole("menuitem", { name: "再送" })).toHaveFocus();

  await user.click(screen.getByRole("menuitem", { name: "再送" }));
  expect(retry).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole("menu")).toBeNull();
  expect(trigger).toHaveAttribute("aria-expanded", "false");
  // [#797] 項目を選んだらトリガへフォーカスを戻す
  expect(trigger).toHaveFocus();
});

it("外側の押下・Escape（trigger へ focus を戻す）・もう一度 trigger で閉じる", async () => {
  const user = userEvent.setup();
  const trigger = renderMenu([{ type: "item", label: "再送", onSelect: () => {} }]);

  await user.click(trigger);
  await user.click(screen.getByText("外側"));
  expect(screen.queryByRole("menu")).toBeNull();

  await user.click(trigger);
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("menu")).toBeNull();
  expect(trigger).toHaveFocus();

  await user.click(trigger);
  await user.click(trigger);
  expect(screen.queryByRole("menu")).toBeNull();
});

it("見出しと区切りを描く", async () => {
  const user = userEvent.setup();
  const trigger = renderMenu([
    { type: "header", label: "この投稿" },
    { type: "item", label: "再送", onSelect: () => {} },
    { type: "separator" },
    { type: "item", label: "削除", onSelect: () => {}, tone: "danger" },
  ]);
  await user.click(trigger);
  const menu = screen.getByRole("menu");
  expect(menu.querySelector("p")).toHaveTextContent("この投稿");
  expect(menu.querySelectorAll("hr")).toHaveLength(1);
  expect(screen.getAllByRole("menuitem").map((b) => b.textContent)).toEqual(["再送", "削除"]);
});

it("[#797] 項目のアイコンと右端の表示（trailing）。ariaLabel があれば読み上げ名はそれ", async () => {
  const user = userEvent.setup();
  const trigger = renderMenu([
    {
      type: "item",
      label: "DM",
      onSelect: () => {},
      icon: <svg data-testid="icon" />,
      trailing: "2",
      ariaLabel: "DM（未読 2 件）",
    },
    { type: "item", label: "リレー", onSelect: () => {}, trailing: "1 / 2 接続中" },
  ]);
  await user.click(trigger);
  const dm = screen.getByRole("menuitem", { name: "DM（未読 2 件）" });
  expect(dm).toHaveTextContent("DM2");
  expect(dm.querySelector("[data-testid='icon']")).not.toBeNull();
  expect(screen.getByRole("menuitem", { name: "リレー1 / 2 接続中" })).toBeInTheDocument();
});

it("[#797] メニューの中のスクロールでは閉じない（外のスクロールでは閉じる）", async () => {
  const user = userEvent.setup();
  const trigger = renderMenu([{ type: "item", label: "再送", onSelect: () => {} }]);
  await user.click(trigger);
  fireEvent.scroll(screen.getByRole("menu"));
  expect(screen.getByRole("menu")).toBeInTheDocument();
  fireEvent.scroll(window);
  expect(screen.queryByRole("menu")).toBeNull();
});
