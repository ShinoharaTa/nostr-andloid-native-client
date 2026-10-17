import { useRef } from "react";
import { t, useT } from "../../i18n";
import { useSession } from "../../signer/session";
import { showToast } from "../../ui/toast";
import { OwnListError } from "../lists/ownLists";
import { useChannel } from "./channels";
import { join, leave, useIsJoined } from "./joinedRooms";

/** ⋯ に出す 1 項目（joined = 今参加中か。文言と押したときの処理） */
export type ChannelJoinAction = { joined: boolean; label: string; onSelect(): void };

/** 参加・退出の失敗の文言（最新版を取れなければ自分のリストの共通のトースト。#478） */
function joinFailureMessage(e: unknown): string {
  if (e instanceof OwnListError && e.reason === "unreachable") return t("web_own_list_no_base");
  return t("channel_join_failed");
}

/**
 * [#856] ルームの ⋯ に出す「参加する／参加をやめる」（ネイティブ ChannelJoin.kt の rememberChannelJoinItem）。
 * メッセージ画面・詳細のルームのヘッダと、デッキのルームカラムの ⋯ で同じものを使う。
 * 未ログイン・自分のリストが未取得なら null（項目を出さない）。保存は最新版を取り直してから発行するので数秒かかることがあり、
 * その間に押しても重ねて発行しない。終わったらトーストで知らせる。
 */
export function useChannelJoin(channelId: string): ChannelJoinAction | null {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const joined = useIsJoined(channelId);
  // ヒント = そのチャンネルのリレーの先頭（一覧に無ければヒント無し）
  const hint = useChannel(channelId)?.relays.find((url) => url.startsWith("wss://")) ?? null;
  const busy = useRef(false);
  if (!me || joined === null) return null;

  return {
    joined,
    label: joined ? t("channel_leave") : t("channel_join"),
    onSelect: () => {
      if (busy.current) return;
      busy.current = true;
      (joined ? leave(me, channelId) : join(me, channelId, hint))
        .then(
          () => showToast(joined ? t("channel_left_toast") : t("channel_joined_toast")),
          (e) => showToast(joinFailureMessage(e)),
        )
        .finally(() => {
          busy.current = false;
        });
    },
  };
}
