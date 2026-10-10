import { type EventPointer, isAddressPointer } from "applesauce-core/helpers/pointers";
import type { NostrEvent } from "nostr-tools/pure";
import { useMemo } from "react";
import { ChannelRoomDetail } from "../../features/chat/ChannelRoomDetail";
import { useEventLinkRoute } from "../../features/chat/useEventLinkRoute";
import { openCompose } from "../../features/compose/composeStore";
import { ThreadScreen } from "../../features/thread/ThreadScreen";
import { useT } from "../../i18n";
import { ScreenHeader } from "../../ui/ScreenHeader";
import { ComingSoon } from "../screens/ComingSoon";
import { parseEventRef } from "./refs";

/**
 * スレッド / 記事の詳細（/e/:ref。#534 で naddr1… も受ける）。下端の返信ボックスは返信の投稿シート（#458）を開く。
 * ヘッダは ThreadScreen が持つ（起点が kind:30023 なら「記事」、それ以外は「スレッド」。ProfileOverlay と同じ構成）。
 * [#798] note / nevent がパブリックチャットのチャンネル（kind:40）・発言（kind:42）なら、同じ詳細にルームを出す
 * （ネイティブ #791。メッセージ画面へは移らない）。
 */
export function ThreadOverlay({ refParam, onBack }: { refParam: string; onBack: () => void }) {
  const t = useT();
  const ref = useMemo(() => parseEventRef(refParam), [refParam]);
  if (!ref) {
    return (
      <>
        <ScreenHeader title={t("thread_title")} subtitle="NIP-10" onBack={onBack} />
        <ComingSoon>{t("web_route_invalid_url")}</ComingSoon>
      </>
    );
  }
  const onReply = (target: NostrEvent) => openCompose({ mode: "reply", target });
  if (isAddressPointer(ref)) {
    return <ThreadScreen key={refParam} pointer={ref} onBack={onBack} onReply={onReply} />;
  }
  return <EventLinkDetail key={refParam} pointer={ref} onBack={onBack} onReply={onReply} />;
}

/** note / nevent の開き先を決めてから描く（決まるまではスレッドのヘッダ + 読み込み中） */
function EventLinkDetail({
  pointer,
  onBack,
  onReply,
}: {
  pointer: EventPointer;
  onBack: () => void;
  onReply: (target: NostrEvent) => void;
}) {
  const t = useT();
  const route = useEventLinkRoute(pointer);
  if (route === null) {
    return (
      <>
        <ScreenHeader title={t("thread_title")} subtitle="NIP-10" onBack={onBack} />
        <ComingSoon>{t("loading")}</ComingSoon>
      </>
    );
  }
  if (route.type === "room") {
    return <ChannelRoomDetail channelId={route.channelId} messageId={route.messageId} onBack={onBack} />;
  }
  return <ThreadScreen pointer={pointer} onBack={onBack} onReply={onReply} />;
}
