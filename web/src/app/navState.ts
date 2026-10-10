import type { UIMatch } from "react-router";
import { t } from "../i18n";
import { isAccountIconActive } from "./accountMenuModel";

/**
 * 宛先（URL のパス）。messages = DM（自分のアイコンのメニューから開く）、channels = パブリックチャット（ナビの 3 枠目）。
 * notFound = どのナビも選択しない
 */
export type Dest = "home" | "search" | "messages" | "channels" | "notifications" | "settings" | "notFound";

/** 宛先の上に重ねる詳細 */
export type OverlayKind = "thread" | "profile";

/** 子ルートの handle。描画は AppShell がこれで決める */
export type RouteHandle = { dest: Dest } | { overlay: OverlayKind };

function isRouteHandle(handle: unknown): handle is RouteHandle {
  if (typeof handle !== "object" || handle === null) return false;
  const h = handle as Record<string, unknown>;
  return typeof h.dest === "string" || typeof h.overlay === "string";
}

/** 一番深いルートの handle */
export function routeHandleOf(matches: UIMatch[]): RouteHandle | null {
  const handle = matches.at(-1)?.handle;
  return isRouteHandle(handle) ? handle : null;
}

/** account = 自分のアイコン（押すとメニュー。未ログインなら設定へ。#797） */
export type NavKey = "home" | "search" | "channels" | "notifications" | "account";

/** 下部ナビの順（ネイティブ BottomBar と同じ） */
export const NAV_ORDER: readonly NavKey[] = ["home", "search", "channels", "notifications", "account"];

/** ナビの表示名。言語の切り替えに追従するよう、定数ではなく呼ぶたびに引く */
export function navLabel(key: NavKey): string {
  switch (key) {
    case "home":
      return t("nav_home");
    case "search":
      return t("nav_search");
    case "channels":
      return t("nav_public_chat");
    case "notifications":
      return t("nav_notifications");
    case "account":
      return t("web_nav_account_menu");
  }
}

export const NAV_PATH: Record<NavKey, string> = {
  home: "/",
  search: "/search",
  channels: "/channels",
  notifications: "/notifications",
  account: "/settings",
};

/** 通知が選択中か。通知画面、またはデッキで通知カラムを見ているとき（ネイティブ #405） */
export function isNotificationsActive(
  dest: Dest,
  visibleColumnId: string | null,
  notifColumnId: string | null,
): boolean {
  return (
    dest === "notifications" ||
    (dest === "home" && visibleColumnId !== null && visibleColumnId === notifColumnId)
  );
}

/** 下部ナビの選択状態 */
export function bottomSelection(
  dest: Dest,
  visibleColumnId: string | null,
  notifColumnId: string | null,
): Record<NavKey, boolean> {
  const notifications = isNotificationsActive(dest, visibleColumnId, notifColumnId);
  return {
    home: dest === "home" && !notifications,
    search: dest === "search",
    channels: dest === "channels",
    notifications,
    account: isAccountIconActive(dest),
  };
}

/** レールのホーム。デッキ表示中で、見ているカラムが目次（ピン留め）に無いとき（ネイティブ #409） */
export function isRailHomeActive(
  dest: Dest,
  visibleColumnId: string | null,
  pinnedIds: readonly string[],
): boolean {
  return dest === "home" && (visibleColumnId === null || !pinnedIds.includes(visibleColumnId));
}

/** レールの目次の 1 件が選択中か */
export function isPinnedActive(dest: Dest, visibleColumnId: string | null, id: string): boolean {
  return dest === "home" && id === visibleColumnId;
}
