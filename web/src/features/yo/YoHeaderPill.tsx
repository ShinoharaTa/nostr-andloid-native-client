import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { useMemo } from "react";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { notificationsFrom, unrepliedYoCount } from "../notifications/notificationModel";
import { useYoSending } from "./sendYo";
import styles from "./Yo.module.css";
import { YO_WORD } from "./yoModel";
import { useYoStyle } from "./yoStyle";

/**
 * [#1025] 通知の見出しに出す「Yo! 件数」（ネイティブ YoUi.kt YoHeaderPill。まだ返していない Yo の相手の数）。
 * Yo の表示が「紫」のときだけ出す。0 件なら出さない。events = その通知カラム（通知画面）の購読のイベント
 */
export function YoHeaderPill({ events }: { events: NostrEvent[] }) {
  const style = useYoStyle();
  if (style !== "color") return null;
  return <YoHeaderCount events={events} />;
}

/** 数える方（紫のときだけ描くので、モノクロでは通知の組み立てを 2 回しない） */
function YoHeaderCount({ events }: { events: NostrEvent[] }) {
  const me = useSession((s) => s.pubkey);
  // 通知の一覧（NotificationList）と同じ材料で数える
  const myReactions = use$(() => (me ? eventStore.timeline({ kinds: [7], authors: [me] }) : undefined), [me]);
  const sentAt = useYoSending((s) => s.sentAt);
  const count = useMemo(
    () => unrepliedYoCount(notificationsFrom(events, me, { myReactions: myReactions ?? [] }), sentAt),
    [events, me, myReactions, sentAt],
  );
  if (count === 0) return null;
  return (
    <span className={styles.pill}>
      {YO_WORD} {count}
    </span>
  );
}
