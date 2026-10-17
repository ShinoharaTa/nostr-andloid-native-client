import type { EventPointer } from "nostr-tools/nip19";
import { type ReactNode, useEffect, useMemo, useState } from "react";
import { useT } from "../../i18n";
import { avatarInitial, avatarShade } from "../../lib/avatar";
import { isDataSaver, markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { useEventByPointer } from "../../nostr/loaders";
import { useSession } from "../../signer/session";
import { EditIcon, Icon } from "../../ui/icons";
import { ChannelEditDialog } from "./ChannelEditDialog";
import styles from "./ChannelList.module.css";
import { ensureMyChannelsSubscribed, useMyChannelIds } from "./channelEdit";
import { type Channel, refreshChannels, useChannels } from "./channels";
import { channelFromCreateEvent } from "./eventLink";
import { useJoinedRooms } from "./joinedRooms";

const NO_IDS: string[] = [];

/**
 * チャンネル一覧（ネイティブ ChannelListColumn。メッセージ画面のチャット側と CHANNEL_LIST カラムで使う）。
 * 表示したら /api/nchan/channels を取り直す。最終更新の新しい順。行 = 画像・名前・説明 1 行・「ピン留め」。
 * 行を押すと onSelect、ピンを押すと onPin（デッキにピン留め済みのものは色を変える）。
 * [#538] ログイン中は先頭に「新しいスレッドを作成」。自分が作った kind:40 の行には ✏️ で編集。
 * 作成したらローカルの一覧へ即反映してそのまま onSelect（ネイティブと同じく API の一覧を待たない）。
 * [#856] 参加中のルーム（kind:10005）があれば「参加中」として上に分け（一覧と同じ最終活動の順）、残りを「すべてのルーム」に
 * 並べる（参加中は下に重ねて出さない）。一覧に無い参加中のルームは参加中の末尾に出し、kind:40 を id で取りに行く
 * （届くまで「パブリックチャット」）。参加中が 0 件なら見出しを出さず今までどおり。
 */
export function ChannelList({
  selectedId,
  pinnedIds,
  onSelect,
  onPin,
}: {
  selectedId: string | null;
  pinnedIds: ReadonlySet<string>;
  onSelect(channel: Channel): void;
  onPin(channel: Channel): void;
}) {
  const t = useT();
  const channels = useChannels((s) => s.channels);
  const failed = useChannels((s) => s.failed);
  const me = useSession((s) => s.pubkey);
  const myChannelIds = useMyChannelIds(me);
  const [showCreate, setShowCreate] = useState(false);
  const [editing, setEditing] = useState<Channel | null>(null);
  const joined = useJoinedRooms((s) => s.joined);
  // 一覧を取っている間は分けない（一覧に有るかが分からず、全部を kind:40 で取りに行ってしまう）
  const joinedIds = channels !== null || failed ? (joined?.ids ?? NO_IDS) : NO_IDS;
  const sections = useMemo(() => {
    const ids = new Set(joinedIds);
    const listed = channels ?? [];
    const known = listed.filter((c) => ids.has(c.id.toLowerCase()));
    const knownIds = new Set(known.map((c) => c.id.toLowerCase()));
    return {
      known,
      unlisted: joinedIds.filter((id) => !knownIds.has(id)),
      others: ids.size === 0 ? channels : listed.filter((c) => !ids.has(c.id.toLowerCase())),
    };
  }, [channels, joinedIds]);

  function row(channel: Channel, key = channel.id) {
    return (
      <ChannelRow
        key={key}
        channel={channel}
        selected={channel.id === selectedId}
        pinned={pinnedIds.has(channel.id)}
        onSelect={onSelect}
        onPin={onPin}
        onEdit={me && myChannelIds.has(channel.id) ? () => setEditing(channel) : undefined}
      />
    );
  }

  useEffect(() => {
    void refreshChannels();
  }, []);
  useEffect(() => {
    if (me) ensureMyChannelsSubscribed(me);
  }, [me]);

  return (
    <>
      {me && (
        <button type="button" className={styles.createRow} onClick={() => setShowCreate(true)}>
          <Icon name="add" size="sm" className={styles.createIcon} />
          {t("channel_create_row")}
        </button>
      )}
      {joinedIds.length > 0 && (
        <>
          <h3 className={styles.section}>{t("channel_joined_section")}</h3>
          <ul className={styles.rows}>
            {sections.known.map((channel) => row(channel))}
            {sections.unlisted.map((id) => (
              <UnlistedJoinedRow key={id} id={id} hint={joined?.hints[id]} render={row} />
            ))}
          </ul>
          <h3 className={styles.section}>{t("channel_all_section")}</h3>
        </>
      )}
      {sections.others === null || sections.others.length === 0 ? (
        <p className={styles.empty}>
          {channels === null && !failed
            ? t("loading")
            : channels === null
              ? t("web_chat_channels_failed")
              : t("web_chat_channels_empty")}
        </p>
      ) : (
        <ul className={styles.rows}>{sections.others.map((channel) => row(channel))}</ul>
      )}
      {showCreate && me && (
        <ChannelEditDialog
          me={me}
          channel={null}
          onDone={(created) => {
            setShowCreate(false);
            onSelect(created);
          }}
          onDismiss={() => setShowCreate(false)}
        />
      )}
      {editing && me && (
        <ChannelEditDialog
          me={me}
          channel={editing}
          onDone={() => setEditing(null)}
          onDismiss={() => setEditing(null)}
        />
      )}
    </>
  );
}

/**
 * [#856] 一覧（thread.nchan.vip）に無い参加中のルーム。kind:40 を id（とリレーのヒント）で取りに行き、
 * 届くまで・名前が空なら「パブリックチャット」で出す（ネイティブ requestJoinedChannels / chat_room_unnamed）。開ける。
 */
function UnlistedJoinedRow({
  id,
  hint,
  render,
}: {
  id: string;
  hint: string | undefined;
  render(channel: Channel, key: string): ReactNode;
}) {
  const t = useT();
  const pointer = useMemo<EventPointer>(() => (hint ? { id, relays: [hint] } : { id }), [id, hint]);
  const created = useEventByPointer(pointer);
  const fetched = created ? channelFromCreateEvent(created) : null;
  const channel: Channel = {
    ...(fetched ?? { id, about: "", picture: null, relays: [], createdAt: 0, lastAt: 0 }),
    id,
    name: fetched?.name.trim() ? fetched.name : t("chat_room_unnamed"),
  };
  return render(channel, id);
}

function ChannelRow({
  channel,
  selected,
  pinned,
  onSelect,
  onPin,
  onEdit,
}: {
  channel: Channel;
  selected: boolean;
  pinned: boolean;
  onSelect(channel: Channel): void;
  onPin(channel: Channel): void;
  /** 自分が作成した kind:40 のときだけ渡る */
  onEdit?: () => void;
}) {
  const t = useT();
  return (
    <li className={styles.item}>
      <button
        type="button"
        className={styles.row}
        aria-current={selected ? "true" : undefined}
        onClick={() => onSelect(channel)}
      >
        <ChannelIcon key={channel.picture} name={channel.name} url={channel.picture} />
        <span className={styles.texts}>
          <span className={styles.name}>{channel.name}</span>
          {channel.about.trim() !== "" && <span className={styles.about}>{channel.about}</span>}
        </span>
      </button>
      {onEdit && (
        <button
          type="button"
          className={styles.edit}
          aria-label={t("channel_edit_title")}
          title={t("channel_edit_title")}
          onClick={onEdit}
        >
          <EditIcon className={styles.editIcon} />
        </button>
      )}
      <button
        type="button"
        className={styles.pin}
        aria-label={t("channel_pin")}
        aria-pressed={pinned}
        title={t("channel_pin")}
        onClick={() => onPin(channel)}
      >
        <Icon name="pushPin" size="sm" />
      </button>
    </li>
  );
}

/**
 * チャンネルの画像（角丸の四角。ネイティブ AvatarSquare）。無い・読めなければ名前の頭文字
 * [#540] w=128・q=80・アニメ保持（データセーバー中は先頭フレームだけ）
 */
export function ChannelIcon({ name, url }: { name: string; url: string | null }) {
  const [src, setSrc] = useState(() =>
    url && /^https?:\/\//i.test(url.trim()) ? proxied(url, 128, 80, !isDataSaver()) : null,
  );
  if (!src) {
    return (
      <span
        className={`${styles.icon} ${styles.initial}`}
        style={{ background: avatarShade(name) }}
        aria-hidden="true"
      >
        {avatarInitial(name)}
      </span>
    );
  }

  function onError() {
    const origin = originOf(src);
    if (origin) {
      markProxyBlocked(origin);
      setSrc(/^https:\/\//i.test(origin) ? origin : null);
    } else {
      setSrc(null);
    }
  }

  return (
    <img
      className={styles.icon}
      src={src}
      alt=""
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}
