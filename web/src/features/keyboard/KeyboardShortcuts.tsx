import type { NostrEvent } from "nostr-tools/pure";
import { useEffect } from "react";
import { type NavigateFunction, useNavigate } from "react-router";
import { repostedEventNow } from "../../nostr/loaders";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { reactWithDefault } from "../actions/reactions";
import { roomHrefOf } from "../chat/chatMessage";
import { openCompose, useCompose } from "../compose/composeStore";
import { toggleBookmark, useOwnLists } from "../lists/ownLists";
import {
  focusColumn,
  listOf,
  moveSelection,
  resolveFocusColumn,
  selectEdge,
  setHelpOpen,
  useKeyboard,
} from "./kbStore";
import { type KeyAction, type KeyContext, keyToAction } from "./keyboard";
import { HELP_SELECTOR, ShortcutsHelp } from "./ShortcutsHelp";

/** 検索欄の id（SearchScreen の input に付けてある） */
const SEARCH_INPUT_ID = "search-query";

/** / で検索画面へ移ったあと、検索欄が描かれるのを待つフレーム数 */
const FOCUS_SEARCH_FRAMES = 30;

/** 投稿シート・ダイアログ（一覧を除く）・ライトボックス・メニューのどれかが開いているか */
function modalOpen(): boolean {
  return (
    useCompose.getState().request !== null ||
    document.querySelector(`dialog[open]:not(${HELP_SELECTOR})`) !== null ||
    document.querySelector("[role='menu']") !== null
  );
}

/** 行の投稿（リポストは元の投稿。手元に無ければ null） */
function postOf(event: NostrEvent | null): NostrEvent | null {
  if (!event) return null;
  if (event.kind !== 6 && event.kind !== 16) return event;
  return repostedEventNow(event) ?? null;
}

/**
 * 行の行き先へ（行の本体を押したのと同じ。スレッド・通知の対象・ふぁぼの対象）。
 * 仮想リストの外（マウスで遠くへスクロールした後）で描かれていなければ、見える位置へ寄せるだけ
 */
function openRow(columnId: string, index: number): void {
  const row = document.getElementById(`deck-col-${columnId}`)?.querySelector(`[data-kb-index="${index}"]`);
  const body = row?.firstElementChild;
  if (body instanceof HTMLElement) body.click();
  else listOf(columnId)?.scrollTo(index);
}

/** 描かれたら検索欄にフォーカスする */
function focusSearchInput(frames = FOCUS_SEARCH_FRAMES): void {
  const input = document.getElementById(SEARCH_INPUT_ID);
  if (input) {
    input.focus();
    return;
  }
  if (frames > 0) requestAnimationFrame(() => focusSearchInput(frames - 1));
}

/** 発行の失敗は画面に出さない（ネイティブと同じ） */
function warn(e: unknown) {
  console.warn("[keyboard] Failed to react", e);
}

/** 発行の失敗は画面に出さない（ネイティブ KbAction.BOOKMARK と同じ。トーストも出さない） */
function warnBookmark(e: unknown) {
  console.warn("[keyboard] Failed to bookmark", e);
}

function run(action: KeyAction, navigate: NavigateFunction): void {
  switch (action.type) {
    case "compose":
      openCompose({ mode: "new" });
      return;
    case "search":
      void navigate("/search", { replace: true });
      focusSearchInput();
      return;
    case "toggleHelp":
      setHelpOpen(!useKeyboard.getState().helpOpen);
      return;
    case "closeHelp":
      setHelpOpen(false);
      return;
    case "closeDetail":
      // DetailOverlay が自分の Esc で閉じる
      return;
    case "deselect":
      useKeyboard.setState({ active: false });
      return;
  }

  const columnId = resolveFocusColumn();
  if (columnId === null) return;
  switch (action.type) {
    case "move":
      moveSelection(columnId, action.delta);
      return;
    case "edge":
      selectEdge(columnId, action.toBottom);
      return;
    case "column": {
      const position = useDeck.getState().columns.findIndex((c) => c.id === columnId);
      focusColumn(Math.max(position, 0) + action.delta);
      return;
    }
  }

  // 選択中の行への操作（ネイティブ requestAction。ハイライトを出してから実行する）
  useKeyboard.setState({ active: true });
  const index = useKeyboard.getState().selected[columnId] ?? -1;
  const count = listOf(columnId)?.count ?? 0;
  if (index < 0 || index >= count) return;
  if (action.type === "open") {
    openRow(columnId, index);
    return;
  }
  // r / t / f は投稿の行だけ
  const post = postOf(listOf(columnId)?.postAt(index) ?? null);
  if (!post) return;
  // [#796] パブリックチャットの発言はタップと同じくルームを開く
  const room = post.kind === 42 ? roomHrefOf(post) : null;
  if (action.type === "reply" && room !== null) void navigate(room);
  else if (action.type === "reply") openCompose({ mode: "reply", target: post });
  else if (action.type === "quote") openCompose({ mode: "quote", target: post });
  else if (action.type === "bookmark") {
    const me = useSession.getState().pubkey;
    if (!me) return;
    const isBookmarked = useOwnLists.getState().bookmarks?.ids.includes(post.id) ?? false;
    toggleBookmark(me, post.id, isBookmarked ? "unbookmark" : "bookmark").catch(warnBookmark);
  }
  // 付与済みなら kind:5 で取り消す。確認は出さない（ネイティブ reactWithDefault と同じ）
  else reactWithDefault(post).catch(warn);
}

/**
 * デッキのキーボードショートカット（ネイティブ DeckArea の onPreviewKeyEvent）。window の keydown を 1 か所で受ける。
 * enabled = デッキ（ホーム）を表示中。hasDetail = 詳細を重ねている。一覧（?）もここで描く。
 */
export function KeyboardShortcuts({ enabled, hasDetail }: { enabled: boolean; hasDetail: boolean }) {
  const navigate = useNavigate();
  const helpOpen = useKeyboard((s) => s.helpOpen);

  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (e: KeyboardEvent) => {
      const kb = useKeyboard.getState();
      const context: KeyContext = {
        modalOpen: modalOpen(),
        helpOpen: kb.helpOpen,
        hasDetail,
        kbActive: kb.active,
      };
      const action = keyToAction(e, context);
      // 詳細の Esc は DetailOverlay に任せる（止めると閉じない）
      if (action === null || action.type === "closeDetail") return;
      e.preventDefault();
      run(action, navigate);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [enabled, hasDetail, navigate]);

  // デッキを離れたら一覧は閉じる
  useEffect(() => {
    if (!enabled) setHelpOpen(false);
  }, [enabled]);

  return enabled && helpOpen ? <ShortcutsHelp onClose={() => setHelpOpen(false)} /> : null;
}
