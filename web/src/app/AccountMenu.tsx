import { type ReactNode, useState } from "react";
import { useNavigate } from "react-router";
import { useOpenSettingsSection } from "../features/settings/useOpenSettingsSection";
import { useT } from "../i18n";
import { useRelayConnections } from "../nostr/pool";
import { useSession } from "../signer/session";
import { badgeText } from "../ui/badge";
import {
  BlockIcon,
  BookmarkBorderIcon,
  CloudIcon,
  Icon,
  LogoutIcon,
  MailOutlineIcon,
  MoodIcon,
  SettingsIcon,
  StarBorderIcon,
} from "../ui/icons";
import { MenuButton, type MenuEntry } from "../ui/MenuButton";
import styles from "./AccountMenu.module.css";
import {
  ACCOUNT_MENU_ITEMS,
  type AccountMenuItem,
  accountMenuLabel,
  accountMenuSection,
  accountMenuTarget,
  badgeCount,
  endsGroup,
} from "./accountMenuModel";
import { useAfterMenuClosed } from "./history";
import { LogoutConfirmDialog } from "./LogoutButton";

function ItemIcon({ item }: { item: AccountMenuItem }) {
  switch (item) {
    case "profile":
      return <Icon name="person" />;
    case "dm":
      return <MailOutlineIcon />;
    case "favs":
      return <StarBorderIcon />;
    case "bookmarks":
      return <BookmarkBorderIcon />;
    case "mute":
      return <BlockIcon />;
    case "emoji":
      return <MoodIcon />;
    case "hashtags":
      return <Icon name="tag" />;
    case "relays":
      return <CloudIcon />;
    case "settings":
      return <SettingsIcon />;
    case "logout":
      return <LogoutIcon />;
  }
}

/** リレーの接続状態「3 / 4 接続中」（レールの ● n/m と同じ数え方）。メニューを開いている間だけ購読する */
function RelayStatus() {
  const t = useT();
  const { connected, total } = useRelayConnections();
  return <span className={styles.relays}>{t("account_menu_relays_fmt", connected, total)}</span>;
}

/**
 * [#797][#807] 自分のアイコン（下部ナビの末尾・レールの最下段）とそのメニュー（ネイティブ AccountMenu）。
 * 見た目はカラムの ⋯ メニューと同じ（モノクロのアイコン + 文言）。ログアウトだけ Warn 色で、設定と同じ確認ダイアログを挟む。
 * 未ログイン・自分の pubkey の読み込み前はメニューを置かず、押すと今までどおり設定へ（onFallback）。
 * children = アイコンの描画（未読 DM の件数のバッジも呼び出し側が重ねる）。
 * placement: 下部ナビは "below"（入らないので上へ出る。右端に揃える）、レールは "beside"（レールの右へ出す）。
 */
export function AccountMenu({
  triggerClassName,
  placement,
  current,
  dmUnread,
  onFallback,
  children,
}: {
  triggerClassName: string;
  placement: "below" | "beside";
  current: boolean;
  dmUnread: number;
  onFallback(): void;
  children: ReactNode;
}) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const navigate = useNavigate();
  const openSection = useOpenSettingsSection();
  const afterMenuClosed = useAfterMenuClosed();
  const [confirmLogout, setConfirmLogout] = useState(false);

  const label = t("web_nav_account_menu");
  const triggerLabel = dmUnread > 0 ? t("web_nav_unread_label", label, dmUnread) : label;

  if (!me) {
    return (
      <button
        type="button"
        className={triggerClassName}
        aria-label={triggerLabel}
        title={label}
        aria-current={current ? "page" : undefined}
        onClick={onFallback}
      >
        {children}
      </button>
    );
  }
  const pubkey = me;

  function select(item: AccountMenuItem) {
    if (item === "logout") {
      setConfirmLogout(true);
      return;
    }
    // 画面を移るのは、メニューが積んだ履歴の印が消えてから（history.ts useAfterMenuClosed）
    afterMenuClosed(() => {
      const section = accountMenuSection(item);
      if (section) {
        openSection(section);
        return;
      }
      const target = accountMenuTarget(item, pubkey);
      if (target) void navigate(target.to, { replace: target.replace });
    });
  }

  const entries: MenuEntry[] = ACCOUNT_MENU_ITEMS.flatMap((item): MenuEntry[] => {
    const name = accountMenuLabel(item);
    const count = badgeCount(item, dmUnread);
    const entry: MenuEntry = {
      type: "item",
      label: name,
      icon: <ItemIcon item={item} />,
      onSelect: () => select(item),
      tone: item === "logout" ? "danger" : undefined,
      trailing:
        count > 0 ? (
          <span className={styles.count} aria-hidden="true">
            {badgeText(count)}
          </span>
        ) : item === "relays" ? (
          <RelayStatus />
        ) : undefined,
      ariaLabel: count > 0 ? t("web_nav_unread_label", name, count) : undefined,
    };
    return endsGroup(item) ? [entry, { type: "separator" }] : [entry];
  });

  return (
    <>
      <MenuButton
        label={label}
        triggerLabel={triggerLabel}
        title={label}
        current={current}
        placement={placement}
        triggerClassName={triggerClassName}
        entries={entries}
      >
        {children}
      </MenuButton>
      {confirmLogout && <LogoutConfirmDialog onDismiss={() => setConfirmLogout(false)} />}
    </>
  );
}
