import { useMemo } from "react";
import { useT } from "../../i18n";
import { useEventByPointer } from "../../nostr/loaders";
import { ChannelRoom, RoomHeader } from "./ChannelRoom";
import { useChannel } from "./channels";
import { channelFromCreateEvent } from "./eventLink";

/**
 * [#798] 詳細（/e/:ref）に重ねるパブリックチャット（NIP-28）のルーム（ネイティブ #791 の ChannelRoomDetail）。
 * 本文の kind:40 / 42 へのリンクから開く。メッセージ画面へは移らず、スレッド・プロフィールと同じく戻るで元の画面へ。
 *  - チャンネル名は手元の一覧から引く。一覧に無ければ kind:40 を id で取りに行き、届くまで（届かなければずっと）
 *    「パブリックチャット」で出す。
 *  - messageId（kind:42 へのリンク）があれば、その発言の位置まで送って短く強調する。
 */
export function ChannelRoomDetail({
  channelId,
  messageId,
  onBack,
}: {
  channelId: string;
  messageId: string | null;
  onBack: () => void;
}) {
  const t = useT();
  const listed = useChannel(channelId);
  const pointer = useMemo(() => (listed ? null : { id: channelId }), [listed, channelId]);
  const created = useEventByPointer(pointer);
  const channel = listed ?? (created ? channelFromCreateEvent(created) : null);
  const title = channel?.name || t("chat_room_unnamed");
  const about = channel?.about ?? "";
  return (
    <ChannelRoom
      key={channelId}
      channelId={channelId}
      title={title}
      mode="screen"
      highlightMessageId={messageId}
      header={
        <RoomHeader
          title={title}
          subtitle={about.trim() === "" ? "NIP-28 · kind:42" : about}
          picture={channel?.picture ?? null}
          onBack={onBack}
        />
      }
    />
  );
}
