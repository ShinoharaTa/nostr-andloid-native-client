import { t } from "../../i18n";
import type { MenuEntry } from "../../ui/MenuButton";
import type { UserMuteState } from "../mute/muteList";

export type MoreMenuActions = {
  follow(): void;
  unfollow(): void;
  bookmark(): void;
  unbookmark(): void;
  pin(): void;
  unpin(): void;
  requestDelete(): void;
  mute(): void;
  unmute(): void;
  block(): void;
  unblock(): void;
  report(): void;
  copyText(): void;
  copyLink(): void;
  copyId(): void;
  copyNote1(): void;
  copyNevent(): void;
  viewJson(): void;
  translate(): void;
  hideTranslation(): void;
};

/**
 * 投稿の ⋯ メニュー（ネイティブ NoteItem.kt の並び）。
 * isFollowing が null（自分の kind:3 が未取得）ならフォロー項目を出さない。
 * isBookmarked が null（自分の kind:10003 が未取得）ならブックマーク項目を出さない（#531）。
 * 自分の投稿は isPinned が null（自分の kind:10001 が未取得）でなければ「プロフィールに固定」。
 * 他人の投稿は「通報」の前にミュート・ブロックの項目（#963。muteState: none = 「このユーザーをミュート」
 * 「このユーザーをブロック」、muted = 「ミュートを解除」「このユーザーをブロック」、blocked = 「ブロックを解除」）。
 * translationVisible が undefined / null（Translator 非対応・本文が空）なら「翻訳」は出さない。
 * false なら「翻訳」、true なら「翻訳を隠す」（操作系との区切りの後、コピー系の前。#541）。
 * 開発者モード（developerMode）の間は末尾に区切り＋「イベントJSONを表示」。
 */
export function moreMenuEntries(a: {
  clientName: string | null;
  isMine: boolean;
  isFollowing: boolean | null;
  isBookmarked: boolean | null;
  isPinned: boolean | null;
  muteState: UserMuteState;
  note1: string;
  nevent: string;
  developerMode?: boolean;
  translationVisible?: boolean | null;
  on: MoreMenuActions;
}): MenuEntry[] {
  const entries: MenuEntry[] = [];
  if (a.clientName) {
    entries.push({ type: "header", label: t("note_posted_via_fmt", a.clientName) }, { type: "separator" });
  }
  if (!a.isMine && a.isFollowing !== null) {
    entries.push(
      a.isFollowing
        ? { type: "item", label: t("note_unfollow"), onSelect: a.on.unfollow }
        : { type: "item", label: t("note_follow"), onSelect: a.on.follow },
    );
  }
  if (a.isBookmarked !== null) {
    entries.push(
      a.isBookmarked
        ? { type: "item", label: t("note_unbookmark"), onSelect: a.on.unbookmark }
        : { type: "item", label: t("note_bookmark"), onSelect: a.on.bookmark },
    );
  }
  if (a.isMine) {
    if (a.isPinned !== null) {
      entries.push(
        a.isPinned
          ? { type: "item", label: t("note_unpin_profile"), onSelect: a.on.unpin }
          : { type: "item", label: t("note_pin_profile"), onSelect: a.on.pin },
      );
    }
  } else {
    entries.push(...muteMenuEntries(a.muteState, a.on));
  }
  entries.push(
    a.isMine
      ? { type: "item", label: t("note_request_delete"), onSelect: a.on.requestDelete, tone: "danger" }
      : { type: "item", label: t("note_report"), onSelect: a.on.report, tone: "danger" },
  );
  entries.push({ type: "separator" });
  if (a.translationVisible !== undefined && a.translationVisible !== null) {
    entries.push(
      a.translationVisible
        ? { type: "item", label: t("note_hide_translation"), onSelect: a.on.hideTranslation }
        : { type: "item", label: t("note_translate"), onSelect: a.on.translate },
    );
  }
  entries.push(
    { type: "item", label: t("note_copy_text"), onSelect: a.on.copyText },
    { type: "item", label: t("note_copy_link"), onSelect: a.on.copyLink },
    { type: "item", label: t("note_copy_id"), onSelect: a.on.copyId },
    { type: "item", label: t("note_copy_fmt", a.note1.slice(0, 12)), onSelect: a.on.copyNote1 },
    { type: "item", label: t("note_copy_fmt", a.nevent.slice(0, 12)), onSelect: a.on.copyNevent },
  );
  if (a.developerMode) {
    entries.push(
      { type: "separator" },
      { type: "item", label: t("note_view_json"), onSelect: a.on.viewJson },
    );
  }
  return entries;
}

/** [#963] ミュート・ブロックの項目（投稿・チャットの発言の ⋯ で共通。ネイティブ NoteItem.kt） */
export function muteMenuEntries(
  state: UserMuteState,
  on: Pick<MoreMenuActions, "mute" | "unmute" | "block" | "unblock">,
): MenuEntry[] {
  switch (state) {
    case "none":
      return [
        { type: "item", label: t("note_mute_user"), onSelect: on.mute },
        { type: "item", label: t("note_block_user"), onSelect: on.block },
      ];
    case "muted":
      return [
        { type: "item", label: t("note_unmute_user"), onSelect: on.unmute },
        { type: "item", label: t("note_block_user"), onSelect: on.block },
      ];
    case "blocked":
      return [{ type: "item", label: t("note_unblock_user"), onSelect: on.unblock }];
  }
}
