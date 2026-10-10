import { npubEncode } from "nostr-tools/nip19";
import { useEffect, useMemo } from "react";
import { useLocation, useNavigate, useParams } from "react-router";
import { parseProfileRef } from "../../app/overlays/refs";
import { useT } from "../../i18n";
import { Icon } from "../../ui/icons";
import { ScreenHeader } from "../../ui/ScreenHeader";
import { type LayoutMode, useLayoutMode } from "../../ui/useLayoutMode";
import { ChannelList } from "../chat/ChannelList";
import { ChannelRoom, RoomHeader } from "../chat/ChannelRoom";
import { type Channel, ensureChannels, useChannel, useChannels } from "../chat/channels";
import { channelHref } from "../chat/chatMessage";
import { pinRoom, usePinnedRoomIds } from "../chat/pin";
import { ConversationList } from "./ConversationList";
import { ConversationView } from "./ConversationView";
import { startDecrypting } from "./dmService";
import styles from "./MessagesScreen.module.css";

/** 一覧から開いた会話の履歴エントリの印（Compact の「←」で戻れるか） */
const FROM_LIST = "dmFromList";

function openedFromList(state: unknown): boolean {
  return (
    typeof state === "object" && state !== null && (state as Record<string, unknown>)[FROM_LIST] === true
  );
}

/** パブリックチャットの一覧の URL（ナビの 3 枠目） */
const CHANNELS_PATH = "/channels";

/**
 * DM・パブリックチャット（ネイティブ DmScreen / PublicChatScreen + TwoPane）。
 * [#797] 一覧の見出しは通常のカラムヘッダ（DM / NIP-17、パブリックチャット / NIP-28）。#422 の「DM | チャット」の切り替えはやめた
 * （DM は自分のアイコンのメニューから、パブリックチャットはナビの 3 枠目から開く）。
 * kind = dm: URL は /messages/:peer?（npub。hex も受ける）、chat: /channels/:id?（チャンネルの id）。
 * Expanded = 左に一覧・右に会話 / ルーム（未選択は「会話を選択」「チャンネルを選択」）、Compact/Rail = 一覧 → 会話 / ルーム
 * （「←」で一覧へ。[#661] Rail は内容が Compact と同じ 1 ペイン）。表示したら DM の復号を始める
 * （NIP-07 / NIP-46 はここまで署名者を呼ばない。DM 側の未読数にも使う）。
 */
export function MessagesScreen({ kind = "dm" }: { kind?: "dm" | "chat" }) {
  const mode = useLayoutMode();
  useEffect(() => {
    startDecrypting();
  }, []);
  return kind === "chat" ? <ChannelsPanes mode={mode} /> : <DmPanes mode={mode} />;
}

function DmPanes({ mode }: { mode: LayoutMode }) {
  const t = useT();
  const { peer: param } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  // undefined = 未選択、null = 読めない
  const peer = useMemo(
    () => (param === undefined ? undefined : (parseProfileRef(param)?.pubkey ?? null)),
    [param],
  );

  // Compact は戻る対象にする（一覧 → 会話）、Expanded は会話の切り替えなので置き換える
  function select(pubkey: string) {
    if (pubkey === peer) return;
    const path = `/messages/${npubEncode(pubkey)}`;
    if (mode !== "expanded") void navigate(path, { state: { [FROM_LIST]: true } });
    else void navigate(path, { replace: true });
  }

  function back() {
    if (openedFromList(location.state)) void navigate(-1);
    else void navigate("/messages", { replace: true });
  }

  // Expanded の ✕（選択解除）。一覧はそのままなので置き換えでプレースホルダへ戻す
  function close() {
    void navigate("/messages", { replace: true });
  }

  if (mode !== "expanded") {
    return (
      <div className={styles.single}>
        {peer === undefined ? (
          <ListPane selectedPeer={null} onSelect={select} />
        ) : (
          <ConversationPane peer={peer} onBack={back} />
        )}
      </div>
    );
  }
  return (
    <div className={styles.twoPane}>
      <div className={styles.listPane}>
        <ListPane selectedPeer={peer ?? null} onSelect={select} />
      </div>
      <div className={styles.detailPane}>
        {peer === undefined ? (
          <p className={styles.placeholder}>{t("dm_select_conversation")}</p>
        ) : (
          <ConversationPane peer={peer} onClose={close} />
        )}
      </div>
    </div>
  );
}

function ListPane({ selectedPeer, onSelect }: { selectedPeer: string | null; onSelect(peer: string): void }) {
  const t = useT();
  return (
    <div className={styles.list}>
      <ScreenHeader title={t("nav_dm")} subtitle="NIP-17" icon={<Icon name="mailOutline" size="lg" />} />
      <div className={styles.listBody}>
        <ConversationList selectedPeer={selectedPeer} onSelect={onSelect} showBanners showNewRow />
      </div>
    </div>
  );
}

/** 会話。相手が読めなければその旨（Compact は「←」つき） */
function ConversationPane({
  peer,
  onBack,
  onClose,
}: {
  peer: string | null;
  onBack?: () => void;
  onClose?: () => void;
}) {
  const t = useT();
  if (peer === null) {
    return (
      <div className={styles.invalid}>
        {onBack && <ScreenHeader title={t("nav_dm")} onBack={onBack} />}
        <p className={styles.placeholder}>{t("web_dm_peer_unreadable")}</p>
      </div>
    );
  }
  // 相手が替わったら表示件数を戻す
  return <ConversationView key={peer} peer={peer} onBack={onBack} onClose={onClose} />;
}

const HEX64 = /^[0-9a-f]{64}$/i;

/** URL のチャンネル id（64 桁の hex）。読めなければ null */
function parseChannelId(param: string): string | null {
  return HEX64.test(param) ? param.toLowerCase() : null;
}

/** パブリックチャット（ネイティブ PublicChatScreen）。一覧 → ルーム。「ピン留め」でデッキの固定カラムへ */
function ChannelsPanes({ mode }: { mode: LayoutMode }) {
  const t = useT();
  const { id: param } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  // undefined = 未選択、null = 読めない
  const channelId = param === undefined ? undefined : parseChannelId(param);
  const pinnedIds = usePinnedRoomIds();

  function select(channel: Channel) {
    if (channel.id === channelId) return;
    const path = channelHref(channel.id);
    if (mode !== "expanded") void navigate(path, { state: { [FROM_LIST]: true } });
    else void navigate(path, { replace: true });
  }

  function back() {
    if (openedFromList(location.state)) void navigate(-1);
    else void navigate(CHANNELS_PATH, { replace: true });
  }

  // Expanded の ✕（選択解除）。一覧はそのままなので置き換えでプレースホルダへ戻す
  function close() {
    void navigate(CHANNELS_PATH, { replace: true });
  }

  const list = (
    <div className={styles.list}>
      <ScreenHeader
        title={t("nav_public_chat")}
        subtitle="NIP-28 · channels"
        icon={<Icon name="tag" size="lg" />}
      />
      <div className={styles.listBody}>
        <ChannelList selectedId={channelId ?? null} pinnedIds={pinnedIds} onSelect={select} onPin={pinRoom} />
      </div>
    </div>
  );

  if (mode !== "expanded") {
    return (
      <div className={styles.single}>
        {channelId === undefined ? list : <RoomPane channelId={channelId} onBack={back} />}
      </div>
    );
  }
  return (
    <div className={styles.twoPane}>
      <div className={styles.listPane}>{list}</div>
      <div className={styles.detailPane}>
        {channelId === undefined ? (
          <p className={styles.placeholder}>{t("chat_select_channel")}</p>
        ) : (
          <RoomPane channelId={channelId} onClose={close} />
        )}
      </div>
    </div>
  );
}

/**
 * ルーム。一覧を取っている間は「チャンネルを読み込み中…」。一覧に無いチャンネルでも（取れた・失敗した後は）ルームを開く
 * （名前は「パブリックチャット」）。
 */
function RoomPane({
  channelId,
  onBack,
  onClose,
}: {
  channelId: string | null;
  onBack?: () => void;
  onClose?: () => void;
}) {
  const t = useT();
  const channel = useChannel(channelId);
  const listLoading = useChannels((s) => s.channels === null && !s.failed);
  useEffect(() => {
    ensureChannels();
  }, []);

  if (channelId === null || (!channel && listLoading)) {
    return (
      <div className={styles.invalid}>
        {onBack && <ScreenHeader title={t("nav_public_chat")} onBack={onBack} />}
        <p className={styles.placeholder}>
          {channelId === null ? t("web_chat_channel_unreadable") : t("chat_loading_channel")}
        </p>
      </div>
    );
  }
  const title = channel?.name || t("nav_public_chat");
  const about = channel?.about ?? "";
  return (
    <ChannelRoom
      key={channelId}
      channelId={channelId}
      title={title}
      mode="screen"
      header={
        <RoomHeader
          title={title}
          subtitle={about.trim() === "" ? "NIP-28 · kind:42" : about}
          picture={channel?.picture ?? null}
          onBack={onBack}
          onClose={onClose}
        />
      }
    />
  );
}
