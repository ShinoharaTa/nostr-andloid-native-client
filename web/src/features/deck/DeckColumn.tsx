import { npubEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { Virtuoso, type VirtuosoHandle } from "react-virtuoso";
import { useCloseMenuOnBack } from "../../app/history";
import { t, useT } from "../../i18n";
import {
  type ColumnKind,
  type ColumnSpec,
  type ColumnWidth,
  columnLabel,
  columnSubtitleFor,
  editTemplate,
  encodeReqFilter,
  roomColumnFor,
} from "../../lib/columns";
import { useProfile } from "../../nostr/loaders";
import {
  FEED_CATEGORIES,
  type FeedCategory,
  feedCatHiddenOf,
  isMutedRevealed,
  statusTypeOf,
  useDeck,
  widthOf,
} from "../../store/deck";
import { columnIcon, Icon } from "../../ui/icons";
import { MyReactionRow } from "../actions/MyReactionRow";
import { ChannelList } from "../chat/ChannelList";
import { ChannelRoom } from "../chat/ChannelRoom";
import { pinRoom, usePinnedRoomIds } from "../chat/pin";
import { ConversationList } from "../dm/ConversationList";
import { startDecrypting } from "../dm/dmService";
import { KbRow, useKbList } from "../keyboard/KbList";
import { NotificationList } from "../notifications/NotificationList";
import { NotificationRow } from "../notifications/NotificationRow";
import { StatusCard } from "../status/StatusCard";
import { isStatusVisible, type StatusType, sortStatuses } from "../status/statusModel";
import { NoteItem } from "../timeline/NoteItem";
import { Timeline } from "../timeline/Timeline";
import { useNow } from "../timeline/useNow";
import { useZapReceipts, zapTargetIds } from "../zap/useZapReceipts";
import styles from "./DeckColumn.module.css";
import type { FeedRow } from "./followingMix";
import { ProfileColumnHeader } from "./ProfileColumnHeader";
import { useColumnFeed } from "./useColumnFeed";

/** Web 版でまだ描けない種別（REQ も張らない） */
const UNSUPPORTED_KINDS: ReadonlySet<ColumnKind> = new Set(["THREAD"]);

const WIDTHS: readonly ColumnWidth[] = ["S", "M", "L"];

/** ステータスカラムの「表示」の選択肢（null = すべて） */
const STATUS_FILTERS: readonly (StatusType | null)[] = [null, "music", "general"];

/** 「表示」の項目名。ヘッダのサブタイトルにも使う（すべて = NIP-38） */
function statusFilterLabel(type: StatusType | null): string {
  switch (type) {
    case null:
      return t("web_status_filter_all");
    case "music":
      return t("web_status_type_music");
    case "general":
      return t("web_status_type_general");
  }
}

function widthLabel(width: ColumnWidth): string {
  switch (width) {
    case "S":
      return t("width_narrow");
    case "M":
      return t("width_default");
    case "L":
      return t("width_wide");
  }
}

/** ⋯ の「タイムラインに混ぜる表示」の項目名（ネイティブ cat_*） */
function categoryLabel(category: FeedCategory): string {
  switch (category) {
    case "REACTIONS":
      return t("cat_reactions_to_me");
    case "REPLIES":
      return t("cat_replies_to_me");
    case "REPOSTS":
      return t("cat_reposts_of_me");
    case "MY_REACTIONS":
      return t("cat_my_reactions");
    case "DMS":
      return t("cat_dms");
  }
}

/** デッキの 1 カラム。showHeader = カラムヘッダ（アイコン・タイトル・⋯）を出す */
export function DeckColumn({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) {
  if (UNSUPPORTED_KINDS.has(spec.kind)) return <UnsupportedColumn spec={spec} showHeader={showHeader} />;
  if (spec.kind === "DM") return <DmColumn spec={spec} showHeader={showHeader} />;
  if (spec.kind === "CHANNEL_LIST") return <ChannelListColumn spec={spec} showHeader={showHeader} />;
  if (spec.kind === "CHANNEL_ROOM") return <RoomColumn spec={spec} showHeader={showHeader} />;
  if (spec.kind === "STATUS") return <StatusColumn spec={spec} showHeader={showHeader} />;
  return <FeedColumn spec={spec} showHeader={showHeader} />;
}

/** 同期などで入ってきた未対応の種別。⋯ から削除だけできる */
function UnsupportedColumn({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) {
  const t = useT();
  return (
    <section className={styles.column} aria-label={columnLabel(spec)}>
      {showHeader && <ColumnHeader spec={spec} />}
      <p className={styles.empty}>{t("web_deck_unsupported_column")}</p>
    </section>
  );
}

/**
 * DM カラム（ネイティブ DmColumn #415）。会話の一覧を出し、行を押すとメッセージ画面をその相手で開く。
 * REQ は張らない（DM の購読は dmService）。表示したら復号を始める（NIP-07 / NIP-46 はここまで署名者を呼ばない）
 */
function DmColumn({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) {
  const navigate = useNavigate();
  useEffect(() => {
    startDecrypting();
  }, []);
  return (
    <section className={styles.column} aria-label={columnLabel(spec)}>
      {showHeader && <ColumnHeader spec={spec} />}
      <div className={`${styles.body} ${styles.scroll}`}>
        <ConversationList
          selectedPeer={null}
          // カラムから会話を開くのは詳細を開くのと同じ扱い（履歴に積む）。戻るでデッキに戻れるように
          onSelect={(peer) => void navigate(`/messages/${npubEncode(peer)}`)}
          showBanners={false}
          showNewRow={false}
        />
      </div>
    </section>
  );
}

/**
 * NIP-28 のチャンネル一覧カラム（ネイティブ ChannelListColumn。カラム追加の一覧・同期で来たものを描く）。行を押すとルームを一時カラムで開き
 * （戻るとこのカラムへ）、ピンで固定カラムにする。
 */
function ChannelListColumn({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) {
  const pinnedIds = usePinnedRoomIds();
  return (
    <section className={styles.column} aria-label={columnLabel(spec)}>
      {showHeader && <ColumnHeader spec={spec} />}
      <div className={`${styles.body} ${styles.scroll}`}>
        <ChannelList
          selectedId={null}
          pinnedIds={pinnedIds}
          onSelect={(channel) => useDeck.getState().openTransient(roomColumnFor(channel), spec.id)}
          onPin={pinRoom}
        />
      </div>
    </section>
  );
}

/** NIP-28 のルームカラム（ネイティブ LiveChannelRoom の deckMode）。⋯ の「ミュートを表示」が効く */
function RoomColumn({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) {
  const t = useT();
  const revealed = useDeck((s) => isMutedRevealed(s, spec.id));
  const header = showHeader ? <ColumnHeader spec={spec} /> : null;
  const channelId = spec.filter.channelId;
  if (channelId === null) {
    return (
      <section className={styles.column} aria-label={columnLabel(spec)}>
        {header}
        <p className={styles.empty}>{t("web_deck_channel_missing")}</p>
      </section>
    );
  }
  return (
    <ChannelRoom
      key={channelId}
      channelId={channelId}
      title={columnLabel(spec)}
      mode="column"
      header={header}
      revealMuted={revealed}
    />
  );
}

function FeedColumn({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) {
  const t = useT();
  const { loading, events, rows, loadingOlder, loadOlder, refresh, emptyText } = useColumnFeed(spec);
  // 表示中の投稿への Zap 受領（アクション行の ⚡ の合計）
  useZapReceipts(zapTargetIds(events));
  // PROFILE カラムの上部カード（ネイティブ ProfileColumn と同じ。一覧の先頭に置き、スクロールに追従させる）
  const profilePubkey = spec.kind === "PROFILE" ? spec.filter.authors[0] : undefined;
  const profileHeader = profilePubkey ? <ProfileColumnHeader pubkey={profilePubkey} /> : undefined;
  return (
    <section className={styles.column} aria-label={columnLabel(spec)} aria-busy={loading}>
      {showHeader && <ColumnHeader spec={spec} onRefresh={refresh} />}
      <div className={styles.body}>
        {loading && (
          <div className={styles.progress} role="progressbar" aria-label={t("web_deck_loading_aria")} />
        )}
        {spec.kind === "FAVS" ? (
          <FavsList
            reactions={events}
            loading={loading}
            onEndReached={loadOlder}
            loadingOlder={loadingOlder}
          />
        ) : spec.kind === "NOTIFICATIONS" ? (
          <NotificationList events={events} loading={loading} columnId={spec.id} onRefresh={refresh} />
        ) : rows ? (
          // フォロー中カラム: 投稿に自分への反応・自分のリアクション・未読 DM を混ぜた行
          <Timeline
            key={encodeReqFilter(spec.filter)}
            events={rows}
            loading={loading}
            onEndReached={loadOlder}
            loadingOlder={loadingOlder}
            renderItem={renderFeedRow}
            postOf={feedRowPost}
            emptyText={emptyText}
            onRefresh={refresh}
          />
        ) : (
          <Timeline
            // フィルターを変えたら中身が入れ替わるので、位置も先頭から
            key={encodeReqFilter(spec.filter)}
            events={events}
            loading={loading}
            onEndReached={loadOlder}
            loadingOlder={loadingOlder}
            emptyText={emptyText}
            header={profileHeader}
            onRefresh={refresh}
          />
        )}
      </div>
    </section>
  );
}

/** ステータスの一覧を進める時計（期限切れをその場で落とす。カードの残り時間と同じ 10 秒） */
const STATUS_CLOCK_MS = 10_000;

/**
 * ステータス（NIP-38）のカラム（#767）。フォロー中の人 + 自分の general / music を 1 人 1 種類 = 1 枚で新しい順に並べる。
 * 空・期限切れ・古すぎる期限なしは出さない（期限切れは時計で落とす）。⋯ の「表示」で種類を絞る（表示だけ。REQ は張り直さない）。
 * 過去読みはしない（置き換え可能で 1 人 2 件までなので、最初の REQ で取り切る）
 */
function StatusColumn({ spec, showHeader }: { spec: ColumnSpec; showHeader: boolean }) {
  const t = useT();
  const { loading, events, refresh, emptyText } = useColumnFeed(spec);
  const type = useDeck((s) => statusTypeOf(s, spec.id));
  const now = useNow(STATUS_CLOCK_MS);
  const statuses = useMemo(
    () => sortStatuses(events.filter((e) => isStatusVisible(e, type, now))),
    [events, type, now],
  );
  const list = useRef<VirtuosoHandle>(null);
  // キー操作は j / k の移動だけ（ステータスは r / t / f の対象外）
  useKbList(list, statuses.length);
  const empty = emptyText ?? t(type === "music" ? "web_status_empty_music" : "web_status_empty");
  return (
    <section className={styles.column} aria-label={columnLabel(spec)} aria-busy={loading}>
      {showHeader && <ColumnHeader spec={spec} onRefresh={refresh} />}
      <div className={styles.body}>
        {loading && (
          <div className={styles.progress} role="progressbar" aria-label={t("web_deck_loading_aria")} />
        )}
        {statuses.length === 0 ? (
          <p className={styles.empty}>{loading ? t("loading") : empty}</p>
        ) : (
          <Virtuoso
            ref={list}
            className={styles.list}
            data={statuses}
            computeItemKey={(_, status) => status.id}
            itemContent={(index, status) => (
              <KbRow index={index}>
                <StatusCard event={status} />
              </KbRow>
            )}
          />
        )}
      </div>
    </section>
  );
}

/**
 * カラムヘッダ（ネイティブの ColumnHeader）。先頭 40px のアイコン、タイトル + 説明、末尾に ⋯。
 * PROFILE はプロフィール（kind:0）の名前をタイトルにする（ネイティブ ProfileColumn.kt:66-71。未取得なら spec.title）。
 */
function ColumnHeader({ spec, onRefresh }: { spec: ColumnSpec; onRefresh?: () => void }) {
  useT();
  const profilePubkey = spec.kind === "PROFILE" ? spec.filter.authors[0] : undefined;
  const profile = useProfile(profilePubkey);
  const title =
    profilePubkey && typeof profile?.name === "string" && profile.name.trim() !== ""
      ? profile.name
      : columnLabel(spec);
  // ステータスカラムは「表示」で絞っている種類をサブタイトルに出す（すべてなら NIP-38）
  const statusType = useDeck((s) => (spec.kind === "STATUS" ? statusTypeOf(s, spec.id) : null));
  const subtitle = statusType !== null ? statusFilterLabel(statusType) : columnSubtitleFor(spec);
  return (
    <header className={styles.header}>
      <span className={styles.icon}>
        <Icon name={columnIcon(spec.kind)} size="lg" />
      </span>
      <div className={styles.titles}>
        <h2 className={styles.title}>{title}</h2>
        <p className={styles.subtitle}>{subtitle}</p>
      </div>
      <ColumnMenu spec={spec} onRefresh={onRefresh} />
    </header>
  );
}

/** フォロー中カラムの 1 行。通知は通知画面の行、自分のリアクションはふぁぼ欄の行と同じ */
function renderFeedRow(row: FeedRow) {
  switch (row.type) {
    case "post":
      return <NoteItem event={row.event} />;
    case "notice":
      return <NotificationRow item={row.item} />;
    case "myReaction":
      return <MyReactionRow reaction={row.reaction} />;
  }
}

/** 混在の行の投稿（キー操作で選べるのは投稿の行だけ。通知・リアクション・DM の行は飛ばす） */
function feedRowPost(row: FeedRow): NostrEvent | null {
  return row.type === "post" ? row.event : null;
}

type FooterContext = { loadingOlder: boolean };

function FavsFooter({ context }: { context?: FooterContext }) {
  const t = useT();
  return context?.loadingOlder ? <p className={styles.empty}>{t("feed_loading_older")}</p> : null;
}

const FAVS_COMPONENTS = { Footer: FavsFooter };

/** ふぁぼ欄。自分のリアクション（kind:7）を 1 行ずつ要約して並べる（MyReactionRow） */
function FavsList({
  reactions,
  loading,
  onEndReached,
  loadingOlder,
}: {
  reactions: NostrEvent[];
  loading: boolean;
  onEndReached: () => void;
  loadingOlder: boolean;
}) {
  const t = useT();
  const list = useRef<VirtuosoHandle>(null);
  // キー操作の対象にする（ふぁぼの行は r / t / f の対象外）
  useKbList(list, reactions.length);
  if (reactions.length === 0) {
    return <p className={styles.empty}>{loading ? t("loading") : t("feed_empty")}</p>;
  }
  return (
    <Virtuoso
      ref={list}
      className={styles.list}
      data={reactions}
      computeItemKey={(_, reaction) => reaction.id}
      endReached={onEndReached}
      components={FAVS_COMPONENTS}
      context={{ loadingOlder }}
      itemContent={(index, reaction) => (
        <KbRow index={index}>
          <FavItem reaction={reaction} />
        </KbRow>
      )}
    />
  );
}

function FavItem({ reaction }: { reaction: NostrEvent }) {
  return <MyReactionRow reaction={reaction} />;
}

/**
 * カラムの ⋯ メニュー（ネイティブの ColumnMenuButton）。移動 ◀ ▶ / フィルターを編集 / ミュートを表示・隠す /
 * 更新 / 固定する / タイムラインに混ぜる表示 / カラム幅 / カラムを削除。外側のクリック・Escape・戻る（#540）で閉じる。
 * onRefresh が無ければ「更新」を出さない。「ミュートを表示」は renderer が FEED / THREAD のときだけ
 * （ネイティブ DeckScreen.kt と同じ。CHANNEL_LIST は対象外）。ROOM（パブリックチャット）は
 * ネイティブには無い Web 独自の対象（発言のミュート表示切替は Web の既存機能なので残す。D3）。
 * 「タイムラインに混ぜる表示」はフォロー中カラムだけ。「表示（すべて / Now Playing / ステータス）」はステータスカラムだけ（#767）。
 */
export function ColumnMenu({ spec, onRefresh }: { spec: ColumnSpec; onRefresh?: () => void }) {
  useT();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const index = useDeck((s) => s.columns.findIndex((c) => c.id === spec.id));
  const count = useDeck((s) => s.columns.length);
  const width = useDeck((s) => widthOf(s, spec.id));
  const mutedRevealed = useDeck((s) => isMutedRevealed(s, spec.id));
  const hiddenCategories = useDeck((s) => feedCatHiddenOf(s, spec.id));
  const statusType = useDeck((s) => statusTypeOf(s, spec.id));

  // [#540] 開いている間の「戻る」はメニューを閉じるだけにする
  useCloseMenuOnBack(open, () => setOpen(false));

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setOpen(false);
      button.current?.focus();
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  /** 押したらメニューを閉じる項目 */
  const act = (action: () => void) => () => {
    setOpen(false);
    action();
  };
  const deck = useDeck.getState;

  return (
    <div ref={root} className={styles.menuRoot}>
      <button
        ref={button}
        type="button"
        className={styles.iconButton}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={t("col_menu")}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="moreHoriz" size="md" />
      </button>
      {open && (
        <div role="menu" aria-label={t("col_menu")} className={styles.menu}>
          {/* 移動と幅はメニューを閉じない（続けて押せるように。ネイティブと同じ） */}
          <fieldset aria-label={t("col_move")} className={styles.menuRow}>
            <span className={styles.menuLabel}>{t("col_move")}</span>
            <button
              type="button"
              role="menuitem"
              className={styles.arrow}
              aria-label={t("col_move_left")}
              disabled={index <= 0}
              onClick={() => deck().moveColumn(spec.id, -1)}
            >
              <Icon name="chevronLeft" size="lg" />
            </button>
            <button
              type="button"
              role="menuitem"
              className={styles.arrow}
              aria-label={t("col_move_right")}
              disabled={index < 0 || index >= count - 1}
              onClick={() => deck().moveColumn(spec.id, 1)}
            >
              <Icon name="chevronRight" size="lg" />
            </button>
          </fieldset>
          {editTemplate(spec) !== null && (
            <button
              type="button"
              role="menuitem"
              className={styles.menuItem}
              onClick={act(() => deck().setEditing(spec.id))}
            >
              <Icon name="tune" size="md" />
              {t("col_edit_filter")}
            </button>
          )}
          {!UNSUPPORTED_KINDS.has(spec.kind) &&
            (spec.renderer === "FEED" || spec.renderer === "THREAD" || spec.renderer === "ROOM") && (
              <button
                type="button"
                role="menuitem"
                className={styles.menuItem}
                onClick={act(() => deck().setRevealMuted(spec.id, !mutedRevealed))}
              >
                <Icon name={mutedRevealed ? "visibilityOff" : "visibility"} size="md" />
                {mutedRevealed ? t("col_hide_muted") : t("col_show_muted")}
              </button>
            )}
          {onRefresh && (
            <button type="button" role="menuitem" className={styles.menuItem} onClick={act(onRefresh)}>
              <Icon name="refresh" size="md" />
              {t("web_deck_refresh")}
            </button>
          )}
          {!spec.pinned && (
            <button
              type="button"
              role="menuitem"
              className={styles.menuItem}
              onClick={act(() => deck().pin(spec.id))}
            >
              <Icon name="pushPin" size="md" />
              {t("web_deck_pin")}
            </button>
          )}
          {spec.kind === "FOLLOWING" && (
            // 種別のトグルはメニューを閉じない（続けて切り替えられるように。ネイティブと同じ）
            <fieldset aria-label={t("col_mix_categories")} className={styles.menuGroup}>
              <span className={styles.menuHeading}>{t("col_mix_categories")}</span>
              {FEED_CATEGORIES.map((category) => {
                const shown = !hiddenCategories.includes(category);
                return (
                  <button
                    key={category}
                    type="button"
                    role="menuitemcheckbox"
                    aria-checked={shown}
                    className={styles.menuItem}
                    onClick={() => deck().setFeedCatHidden(spec.id, category, shown)}
                  >
                    <Icon name={shown ? "checkBox" : "checkBoxOutlineBlank"} size="md" />
                    {categoryLabel(category)}
                  </button>
                );
              })}
            </fieldset>
          )}
          {spec.kind === "STATUS" && (
            // 種類の切替は幅と同じくメニューを閉じない
            <fieldset aria-label={t("web_status_filter")} className={styles.menuRow}>
              <span className={styles.menuLabel}>{t("web_status_filter")}</span>
              {STATUS_FILTERS.map((type) => (
                <button
                  key={type ?? "all"}
                  type="button"
                  role="menuitemradio"
                  aria-checked={statusType === type}
                  className={styles.chip}
                  onClick={() => deck().setStatusType(spec.id, type)}
                >
                  {statusFilterLabel(type)}
                </button>
              ))}
            </fieldset>
          )}
          <fieldset aria-label={t("col_width")} className={styles.menuRow}>
            <span className={styles.menuLabel}>{t("col_width")}</span>
            {WIDTHS.map((w) => (
              <button
                key={w}
                type="button"
                role="menuitemradio"
                aria-checked={width === w}
                className={styles.chip}
                onClick={() => deck().setWidth(spec.id, w)}
              >
                {widthLabel(w)}
              </button>
            ))}
          </fieldset>
          <button
            type="button"
            role="menuitem"
            className={`${styles.menuItem} ${styles.danger}`}
            onClick={act(() => deck().removeColumn(spec.id))}
          >
            <Icon name="close" size="md" />
            {t("col_delete")}
          </button>
        </div>
      )}
    </div>
  );
}
