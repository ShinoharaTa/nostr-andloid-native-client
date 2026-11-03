import { useMemo } from "react";
import { useDmSeen } from "../dm/dmSeen";
import { conversationsOf, useDm, withoutMutedDms } from "../dm/dmStore";
import { useMuteMatcher } from "../mute/muteList";
import { dmNotices, type NotificationItem } from "./notificationModel";

const NO_ITEMS: NotificationItem[] = [];

/**
 * 未読のある DM 会話の通知行（通知カラム・通知画面・フォロー中カラムに混ぜる。ネイティブ #419）。
 * 相手がミュート対象の会話と、ミュートワードに当たる相手の DM（#971）は除く
 * （revealMuted = そのカラムで「ミュートを表示」中なら除かない）。
 * enabled = false の間は DM を読まない（混ぜないカラムで会話の一覧を計算しない）
 */
export function useDmNotices(enabled: boolean, revealMuted: boolean): NotificationItem[] {
  const messages = useDm((s) => (enabled ? s.messages : null));
  const me = useDm((s) => s.owner);
  const follows = useDm((s) => s.follows);
  const first = useDmSeen((s) => s.first);
  const peers = useDmSeen((s) => s.peers);
  const matcher = useMuteMatcher();
  return useMemo(() => {
    if (messages === null) return NO_ITEMS;
    const rows = revealMuted
      ? Object.values(messages)
      : withoutMutedDms(Object.values(messages), me, matcher);
    const notices = dmNotices(conversationsOf(rows, me, { first, peers }, follows));
    if (revealMuted || matcher.isEmpty) return notices;
    return notices.filter((n) => !matcher.users.has(n.actor));
  }, [messages, me, follows, first, peers, matcher, revealMuted]);
}
