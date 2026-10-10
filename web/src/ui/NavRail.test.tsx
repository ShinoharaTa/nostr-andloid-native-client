import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { expect, it, vi } from "vitest";
import type { NavKey } from "../app/navState";
import { NavRail, type RailPinned } from "./NavRail";

const NONE: Record<NavKey, boolean> = {
  home: false,
  search: false,
  channels: false,
  notifications: false,
  account: false,
};

const PINNED: RailPinned[] = [
  { id: "c_following", title: "フォロー中", kind: "FOLLOWING", active: false },
  { id: "c_hashtag", title: "#nostr", kind: "HASHTAG", active: true },
  { id: "c_notif", title: "通知", kind: "NOTIFICATIONS", active: false },
];

function renderRail(props: Partial<Parameters<typeof NavRail>[0]> = {}) {
  const handlers = { onSelect: vi.fn(), onOpenColumn: vi.fn(), onAddColumn: vi.fn() };
  // 自分のアイコンのメニューが画面を移るので Router の中で描く
  render(
    <MemoryRouter>
      <NavRail
        selected={NONE}
        homeActive={false}
        pinned={PINNED}
        showNotifications={false}
        {...handlers}
        {...props}
      />
    </MemoryRouter>,
  );
  return handlers;
}

/** ブランド画像とボタンを DOM の順に並べる（リレーの接続表示のボタンは除く。別に確かめる） */
function railItems() {
  const nav = screen.getByRole("navigation", { name: "メイン" });
  return [...nav.querySelectorAll("img[alt='Nostrism'], button:not([aria-label^='リレー接続'])")].map(
    (el) => el.getAttribute("aria-label") ?? `img:${el.getAttribute("alt")}`,
  );
}

it("ブランド → ホーム → 目次 → カラム追加 → 検索 → パブリックチャット → 自分のアイコンの順（通知カラムがあれば通知は出さない）", () => {
  renderRail();
  expect(railItems()).toEqual([
    "img:Nostrism",
    "ホーム",
    "フォロー中",
    "#nostr",
    "通知",
    "カラム追加",
    "検索",
    "パブリックチャット",
    "アカウントのメニュー",
  ]);
  const nav = screen.getByRole("navigation", { name: "メイン" });
  expect(within(nav).getByRole("button", { name: /^リレー接続 / })).toBeInTheDocument();
});

it("通知カラムが無ければ検索・パブリックチャット・通知の順に出す", () => {
  renderRail({ pinned: PINNED.slice(0, 2), showNotifications: true });
  expect(railItems().slice(-4)).toEqual(["検索", "パブリックチャット", "通知", "アカウントのメニュー"]);
});

it("目次の選択は aria-current=true、ホームの選択は aria-current=page", () => {
  renderRail({ homeActive: true });
  expect(screen.getByRole("button", { name: "#nostr" })).toHaveAttribute("aria-current", "true");
  expect(screen.getByRole("button", { name: "フォロー中" })).not.toHaveAttribute("aria-current");
  expect(screen.getByRole("button", { name: "ホーム" })).toHaveAttribute("aria-current", "page");
});

it("目次で onOpenColumn、カラム追加で onAddColumn、宛先で onSelect", async () => {
  const user = userEvent.setup();
  const { onSelect, onOpenColumn, onAddColumn } = renderRail();

  await user.click(screen.getByRole("button", { name: "#nostr" }));
  expect(onOpenColumn).toHaveBeenCalledWith("c_hashtag");

  await user.click(screen.getByRole("button", { name: "カラム追加" }));
  expect(onAddColumn).toHaveBeenCalledTimes(1);

  await user.click(screen.getByRole("button", { name: "検索" }));
  expect(onSelect).toHaveBeenCalledWith("search");
});

it("[#797] 未読 DM の数を自分のアイコンに重ねる（99+ まで）。0 なら出さない。未ログインで押すと onSelect('account')（設定へ）", async () => {
  const user = userEvent.setup();
  const { onSelect } = renderRail({ badges: { account: 3 } });
  const account = screen.getByRole("button", { name: "アカウントのメニュー（未読 3 件）" });
  expect(account).toHaveTextContent("3");
  await user.click(account);
  expect(onSelect).toHaveBeenCalledWith("account");
  cleanup();

  renderRail({ badges: { account: 150 } });
  expect(screen.getByRole("button", { name: "アカウントのメニュー（未読 150 件）" })).toHaveTextContent(
    "99+",
  );
  cleanup();

  renderRail({ badges: { account: 0 } });
  expect(screen.getByRole("button", { name: "アカウントのメニュー" })).not.toHaveTextContent(/\d/);
});

it("パブリックチャットを押すと onSelect('channels')、選択中は aria-current=page", async () => {
  const user = userEvent.setup();
  const { onSelect } = renderRail({ selected: { ...NONE, channels: true } });
  const chat = screen.getByRole("button", { name: "パブリックチャット" });
  expect(chat).toHaveAttribute("aria-current", "page");
  await user.click(chat);
  expect(onSelect).toHaveBeenCalledWith("channels");
});
