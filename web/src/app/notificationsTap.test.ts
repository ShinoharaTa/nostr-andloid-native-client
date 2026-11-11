import { describe, expect, it } from "vitest";
import { type NotificationsTapInput, notificationsTapAction } from "./notificationsTap";

/**
 * [#992] ネイティブ NotificationsTapTest（#991）と同じ場合分け。
 * フォロー中・ハッシュタグ・通知の 3 カラム。表示中は visible。
 */
function input(visible: string, rest: Partial<NotificationsTapInput> = {}): NotificationsTapInput {
  return { dest: "home", hasOverlay: false, visibleColumnId: visible, notifColumnId: "notif", ...rest };
}

describe("NotificationsTapTest", () => {
  it("from_another_column_jumps_and_starts_at_top", () => {
    expect(notificationsTapAction(input("tag"))).toBe("openAtTop");
  });

  it("while_viewing_the_column_scrolls_to_top", () => {
    expect(notificationsTapAction(input("notif"))).toBe("scrollTop");
  });

  it("from_another_screen_opens_the_column_at_top", () => {
    expect(notificationsTapAction(input("notif", { dest: "search" }))).toBe("openAtTop");
  });

  it("with_a_detail_on_top_closes_it_and_starts_at_top", () => {
    expect(notificationsTapAction(input("notif", { hasOverlay: true }))).toBe("openAtTop");
  });

  it("without_the_column_opens_the_screen", () => {
    expect(notificationsTapAction(input("following", { notifColumnId: null }))).toBe("open");
  });

  it("while_viewing_the_screen_scrolls_it_to_top", () => {
    expect(notificationsTapAction(input("following", { notifColumnId: null, dest: "notifications" }))).toBe(
      "scrollTop",
    );
  });
});
