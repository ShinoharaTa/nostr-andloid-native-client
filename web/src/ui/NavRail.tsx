import type { ReactNode } from "react";
import { AccountMenu } from "../app/AccountMenu";
import { type NavKey, navLabel } from "../app/navState";
import { useT } from "../i18n";
import { columnDisplayTitle } from "../i18n/columnTitles";
import type { ColumnKind } from "../lib/columns";
import { AccountAvatar } from "./AccountAvatar";
import { badgeText } from "./badge";
import {
  AddIcon,
  ChatIcon,
  ColumnKindIcon,
  EditIcon,
  HomeIcon,
  MoodIcon,
  NotificationsIcon,
  SearchIcon,
} from "./icons";
import styles from "./NavRail.module.css";
import { RelayIndicator } from "./RelayIndicator";

export type RailPinned = { id: string; title: string; kind: ColumnKind; active: boolean };

/**
 * Expanded の左レール（ネイティブ DeckRail）。表示だけで、押したら各コールバック。
 * ブランド → ホーム ｜ ピン留めの目次（ここだけ縦スクロール）→ カラム追加 ｜ 検索 → パブリックチャット →
 * （通知カラムが無いときだけ）通知 → 新規投稿 ｜ 接続表示 → 自分。
 * [#1001] 新規投稿は接続表示の上の区切りの上（ネイティブ DeckRail #1000）。右下の投稿ボタン（FAB）の代わり。
 * [#1020] その上に自分のステータスを更新するボタン。onCompose / onStatus が無ければ出さない（1 カラムの表示では、
 * AppShell が右下に出す）。固定の項目が収まらない高さ（スマホの横向きなど）では、レール全体が縦スクロールする。
 * badges = 宛先のアイコンの右上に重ねる未読数（0・未指定は出さない）。
 * [#797] 自分のアイコンは押すとメニュー（AccountMenu。レールの右へ出す。未ログインなら onSelect("account") で設定へ）。
 */
export function NavRail({
  selected,
  badges,
  homeActive,
  pinned,
  showNotifications,
  onSelect,
  onOpenColumn,
  onAddColumn,
  onCompose,
  onStatus,
}: {
  selected: Record<NavKey, boolean>;
  badges?: Partial<Record<NavKey, number>>;
  homeActive: boolean;
  pinned: RailPinned[];
  showNotifications: boolean;
  onSelect(key: NavKey): void;
  onOpenColumn(id: string): void;
  onAddColumn(): void;
  onCompose?: () => void;
  onStatus?: () => void;
}) {
  const t = useT();
  const accountBadge = badges?.account ?? 0;
  const dest = (key: NavKey, active: boolean, icon: ReactNode) => {
    const badge = badges?.[key] ?? 0;
    return (
      <button
        type="button"
        className={styles.slot}
        aria-label={badge > 0 ? t("web_nav_unread_label", navLabel(key), badge) : navLabel(key)}
        title={navLabel(key)}
        aria-current={active ? "page" : undefined}
        onClick={() => onSelect(key)}
      >
        {icon}
        {badge > 0 && (
          <span className={styles.badge} aria-hidden="true">
            {badgeText(badge)}
          </span>
        )}
      </button>
    );
  };

  return (
    <nav className={styles.rail} aria-label={t("web_nav_main")}>
      <div className={`${styles.block} ${styles.top}`}>
        <span className={styles.brandSlot}>
          <img
            src={`${import.meta.env.BASE_URL}icons/icon-192.png`}
            alt="Nostrism"
            className={styles.brand}
          />
        </span>
        {dest("home", homeActive, <HomeIcon className={styles.icon} />)}
      </div>
      <div className={styles.divider} />
      <div className={styles.index}>
        {pinned.map((c) => (
          // 目次は選択時も色を変えず、下地だけ変える（ネイティブ）
          <button
            key={c.id}
            type="button"
            className={styles.slot}
            aria-label={columnDisplayTitle(c.title)}
            title={columnDisplayTitle(c.title)}
            aria-current={c.active ? "true" : undefined}
            onClick={() => onOpenColumn(c.id)}
          >
            <ColumnKindIcon kind={c.kind} className={styles.icon} />
          </button>
        ))}
      </div>
      {/* カラム追加は目次の外（スクロールしない。常に AccentWeak の下地） */}
      <button
        type="button"
        className={`${styles.slot} ${styles.add}`}
        aria-label={t("nav_add_column")}
        title={t("nav_add_column")}
        onClick={onAddColumn}
      >
        <AddIcon className={styles.icon} />
      </button>
      <div className={styles.divider} />
      <div className={styles.block}>
        {dest("search", selected.search, <SearchIcon className={styles.icon} />)}
        {dest("channels", selected.channels, <ChatIcon className={styles.icon} />)}
        {showNotifications &&
          dest("notifications", selected.notifications, <NotificationsIcon className={styles.icon} />)}
        {/* [#1020] 自分のステータスを更新する（投稿ボタンの上） */}
        {onStatus && (
          <button
            type="button"
            className={styles.slot}
            aria-label={t("status_edit_title")}
            title={t("status_edit_title")}
            onClick={onStatus}
          >
            <MoodIcon className={styles.icon} />
          </button>
        )}
        {/* [#1001] 新規投稿（投稿ボタンと同じ塗り。キーボードの n は今まで通り） */}
        {onCompose && (
          <button
            type="button"
            className={`${styles.slot} ${styles.compose}`}
            aria-label={t("fab_post")}
            title={t("fab_post")}
            onClick={onCompose}
          >
            <EditIcon className={styles.icon} />
          </button>
        )}
      </div>
      <div className={styles.divider} />
      <div className={`${styles.block} ${styles.bottom}`}>
        {/* [#597] 接続表示のタップ領域を他のレール項目（.slot 48dp）と揃える */}
        <span className={styles.relaySlot}>
          <RelayIndicator orientation="vertical" />
        </span>
        <AccountMenu
          triggerClassName={styles.slot}
          placement="beside"
          current={selected.account}
          dmUnread={accountBadge}
          onFallback={() => onSelect("account")}
        >
          <AccountAvatar size={40} />
          {accountBadge > 0 && (
            <span className={styles.badge} aria-hidden="true">
              {badgeText(accountBadge)}
            </span>
          )}
        </AccountMenu>
      </div>
    </nav>
  );
}
