import { useMemo } from "react";
import { useLocation, useNavigate } from "react-router";
import { columnScrollOf } from "../features/deck/columnScroll";
import { notificationsColumnId, useDeck } from "../store/deck";
import type { LayoutMode } from "../ui/useLayoutMode";
import { homeTapAction } from "./homeTap";
import { type Dest, NAV_PATH, type NavKey } from "./navState";

export type NavActions = {
  /** 下部ナビ・レールの宛先ボタン */
  open(key: NavKey): void;
  /** レールの目次（そのカラムへ jump） */
  openColumn(id: string): void;
};

/** 押したときの画面（AppShell が決めたもの） */
export type NavContext = {
  mode: LayoutMode;
  dest: Dest;
  /** スレッド・プロフィールを重ねているか */
  hasOverlay: boolean;
};

/**
 * 宛先の切替。すべて replace（ネイティブの「宛先の切替は戻る対象ではない」）。
 * ストアは購読せず、押したときに getState() で読む。
 */
export function useNavActions({ mode, dest, hasOverlay }: NavContext): NavActions {
  const navigate = useNavigate();
  const { pathname } = useLocation();

  return useMemo(() => {
    // 既に / なら navigate しない（一時カラムの履歴の印を消さない）
    const goDeck = () => {
      if (pathname !== "/") void navigate("/", { replace: true });
    };
    return {
      open(key) {
        const s = useDeck.getState();
        if (key === "home") {
          // [#919] 1 カラム表示でカラムを途中まで読んでいたら、そのカラムを先頭へ戻すだけ（ネイティブ tapHomeCompact）
          const scroll = s.visibleColumnId === null ? null : columnScrollOf(s.visibleColumnId);
          if (
            mode !== "expanded" &&
            homeTapAction({
              dest,
              hasOverlay,
              visibleColumnId: s.visibleColumnId,
              columnIds: s.columns.map((c) => c.id),
              notifColumnId: notificationsColumnId(s),
              atTop: scroll?.atTop() ?? true,
            }) === "scrollTop"
          ) {
            scroll?.scrollToTop();
            return;
          }
          // ネイティブ openHome: フォロー中、無ければ先頭のカラムへ
          goDeck();
          const target = s.columns.find((c) => c.kind === "FOLLOWING")?.id ?? s.columns[0]?.id;
          if (target) s.jumpTo(target);
        } else if (key === "notifications") {
          // ネイティブ openNotifications: 通知カラムがあればそこへ、無ければ通知画面
          const id = notificationsColumnId(s);
          if (id) {
            goDeck();
            s.jumpTo(id);
          } else {
            void navigate(NAV_PATH.notifications, { replace: true });
          }
        } else {
          // パブリックチャット（ネイティブ openPublicChat: 常にチャンネル一覧）・検索・
          // 自分のアイコン（未ログインのときだけここへ来る。設定へ）
          void navigate(NAV_PATH[key], { replace: true });
        }
      },
      openColumn(id) {
        goDeck();
        useDeck.getState().jumpTo(id);
      },
    };
  }, [navigate, pathname, mode, dest, hasOverlay]);
}
