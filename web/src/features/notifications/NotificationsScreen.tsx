import { useT } from "../../i18n";
import { type ColumnSpec, defaultFilter } from "../../lib/columns";
import { Icon } from "../../ui/icons";
import { ScreenHeader } from "../../ui/ScreenHeader";
import { SingleColumnPane } from "../../ui/SingleColumnPane";
import { NOTIFICATIONS_SCREEN_SCROLL_ID } from "../deck/columnScroll";
import { useColumnFeed } from "../deck/useColumnFeed";
import { NotificationList } from "./NotificationList";
import styles from "./NotificationsScreen.module.css";

/**
 * 通知画面の購読（id はネイティブの購読 id と同じ。デッキには入れない）。
 * kinds は通知カラムの既定の種別だが、表示には使わない（viewFor が全種別を読む）。
 */
export const NOTIFICATIONS_SCREEN_SPEC: ColumnSpec = {
  id: "notifications",
  // 見出しは画面側で t() を引く（ここは購読の指定だけ。モジュール定数で訳すと言語の切替に追従しない）
  title: "",
  subtitle: "",
  kind: "NOTIFICATIONS",
  renderer: "FEED",
  filter: { ...defaultFilter(), kinds: [1, 7, 9735, 6] },
  pinned: false,
  order: 0,
};

/** 通知画面（/notifications。ネイティブの NotificationsScreen）。通知カラムと同じ一覧を 1 カラムで */
export function NotificationsScreen() {
  const t = useT();
  const { events, loading, refresh } = useColumnFeed(NOTIFICATIONS_SCREEN_SPEC);
  return (
    <SingleColumnPane>
      <ScreenHeader
        title={t("nav_notifications")}
        subtitle={t("notif_subtitle")}
        icon={<Icon name="notifications" size="lg" />}
      />
      <hr className={styles.divider} />
      <div className={styles.body}>
        <NotificationList
          events={events}
          loading={loading}
          columnId={NOTIFICATIONS_SCREEN_SPEC.id}
          onRefresh={refresh}
          scrollId={NOTIFICATIONS_SCREEN_SCROLL_ID}
        />
      </div>
    </SingleColumnPane>
  );
}
