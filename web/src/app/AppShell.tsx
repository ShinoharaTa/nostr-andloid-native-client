import { useEffect, useMemo, useRef, useState } from "react";
import { Outlet, useLocation, useMatches, useNavigate, useParams } from "react-router";
import { useShallow } from "zustand/react/shallow";
import { ComposeHost } from "../features/compose/ComposeHost";
import { AddColumnDialog } from "../features/deck/AddColumnDialog";
import { EditColumnDialog } from "../features/deck/EditColumnDialog";
import { useDmUnreadTotal } from "../features/dm/dmStore";
import { MessagesScreen } from "../features/dm/MessagesScreen";
import { HashtagManager } from "../features/hashtags/HashtagManager";
import { closeHashtagManager, useHashtagManager } from "../features/hashtags/hashtagManagerStore";
import { KeyboardShortcuts } from "../features/keyboard/KeyboardShortcuts";
import { NotificationsScreen } from "../features/notifications/NotificationsScreen";
import { SearchScreen } from "../features/search/SearchScreen";
import { SettingsScreen } from "../features/settings/SettingsScreen";
import { useT } from "../i18n";
import { notificationsColumnId, pinnedColumns, useDeck } from "../store/deck";
import { BottomNav } from "../ui/BottomNav";
import { ConnectionPill } from "../ui/ConnectionPill";
import { DetailOverlay } from "../ui/DetailOverlay";
import { NavRail } from "../ui/NavRail";
import { useLayoutMode } from "../ui/useLayoutMode";
import { useVisualViewportHeight } from "../ui/useVisualViewportHeight";
import styles from "./AppShell.module.css";
import { DeckScreen } from "./deck/DeckScreen";
import { useCloseOverlay, useTransientHistory } from "./history";
import {
  bottomSelection,
  type Dest,
  isPinnedActive,
  isRailHomeActive,
  type OverlayKind,
  routeHandleOf,
} from "./navState";
import { ProfileOverlay } from "./overlays/ProfileOverlay";
import { ThreadOverlay } from "./overlays/ThreadOverlay";
import { NotFoundScreen } from "./screens/NotFoundScreen";
import { UpdateToast } from "./UpdateToast";
import { useNavActions } from "./useNavActions";

function DestScreen({ dest }: { dest: Dest }) {
  switch (dest) {
    case "home":
      return <DeckScreen />;
    case "search":
      return <SearchScreen />;
    case "messages":
      return <MessagesScreen kind="dm" />;
    case "channels":
      return <MessagesScreen kind="chat" />;
    case "notifications":
      return <NotificationsScreen />;
    case "settings":
      return <SettingsScreen />;
    case "notFound":
      return <NotFoundScreen />;
  }
}

/**
 * ログイン後の骨格（ネイティブ AppScaffold）。Compact = 内容 + 下部ナビ、Rail = 内容は Compact と同じ
 * 1 カラムのままナビだけ左レール、Expanded = レール + 横並びのデッキ。[#661] 3 段階とも useLayoutMode
 * が 1 か所で判定する（内容領域は各画面が useLayoutMode を見る）。
 * 宛先（URL のパス）の画面を内容領域に描き、詳細（/e /p）はその上に重ねる（背後は最後の宛先を描いたまま）。
 * 子の並び順は固定（条件付きの要素も同じ位置）= モードを切り替えても内容を作り直さない。
 */
export function AppShell() {
  const mode = useLayoutMode();
  const handle = routeHandleOf(useMatches());
  const params = useParams();
  const { key: locationKey } = useLocation();
  const navigate = useNavigate();
  // ソフトキーボードで縮んだ高さに骨格ごと収める（#594。iOS は 100dvh がキーボードに縮まないため）
  const shell = useRef<HTMLDivElement>(null);
  useVisualViewportHeight(shell, "--shell-vvh");

  // 詳細の背後に描く宛先。直リンク・リロードで詳細から始まったらデッキ
  const [baseDest, setBaseDest] = useState<Dest>(handle && "dest" in handle ? handle.dest : "home");
  // [#807] 詳細の履歴エントリごとに、最初に開いたときの背後の宛先を覚える。詳細から積んだ設定の項目
  // （自分のアイコンのメニュー・プロフィールの「編集」）から戻ったとき、背後を設定のままにしない
  const overlayBases = useRef(new Map<string, Dest>());
  if (handle && "dest" in handle && handle.dest !== baseDest) setBaseDest(handle.dest);
  else if (handle && "overlay" in handle) {
    const remembered = overlayBases.current.get(locationKey);
    if (remembered === undefined) overlayBases.current.set(locationKey, baseDest);
    else if (remembered !== baseDest) setBaseDest(remembered);
  }
  const dest: Dest = handle && "dest" in handle ? handle.dest : baseDest;
  const overlay: { kind: OverlayKind; ref: string } | null =
    handle && "overlay" in handle ? { kind: handle.overlay, ref: params.ref ?? "" } : null;
  const overlayKind = overlay?.kind ?? null;

  const t = useT();
  useTransientHistory();
  const closeOverlay = useCloseOverlay();
  const { open, openColumn } = useNavActions();

  const jumpTarget = useDeck((s) => s.jumpTarget);
  const visibleColumnId = useDeck((s) => s.visibleColumnId);
  const showAddColumn = useDeck((s) => s.showAddColumn);
  const editingColumnId = useDeck((s) => s.editingColumnId);
  const hashtagManagerOpen = useHashtagManager((s) => s.open);
  const notifColumnId = useDeck(notificationsColumnId);
  const pinned = useDeck(useShallow(pinnedColumns));
  const dmUnread = useDmUnreadTotal();

  // 宛先の外で jump したら必ずデッキへ出す（ネイティブ #49。検索画面からカラム追加した場合など）
  useEffect(() => {
    if (jumpTarget !== null && (dest !== "home" || overlayKind !== null))
      void navigate("/", { replace: true });
  }, [jumpTarget, dest, overlayKind, navigate]);

  const selected = bottomSelection(dest, visibleColumnId, notifColumnId);
  // [#797] 未読 DM の件数は自分のアイコン（とそのメニューの「DM」）に出す。DM はそのメニューからだけ開く
  const badges = { account: dmUnread };
  const railPinned = useMemo(
    () =>
      pinned.map((c) => ({
        id: c.id,
        title: c.title,
        kind: c.kind,
        active: isPinnedActive(dest, visibleColumnId, c.id),
      })),
    [pinned, dest, visibleColumnId],
  );
  const pinnedIds = useMemo(() => pinned.map((c) => c.id), [pinned]);

  return (
    <div className={styles.shell} data-layout={mode} ref={shell}>
      {mode !== "compact" && (
        <NavRail
          selected={selected}
          badges={badges}
          homeActive={isRailHomeActive(dest, visibleColumnId, pinnedIds)}
          pinned={railPinned}
          showNotifications={notifColumnId === null}
          onSelect={open}
          onOpenColumn={openColumn}
          onAddColumn={() => useDeck.getState().setShowAddColumn(true)}
        />
      )}
      <main className={styles.content} id="main">
        <div className={styles.base} inert={overlay !== null}>
          <DestScreen dest={dest} />
        </div>
        {overlay && (
          <DetailOverlay
            kind={overlay.kind}
            label={overlay.kind === "thread" ? t("thread_title") : t("profile_section")}
            onClose={closeOverlay}
          >
            {overlay.kind === "thread" ? (
              <ThreadOverlay refParam={overlay.ref} onBack={closeOverlay} />
            ) : (
              <ProfileOverlay refParam={overlay.ref} onBack={closeOverlay} />
            )}
          </DetailOverlay>
        )}
        <ComposeHost showFab={dest === "home" && overlay === null} />
        <KeyboardShortcuts enabled={dest === "home"} hasDetail={overlay !== null} />
        <ConnectionPill />
        <UpdateToast />
      </main>
      {mode === "compact" && <BottomNav selected={selected} badges={badges} onSelect={open} />}
      <Outlet />
      {showAddColumn && <AddColumnDialog />}
      {editingColumnId !== null && <EditColumnDialog />}
      {hashtagManagerOpen && <HashtagManager onDismiss={closeHashtagManager} />}
    </div>
  );
}
