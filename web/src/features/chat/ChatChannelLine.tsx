import type { NostrEvent } from "nostr-tools/pure";
import { useMemo } from "react";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { useEventByPointer } from "../../nostr/loaders";
import styles from "../timeline/ReplyContext.module.css";
import { useChannel } from "./channels";
import { channelIdOf, relayHintOf, roomHrefOf } from "./chatMessage";
import { channelFromCreateEvent } from "./eventLink";

/**
 * [#796] タイムラインに混ぜたパブリックチャットの発言（kind:42）の「#チャンネル名 で発言」の 1 行（ネイティブ NoteItem の
 * chatChannel。返信元の行と同じ体裁で、# が印になるので ◁ は付けない）。押すとそのチャンネルのルームを詳細に重ねる。
 * 名前は手元のチャンネル一覧から、無ければ kind:40 を id（e タグのリレーヒント付き）で取りに行き、届くまでは仮の文言。
 */
export function ChatChannelLine({ message }: { message: NostrEvent }) {
  const t = useT();
  const channelId = channelIdOf(message);
  const href = roomHrefOf(message);
  const listed = useChannel(channelId);
  const pointer = useMemo(() => {
    if (listed || channelId === null) return null;
    const hint = relayHintOf(message.tags, channelId);
    return hint ? { id: channelId, relays: [hint] } : { id: channelId };
  }, [listed, channelId, message.tags]);
  const created = useEventByPointer(pointer);
  if (href === null) return null;
  const name = listed?.name || (created ? channelFromCreateEvent(created)?.name : "") || "";
  const label = name === "" ? t("feed_chat_in_channel_unknown") : t("feed_chat_in_channel_fmt", name);
  return (
    <Link className={styles.line} to={href}>
      <span className={styles.text}>{label}</span>
    </Link>
  );
}
