import { expect, it, vi } from "vitest";
import type { MenuEntry } from "../../ui/MenuButton";
import { type MoreMenuActions, moreMenuEntries } from "./moreMenu";

const NOTE1 = "note1abcdefghijklmnopqrstuvwxyz";
const NEVENT = "nevent1abcdefghijklmnopqrstuvwxyz";

function actions(): MoreMenuActions {
  return {
    follow: vi.fn(),
    unfollow: vi.fn(),
    bookmark: vi.fn(),
    unbookmark: vi.fn(),
    pin: vi.fn(),
    unpin: vi.fn(),
    requestDelete: vi.fn(),
    mute: vi.fn(),
    unmute: vi.fn(),
    block: vi.fn(),
    unblock: vi.fn(),
    report: vi.fn(),
    copyText: vi.fn(),
    copyLink: vi.fn(),
    copyId: vi.fn(),
    copyNote1: vi.fn(),
    copyNevent: vi.fn(),
    viewJson: vi.fn(),
    translate: vi.fn(),
    hideTranslation: vi.fn(),
  };
}

/** 比べやすい形（header / separator / 項目のラベル。danger は印を付ける） */
function shape(entries: MenuEntry[]): string[] {
  return entries.map((e) =>
    e.type === "separator"
      ? "---"
      : e.type === "header"
        ? `[${e.label}]`
        : `${e.label}${e.tone === "danger" ? " (danger)" : ""}`,
  );
}

const COPIES = [
  "テキストをコピー",
  "リンクをコピー（njump）",
  "投稿IDをコピー",
  "note1abcdefg… をコピー",
  "nevent1abcde… をコピー",
];

it("他人・client あり・フォロー中・ブックマーク済みの並び", () => {
  const on = actions();
  const entries = moreMenuEntries({
    clientName: "Nostrism",
    isMine: false,
    isFollowing: true,
    isBookmarked: true,
    isPinned: null,
    muteState: "none" as const,
    note1: NOTE1,
    nevent: NEVENT,
    on,
  });
  expect(shape(entries)).toEqual([
    "[Nostrism から投稿]",
    "---",
    "フォロー解除",
    "ブックマークを解除",
    "このユーザーをミュート",
    "このユーザーをブロック",
    "通報 (danger)",
    "---",
    ...COPIES,
  ]);

  // 項目は対応する操作を呼ぶ
  const select = (label: string) => {
    const entry = entries.find((e) => e.type === "item" && e.label === label);
    if (entry?.type === "item") entry.onSelect();
  };
  select("フォロー解除");
  select("ブックマークを解除");
  select("このユーザーをミュート");
  select("このユーザーをブロック");
  select("通報");
  select("リンクをコピー（njump）");
  select("note1abcdefg… をコピー");
  expect(on.unfollow).toHaveBeenCalledTimes(1);
  expect(on.unbookmark).toHaveBeenCalledTimes(1);
  expect(on.mute).toHaveBeenCalledTimes(1);
  expect(on.block).toHaveBeenCalledTimes(1);
  expect(on.report).toHaveBeenCalledTimes(1);
  expect(on.copyLink).toHaveBeenCalledTimes(1);
  expect(on.copyNote1).toHaveBeenCalledTimes(1);
  expect(on.follow).not.toHaveBeenCalled();
  expect(on.bookmark).not.toHaveBeenCalled();
});

it("未フォローは「フォロー」、自分の kind:3 が未取得（null）ならフォロー項目なし", () => {
  const base = {
    clientName: null,
    isMine: false,
    isBookmarked: null,
    isPinned: null,
    muteState: "none" as const,
    note1: NOTE1,
    nevent: NEVENT,
    on: actions(),
  };
  expect(shape(moreMenuEntries({ ...base, isFollowing: false }))).toEqual([
    "フォロー",
    "このユーザーをミュート",
    "このユーザーをブロック",
    "通報 (danger)",
    "---",
    ...COPIES,
  ]);
  expect(shape(moreMenuEntries({ ...base, isFollowing: null }))).toEqual([
    "このユーザーをミュート",
    "このユーザーをブロック",
    "通報 (danger)",
    "---",
    ...COPIES,
  ]);
});

it("未ブックマークは「ブックマーク」、自分の kind:10003 が未取得（null）ならブックマーク項目なし", () => {
  const base = {
    clientName: null,
    isMine: false,
    isFollowing: null,
    isPinned: null,
    muteState: "none" as const,
    note1: NOTE1,
    nevent: NEVENT,
    on: actions(),
  };
  expect(shape(moreMenuEntries({ ...base, isBookmarked: false }))).toEqual([
    "ブックマーク",
    "このユーザーをミュート",
    "このユーザーをブロック",
    "通報 (danger)",
    "---",
    ...COPIES,
  ]);
  expect(shape(moreMenuEntries({ ...base, isBookmarked: null }))).toEqual([
    "このユーザーをミュート",
    "このユーザーをブロック",
    "通報 (danger)",
    "---",
    ...COPIES,
  ]);
});

it("[#963] ミュート中の人は「ミュートを解除」「このユーザーをブロック」", () => {
  const on = actions();
  const entries = moreMenuEntries({
    clientName: null,
    isMine: false,
    isFollowing: null,
    isBookmarked: null,
    isPinned: null,
    muteState: "muted",
    note1: NOTE1,
    nevent: NEVENT,
    on,
  });
  expect(shape(entries)).toEqual([
    "ミュートを解除",
    "このユーザーをブロック",
    "通報 (danger)",
    "---",
    ...COPIES,
  ]);
  const select = (label: string) => {
    const entry = entries.find((e) => e.type === "item" && e.label === label);
    if (entry?.type === "item") entry.onSelect();
  };
  select("ミュートを解除");
  select("このユーザーをブロック");
  expect(on.unmute).toHaveBeenCalledTimes(1);
  expect(on.block).toHaveBeenCalledTimes(1);
  expect(on.mute).not.toHaveBeenCalled();
});

it("[#963] ブロック中の人は「ブロックを解除」だけ", () => {
  const on = actions();
  const entries = moreMenuEntries({
    clientName: null,
    isMine: false,
    isFollowing: null,
    isBookmarked: null,
    isPinned: null,
    muteState: "blocked",
    note1: NOTE1,
    nevent: NEVENT,
    on,
  });
  expect(shape(entries)).toEqual(["ブロックを解除", "通報 (danger)", "---", ...COPIES]);
  const entry = entries[0];
  if (entry.type === "item") entry.onSelect();
  expect(on.unblock).toHaveBeenCalledTimes(1);
  expect(on.unmute).not.toHaveBeenCalled();
  expect(on.mute).not.toHaveBeenCalled();
  expect(on.block).not.toHaveBeenCalled();
});

it("自分の投稿はフォロー項目・ミュート項目なしで「削除をリクエスト」、代わりに固定の項目", () => {
  const on = actions();
  const entries = moreMenuEntries({
    clientName: "Nostrism",
    isMine: true,
    isFollowing: true,
    isBookmarked: false,
    isPinned: false,
    muteState: "none" as const,
    note1: NOTE1,
    nevent: NEVENT,
    on,
  });
  expect(shape(entries)).toEqual([
    "[Nostrism から投稿]",
    "---",
    "ブックマーク",
    "プロフィールに固定",
    "削除をリクエスト (danger)",
    "---",
    ...COPIES,
  ]);
  const select = (label: string) => {
    const entry = entries.find((e) => e.type === "item" && e.label === label);
    if (entry?.type === "item") entry.onSelect();
  };
  select("プロフィールに固定");
  expect(on.pin).toHaveBeenCalledTimes(1);
  expect(on.unpin).not.toHaveBeenCalled();
});

it("自分の投稿で固定済みなら「プロフィールの固定を解除」、kind:10001 が未取得（null）なら固定の項目なし", () => {
  const base = {
    clientName: null,
    isMine: true,
    isFollowing: null,
    isBookmarked: null,
    muteState: "none" as const,
    note1: NOTE1,
    nevent: NEVENT,
  };
  const on = actions();
  const entries = moreMenuEntries({ ...base, isPinned: true, on });
  expect(shape(entries)).toEqual(["プロフィールの固定を解除", "削除をリクエスト (danger)", "---", ...COPIES]);
  const entry = entries.find((e) => e.type === "item" && e.label === "プロフィールの固定を解除");
  if (entry?.type === "item") entry.onSelect();
  expect(on.unpin).toHaveBeenCalledTimes(1);

  expect(shape(moreMenuEntries({ ...base, isPinned: null, on: actions() }))).toEqual([
    "削除をリクエスト (danger)",
    "---",
    ...COPIES,
  ]);
});

it("client タグが無ければ見出しと最初の区切りを出さない", () => {
  const entries = moreMenuEntries({
    clientName: null,
    isMine: true,
    isFollowing: null,
    isBookmarked: null,
    isPinned: null,
    muteState: "none" as const,
    note1: NOTE1,
    nevent: NEVENT,
    on: actions(),
  });
  expect(shape(entries)[0]).toBe("削除をリクエスト (danger)");
});

it("translationVisible が undefined / null なら「翻訳」を出さない。false は「翻訳」、true は「翻訳を隠す」", () => {
  const base = {
    clientName: null,
    isMine: true,
    isFollowing: null,
    isBookmarked: null,
    isPinned: null,
    muteState: "none" as const,
    note1: NOTE1,
    nevent: NEVENT,
  };
  expect(shape(moreMenuEntries({ ...base, on: actions() }))[0]).toBe("削除をリクエスト (danger)");
  expect(shape(moreMenuEntries({ ...base, translationVisible: null, on: actions() }))[0]).toBe(
    "削除をリクエスト (danger)",
  );

  const onShow = actions();
  const shown = moreMenuEntries({ ...base, translationVisible: false, on: onShow });
  expect(shape(shown)).toEqual(["削除をリクエスト (danger)", "---", "翻訳", ...COPIES]);
  const translateEntry = shown.find((e) => e.type === "item" && e.label === "翻訳");
  if (translateEntry?.type === "item") translateEntry.onSelect();
  expect(onShow.translate).toHaveBeenCalledTimes(1);
  expect(onShow.hideTranslation).not.toHaveBeenCalled();

  const onHide = actions();
  const hidden = moreMenuEntries({ ...base, translationVisible: true, on: onHide });
  expect(shape(hidden)).toEqual(["削除をリクエスト (danger)", "---", "翻訳を隠す", ...COPIES]);
  const hideEntry = hidden.find((e) => e.type === "item" && e.label === "翻訳を隠す");
  if (hideEntry?.type === "item") hideEntry.onSelect();
  expect(onHide.hideTranslation).toHaveBeenCalledTimes(1);
  expect(onHide.translate).not.toHaveBeenCalled();
});

it("開発者モードの間は末尾に「イベントJSONを表示」", () => {
  const on = actions();
  const base = {
    clientName: null,
    isMine: true,
    isFollowing: null,
    isBookmarked: null,
    isPinned: null,
    muteState: "none" as const,
    note1: NOTE1,
    nevent: NEVENT,
    on,
  };
  expect(shape(moreMenuEntries({ ...base, developerMode: false })).at(-1)).toBe("nevent1abcde… をコピー");
  const entries = moreMenuEntries({ ...base, developerMode: true });
  expect(shape(entries)).toEqual([
    "削除をリクエスト (danger)",
    "---",
    ...COPIES,
    "---",
    "イベントJSONを表示",
  ]);
  const last = entries.at(-1);
  if (last?.type === "item") last.onSelect();
  expect(on.viewJson).toHaveBeenCalledTimes(1);
});
