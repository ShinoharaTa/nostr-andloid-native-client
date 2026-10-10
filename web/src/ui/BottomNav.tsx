import { AccountMenu } from "../app/AccountMenu";
import { NAV_ORDER, type NavKey, navLabel } from "../app/navState";
import { useT } from "../i18n";
import { AccountAvatar } from "./AccountAvatar";
import styles from "./BottomNav.module.css";
import { badgeText } from "./badge";
import { ChatIcon, HomeIcon, NotificationsIcon, SearchIcon } from "./icons";

function NavIcon({ navKey }: { navKey: NavKey }) {
  switch (navKey) {
    case "home":
      return <HomeIcon className={styles.icon} />;
    case "search":
      return <SearchIcon className={styles.icon} />;
    case "channels":
      return <ChatIcon className={styles.icon} />;
    case "notifications":
      return <NotificationsIcon className={styles.icon} />;
    case "account":
      return <AccountAvatar size={24} />;
  }
}

/**
 * Compact の下部ナビ（ネイティブ BottomBar）。固定 5 枠・ラベル無し。表示だけで、押したら onSelect。
 * badges = アイコンの右上に重ねる未読数（0・未指定は出さない）。
 * [#797] 末尾の自分のアイコンは押すとメニュー（AccountMenu。未ログインなら onSelect("account") で設定へ）。
 */
export function BottomNav({
  selected,
  badges,
  onSelect,
}: {
  selected: Record<NavKey, boolean>;
  badges?: Partial<Record<NavKey, number>>;
  onSelect(key: NavKey): void;
}) {
  const t = useT();
  return (
    <nav className={styles.nav} aria-label={t("web_nav_main")}>
      {NAV_ORDER.map((key) => {
        const badge = badges?.[key] ?? 0;
        const indicator = (
          <span className={styles.indicator}>
            <NavIcon navKey={key} />
            {badge > 0 && (
              <span className={styles.badge} aria-hidden="true">
                {badgeText(badge)}
              </span>
            )}
          </span>
        );
        if (key === "account") {
          return (
            <AccountMenu
              key={key}
              triggerClassName={styles.item}
              placement="below"
              current={selected[key]}
              dmUnread={badge}
              onFallback={() => onSelect(key)}
            >
              {indicator}
            </AccountMenu>
          );
        }
        return (
          <button
            key={key}
            type="button"
            className={styles.item}
            aria-label={badge > 0 ? t("web_nav_unread_label", navLabel(key), badge) : navLabel(key)}
            title={navLabel(key)}
            aria-current={selected[key] ? "page" : undefined}
            onClick={() => onSelect(key)}
          >
            {indicator}
          </button>
        );
      })}
    </nav>
  );
}
