import { expect, it } from "vitest";
import { bottomSelection, isPinnedActive, isRailHomeActive, NAV_ORDER } from "./navState";

function selectedKeys(selection: ReturnType<typeof bottomSelection>) {
  return NAV_ORDER.filter((key) => selection[key]);
}

it("通知カラムを見ている間は下部ナビの「通知」だけが選択され、「ホーム」は外れる", () => {
  expect(selectedKeys(bottomSelection("home", "c_notif", "c_notif"))).toEqual(["notifications"]);
  expect(selectedKeys(bottomSelection("home", "c_following", "c_notif"))).toEqual(["home"]);
  expect(selectedKeys(bottomSelection("notifications", null, null))).toEqual(["notifications"]);
  expect(selectedKeys(bottomSelection("notFound", "c_notif", "c_notif"))).toEqual([]);
});

it("レールのホームは見ているカラムが目次に無いときだけ選択", () => {
  const ids = ["c_following", "c_hashtag", "c_notif"];
  expect(isRailHomeActive("home", null, ids)).toBe(true);
  expect(isRailHomeActive("home", "c_hashtag", ["c_hashtag"])).toBe(false);
  expect(isRailHomeActive("home", "col_hashtag_1", ["c_hashtag"])).toBe(true);
  expect(isRailHomeActive("search", null, ids)).toBe(false);
});

it("目次の選択はデッキ表示中だけ", () => {
  expect(isPinnedActive("home", "c_hashtag", "c_hashtag")).toBe(true);
  expect(isPinnedActive("home", "c_notif", "c_hashtag")).toBe(false);
  for (const dest of ["search", "messages", "channels", "notifications", "settings", "notFound"] as const) {
    expect(isPinnedActive(dest, "c_hashtag", "c_hashtag")).toBe(false);
  }
});

it("[#797] 3 枠目はパブリックチャット（channels のときだけ）、自分のアイコンは設定と DM のとき選択", () => {
  expect(NAV_ORDER).toEqual(["home", "search", "channels", "notifications", "account"]);
  expect(selectedKeys(bottomSelection("channels", null, null))).toEqual(["channels"]);
  expect(selectedKeys(bottomSelection("messages", null, null))).toEqual(["account"]);
  expect(selectedKeys(bottomSelection("settings", null, null))).toEqual(["account"]);
  expect(selectedKeys(bottomSelection("search", null, null))).toEqual(["search"]);
});
