import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it } from "vitest";
import { KANSAI_KEY, setKansaiMode, setLocaleSetting, useLocale } from "../../i18n/locale";
import { NYAN_MODE_KEY, useNyanMode } from "../../ui/nyan";
import { DEFAULT_EMBED_PREFS, EMBED_PREFS_KEY, useEmbedPrefs } from "../linkcard/embedPrefs";
import { REACTION_COUNTS_KEY, useReactionCountsPref } from "../reactions/reactionCountsPref";
import { DEFAULT_THEME_PREFS, THEME_KEY, useThemePrefs } from "../theme/themePrefs";
import { DisplaySection } from "./DisplaySection";
import { reloadTimeDisplayForTest, TIME_DISPLAY_KEY } from "./timeDisplay";

afterEach(() => {
  localStorage.clear();
  useEmbedPrefs.setState(DEFAULT_EMBED_PREFS);
  useNyanMode.setState({ mode: "off" });
  useThemePrefs.setState(DEFAULT_THEME_PREFS);
  useReactionCountsPref.setState({ enabled: true });
  reloadTimeDisplayForTest();
});

it("埋め込み表示の 6 項目は既定ですべて ON", () => {
  render(<DisplaySection />);
  for (const label of [
    "動画（mp4 等）をインライン再生",
    "YouTube のサムネイルを表示",
    "Spotify のカードを表示",
    "その他リンクの OGP カードを表示",
    "OGP カードの画像を読み込む",
    "カードを出したリンクのURLを本文から隠す",
  ]) {
    expect(screen.getByRole("checkbox", { name: label })).toBeChecked();
  }
});

it("トグルを押すと保存され、次の描画にも残る", async () => {
  const user = userEvent.setup();
  const { unmount } = render(<DisplaySection />);

  await user.click(screen.getByRole("checkbox", { name: "YouTube のサムネイルを表示" }));

  expect(useEmbedPrefs.getState().youtube).toBe(false);
  expect(JSON.parse(localStorage.getItem(EMBED_PREFS_KEY) ?? "null")).toEqual({
    ...DEFAULT_EMBED_PREFS,
    youtube: false,
  });
  unmount();

  render(<DisplaySection />);
  expect(screen.getByRole("checkbox", { name: "YouTube のサムネイルを表示" })).not.toBeChecked();
});

it("にゃんモードは既定でオフ。選ぶと保存され、次の描画にも残る", async () => {
  const user = userEvent.setup();
  const { unmount } = render(<DisplaySection />);

  expect(screen.getByRole("button", { name: "オフ" })).toHaveAttribute("aria-pressed", "true");

  await user.click(screen.getByRole("button", { name: "全員" }));

  expect(useNyanMode.getState().mode).toBe("all");
  expect(localStorage.getItem(NYAN_MODE_KEY)).toBe("all");
  unmount();

  render(<DisplaySection />);
  expect(screen.getByRole("button", { name: "全員" })).toHaveAttribute("aria-pressed", "true");
});

it("[#674] 廃人モードは既定でオフ。トグルを押すと保存され、次の描画にも残る", async () => {
  const user = userEvent.setup();
  const { unmount } = render(<DisplaySection />);

  const toggle = screen.getByRole("checkbox", { name: "廃人モードを使う" });
  expect(toggle).not.toBeChecked();

  await user.click(toggle);

  expect(useThemePrefs.getState().density).toBe("dense");
  expect(JSON.parse(localStorage.getItem(THEME_KEY) ?? "null").density).toBe("dense");
  unmount();

  render(<DisplaySection />);
  expect(screen.getByRole("checkbox", { name: "廃人モードを使う" })).toBeChecked();
});

it("OGP カードを表示 が OFF の間は OGP カードの画像を読み込む を無効にする", async () => {
  const user = userEvent.setup();
  render(<DisplaySection />);

  const ogpImages = screen.getByRole("checkbox", { name: "OGP カードの画像を読み込む" });
  expect(ogpImages).toBeEnabled();

  await user.click(screen.getByRole("checkbox", { name: "その他リンクの OGP カードを表示" }));

  expect(ogpImages).toBeDisabled();
});

it("項目の順序はネイティブと同じ: テーマ → 種別の視覚表示 → 表示サイズ → 文字サイズ → 文字を太くする → にゃにゃにゃ → 埋め込み表示。データセーバーは末尾のまま（Web 追加。#587）。うにゅうと握手（#710）→ 廃人モードはにゃにゃにゃの後（Web 追加。#674）", () => {
  const { container } = render(<DisplaySection />);
  const text = container.textContent ?? "";
  const labels = [
    "テーマ",
    "種別の視覚表示",
    "表示サイズ",
    "文字サイズ",
    "文字を太くする",
    "にゃにゃにゃウイルス",
    "うにゅうと握手",
    "廃人モード",
    "リンクの埋め込み表示",
    "データセーバー",
  ];
  const positions = labels.map((label) => text.indexOf(label));
  expect(positions.every((p) => p >= 0)).toBe(true);
  expect(positions).toEqual([...positions].sort((a, b) => a - b));
  // 「デフォルトのリアクション」は #587 で独立セクション（ReactionSection）へ戻したので、ここには無い
  expect(screen.queryByText("デフォルトのリアクション")).toBeNull();
});

it("[#710] うにゅうと握手は既定でオフ。トグルを押すと保存され、解決後の言語が ja-kansai になる", async () => {
  const user = userEvent.setup();
  try {
    render(<DisplaySection />);
    const toggle = screen.getByRole("checkbox", { name: "うにゅうと握手する" });
    expect(toggle).not.toBeChecked();
    expect(toggle).toBeEnabled();

    await user.click(toggle);

    expect(localStorage.getItem(KANSAI_KEY)).toBe("on");
    expect(useLocale.getState().resolved).toBe("ja-kansai");
    // 見出し・チェックボックスは関西弁になっても同じ文字列（戻せなくならないように）
    expect(screen.getByRole("heading", { name: "うにゅうと握手" })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "うにゅうと握手する" })).toBeChecked();
    expect(screen.getByRole("heading", { name: "廃人モード" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "にゃにゃにゃウイルス" })).toBeInTheDocument();
  } finally {
    setKansaiMode(false);
    setLocaleSetting("ja");
  }
});

it("[#710] 言語が English のときはうにゅうと握手を無効にして注意を出す", () => {
  try {
    setLocaleSetting("en");
    render(<DisplaySection />);
    expect(screen.getByRole("checkbox", { name: "Shake hands with Unyu" })).toBeDisabled();
    expect(screen.getByText("Available only when the UI language is Japanese")).toBeInTheDocument();
  } finally {
    setLocaleSetting("ja");
  }
});

it("[#941] リアクションの数は既定でオン。トグルを切ると保存され、次の描画にも残る", async () => {
  const user = userEvent.setup();
  const { unmount } = render(<DisplaySection />);

  const toggle = screen.getByRole("checkbox", { name: "リアクションの数を出す" });
  expect(toggle).toBeChecked();

  await user.click(toggle);

  expect(useReactionCountsPref.getState().enabled).toBe(false);
  expect(localStorage.getItem(REACTION_COUNTS_KEY)).toBe("off");
  unmount();

  render(<DisplaySection />);
  expect(screen.getByRole("checkbox", { name: "リアクションの数を出す" })).not.toBeChecked();
});

it("[#940] 時刻の表示は既定で経過時間。3 択を選ぶと保存され、次の描画にも残る", async () => {
  const user = userEvent.setup();
  const { unmount } = render(<DisplaySection />);

  expect(screen.getByRole("heading", { name: "時刻の表示" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "経過時間" })).toHaveAttribute("aria-pressed", "true");

  await user.click(screen.getByRole("button", { name: "日時" }));
  expect(localStorage.getItem(TIME_DISPLAY_KEY)).toBe("absolute");
  expect(screen.getByRole("button", { name: "日時" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "経過時間" })).toHaveAttribute("aria-pressed", "false");

  await user.click(screen.getByRole("button", { name: "日時（秒まで）" }));
  expect(localStorage.getItem(TIME_DISPLAY_KEY)).toBe("absolute_seconds");
  unmount();

  reloadTimeDisplayForTest();
  render(<DisplaySection />);
  expect(screen.getByRole("button", { name: "日時（秒まで）" })).toHaveAttribute("aria-pressed", "true");
});

it("[#940] 時刻の表示はネイティブと同じく 文字を太くする の下・にゃにゃにゃ の上", () => {
  const { container } = render(<DisplaySection />);
  const text = container.textContent ?? "";
  const positions = ["文字を太くする", "時刻の表示", "にゃにゃにゃウイルス"].map((label) =>
    text.indexOf(label),
  );
  expect(positions.every((p) => p >= 0)).toBe(true);
  expect(positions).toEqual([...positions].sort((a, b) => a - b));
});
