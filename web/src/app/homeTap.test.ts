import { describe, expect, it, vi } from "vitest";
import { columnScrollOf, registerColumnScroll } from "../features/deck/columnScroll";
import { type HomeTapInput, homeTapAction } from "./homeTap";

/**
 * [#919] ネイティブ HomeTapTest（#917）の 7 件を同じ名前で移す。
 * フォロー中・ハッシュタグ・通知の 3 カラム。表示中は visible、scrolled = 途中まで読んでいる。
 */
function input(visible: string, scrolled: boolean, rest: Partial<HomeTapInput> = {}): HomeTapInput {
  return {
    dest: "home",
    hasOverlay: false,
    visibleColumnId: visible,
    columnIds: ["following", "tag", "notif"],
    notifColumnId: "notif",
    atTop: !scrolled,
    ...rest,
  };
}

describe("HomeTapTest", () => {
  it("scrolls_the_visible_column_to_top", () => {
    expect(homeTapAction(input("tag", true))).toBe("scrollTop");
  });

  it("at_top_goes_to_following", () => {
    expect(homeTapAction(input("tag", false))).toBe("openHome");
  });

  it("following_column_scrolls_to_top_too", () => {
    expect(homeTapAction(input("following", true))).toBe("scrollTop");
  });

  it("from_another_screen_goes_home_without_scrolling", () => {
    expect(homeTapAction(input("tag", true, { dest: "search" }))).toBe("openHome");
  });

  it("with_detail_open_closes_it_and_goes_home", () => {
    expect(homeTapAction(input("tag", true, { hasOverlay: true }))).toBe("openHome");
  });

  it("notifications_column_counts_as_another_screen", () => {
    // 通知カラムを見ている間はナビの「通知」が選択表示なので、ホームはフォロー中へ移る操作のまま
    expect(homeTapAction(input("notif", true))).toBe("openHome");
  });

  it("consume_clears_the_request", () => {
    // Web は要求をストアに残さず、カラムの窓口を 1 回呼んで終わる。窓口を外したカラムは先頭扱い
    const scrollToTop = vi.fn();
    const unregister = registerColumnScroll("tag", { atTop: () => false, scrollToTop });
    const atTop = () => columnScrollOf("tag")?.atTop() ?? true;
    expect(homeTapAction(input("tag", !atTop()))).toBe("scrollTop");
    columnScrollOf("tag")?.scrollToTop();
    expect(scrollToTop).toHaveBeenCalledTimes(1);
    unregister();
    expect(columnScrollOf("tag")).toBeNull();
    expect(homeTapAction(input("tag", !atTop()))).toBe("openHome");
  });
});

it("表示中のカラムが無い・消えているときはフォロー中へ", () => {
  expect(homeTapAction(input("tag", true, { visibleColumnId: null }))).toBe("openHome");
  expect(homeTapAction(input("gone", true))).toBe("openHome");
});
