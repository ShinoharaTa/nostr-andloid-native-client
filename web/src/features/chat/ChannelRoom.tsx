import type { NostrEvent } from "nostr-tools/pure";
import {
  type FocusEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { t, useT } from "../../i18n";
import { oneLine } from "../../lib/content/labels";
import { markProxyBlocked, originOf, proxiedEmoji } from "../../lib/imageProxy";
import { extractMedia } from "../../lib/media";
import { timeLabel } from "../../lib/time";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { retryUnsentNow, useIsUnsent } from "../../nostr/publish";
import { useSession } from "../../signer/session";
import { ConfirmDialog } from "../../ui/ConfirmDialog";
import { EventJsonDialog } from "../../ui/EventJsonDialog";
import {
  AddReactionIcon,
  ArrowBackIcon,
  BoltIcon,
  CloseIcon,
  FavoriteBorderIcon,
  FavoriteIcon,
  MoreHorizIcon,
  ReplyIcon,
  StarBorderIcon,
  StarIcon,
} from "../../ui/icons";
import { MenuButton, type MenuEntry } from "../../ui/MenuButton";
import { showToast } from "../../ui/toast";
import { copyText, plainTextOf } from "../actions/noteLinks";
import { reactionSentMessage, saveMadeEmoji } from "../actions/pickedReaction";
import { ReactionPickerDialog } from "../actions/ReactionPickerDialog";
import { ReportDialog } from "../actions/ReportDialog";
import { useDefaultReaction } from "../actions/reactionPrefs";
import {
  ensureMyReactionsSubscribed,
  publishReaction,
  reactWithDefault,
  reportNote,
  useIsReacted,
} from "../actions/reactions";
import { registerColumnScroll } from "../deck/columnScroll";
import { useKbColumnId } from "../keyboard/KbList";
import { NoteMedia } from "../media/NoteMedia";
import { useMuteMatcher } from "../mute/muteList";
import { MuteListError, muteUser, unmuteUser } from "../mute/muteSync";
import { useDeveloperMode } from "../settings/devMode";
import { useTimeDisplay } from "../settings/timeDisplay";
import type { ReactionGroup } from "../thread/engagement";
import { NewItemsPill } from "../timeline/NewItemsPill";
import { NoteContent } from "../timeline/NoteContent";
import { Avatar } from "../timeline/NoteItem";
import { useNow } from "../timeline/useNow";
import { payInvoiceWithNwc, useNwc } from "../wallet/nwcManager";
import { ZapDialog } from "../zap/ZapDialog";
import { ChannelIcon } from "./ChannelList";
import styles from "./ChannelRoom.module.css";
import { ChatComposer } from "./ChatComposer";
import { replyParentIdOf } from "./chatMessage";
import { useChannelJoin } from "./useChannelJoin";
import { useChannelRoom } from "./useChannelRoom";

/** 同じ送り手でこの秒数未満の連投は名前・アバターを省く（ネイティブ continuation） */
const CONTINUATION_SEC = 300;
/** 下端（最新）からこの距離以内なら「最新にいる」とみなす（フィードの Timeline と同じ 40px） */
const AT_LATEST_THRESHOLD = 40;
/** 下端に見えている発言がこの位置以降なら、新着が無くても「最新へ戻る」を出す（Timeline と同じ 3） */
const SCROLLED_AWAY_INDEX = 3;
/** [#919] 最新へ戻すとき、新しい方からこの件数目が見える範囲より下なら、まずそこまで一気に飛んでから滑らせる（Timeline と同じ 8） */
const JUMP_NEAR_INDEX = 8;

/** 発言の位置 = それより新しい（下にある）件数（見つからなければ 0） */
function positionOf(messages: readonly NostrEvent[], id: string | undefined): number {
  const index = messages.findIndex((m) => m.id === id);
  return index < 0 ? 0 : index;
}

/**
 * チャンネルのルーム（ネイティブ LiveChannelRoom + ChannelRoomColumn）。メッセージ画面とデッキのカラムで同じ形:
 * 最新が下（column-reverse。DOM は新しい順）・下端に常設の入力欄。
 * [#859] 以前はカラムだけ最新が上・「✏️ メッセージを書く」のモーダルだった（同じルームで並びが逆になり使いにくい。#858）。
 * 新着が届いたとき、最新の近くにいれば最新へ寄せ、上（過去）を読んでいれば位置を保つ。自分の発言が届いたら最新へ送る。
 * 最新から離れているときは下端に「↓ N 件の新着」「↓ 最新へ戻る」（フィードと同じピル）。
 * 吹き出しは全員左寄せ（自分も左。ネイティブ mineOnRight=false）。ミュートした人・ワードの発言は出さない（revealMuted なら出す）。
 * [#798] highlightMessageId（本文の kind:42 へのリンクから開いたとき）は、届いたらその位置まで送って短く強調する。
 */
export function ChannelRoom({
  channelId,
  title,
  header,
  revealMuted = false,
  highlightMessageId = null,
}: {
  channelId: string;
  title: string;
  header: ReactNode;
  revealMuted?: boolean;
  highlightMessageId?: string | null;
}) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const { loading, messages, reactions, channelRelays } = useChannelRoom(channelId, revealMuted);
  const [replyTo, setReplyTo] = useState<NostrEvent | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (me) ensureMyReactionsSubscribed(me);
  }, [me]);

  // [#594] 常設の入力欄（textarea）にフォーカスしたら最新のメッセージ（column-reverse の scrollTop 0）を見せる
  function onComposerFocus(e: FocusEvent<HTMLElement>) {
    if (e.target.tagName !== "TEXTAREA" || !scroller.current) return;
    scroller.current.scrollTop = 0;
  }

  const byId = useMemo(() => new Map(messages.map((m) => [m.id, m])), [messages]);

  // ---- 最新への追従と新着のピル（[#859] ネイティブ ChannelRoomColumn の LaunchedEffect と ChatBottomPill） ----
  const newestId = messages[0]?.id;
  const newestMine = me !== null && messages[0]?.pubkey === me;
  const [atLatest, setAtLatest] = useState(true);
  const [scrolledAway, setScrolledAway] = useState(false);
  /** 最後に最新の位置で見た発言。これより新しい件数を新着として数える */
  const [seenNewestId, setSeenNewestId] = useState(newestId);
  const follow = useRef({ newestId, atLatest: true });

  function onScroll() {
    const el = scroller.current;
    if (!el) return;
    // column-reverse の scrollTop は下端が 0、上へ読むほど負
    const near = -el.scrollTop < AT_LATEST_THRESHOLD;
    follow.current.atLatest = near;
    setAtLatest(near);
    if (near) setSeenNewestId(follow.current.newestId);
    // 新しい方から SCROLLED_AWAY_INDEX 件目が見えている範囲より下にあれば、最新から離れている
    const row = el.children[SCROLLED_AWAY_INDEX - 1];
    setScrolledAway(
      row instanceof HTMLElement && row.getBoundingClientRect().top >= el.getBoundingClientRect().bottom,
    );
  }

  useLayoutEffect(() => {
    const el = scroller.current;
    const previous = follow.current.newestId;
    follow.current.newestId = newestId;
    if (!el || newestId === undefined || previous === newestId) return;
    // 自分の発言（読み込み中の過去分は除く）はどこを読んでいても最新へ。最新の近くにいれば最新へ寄せる
    if ((newestMine && !loading) || follow.current.atLatest) {
      el.scrollTop = 0;
      follow.current.atLatest = true;
      setAtLatest(true);
      setSeenNewestId(newestId);
      return;
    }
    // 上を読んでいる間は位置を保つ: 下端に増えた分（最新の発言と前の最新の下端の差）だけ上へずらす
    const before = previous === undefined ? null : el.querySelector(`[data-message-id="${previous}"]`);
    const latest = el.firstElementChild;
    if (before && latest) {
      el.scrollTop -= latest.getBoundingClientRect().bottom - before.getBoundingClientRect().bottom;
    }
  }, [newestId, newestMine, loading]);

  const newCount = atLatest ? 0 : positionOf(messages, seenNewestId);

  // 最新（column-reverse の scrollTop 0）へ戻す（「最新へ戻る」ピルと 1 カラム表示の「ホーム」#919）。
  // 遠くまで読み進めていたら、新しい方から JUMP_NEAR_INDEX 件目が下端に来る位置まで一気に飛んでから滑らせる（ネイティブ #917）
  const scrollToLatest = useCallback(() => {
    const el = scroller.current;
    if (!el) return;
    const row = el.children[JUMP_NEAR_INDEX - 1];
    const bottom = el.getBoundingClientRect().bottom;
    if (row instanceof HTMLElement && row.getBoundingClientRect().top >= bottom) {
      el.scrollTop += row.getBoundingClientRect().bottom - bottom;
      requestAnimationFrame(() => scroller.current?.scrollTo?.({ top: 0, behavior: "smooth" }));
    } else {
      el.scrollTo?.({ top: 0, behavior: "smooth" });
    }
  }, []);

  // [#919] デッキのカラムなら、1 カラム表示の「ホーム」から最新にいるかを聞かれ、最新へ戻せるようにする
  const columnId = useKbColumnId();
  useEffect(() => {
    if (columnId === null) return;
    return registerColumnScroll(columnId, {
      atTop: () => follow.current.atLatest,
      scrollToTop: scrollToLatest,
    });
  }, [columnId, scrollToLatest]);

  // [#798] 強調する発言。届いたらその位置へ送り、そこで強調を点ける（CSS のアニメーションで消える）。
  // 最初の EOSE までは後から届く発言で位置がずれるので、読み込みが終わったところでもう 1 度だけ送る
  const hasTarget = highlightMessageId !== null && byId.has(highlightMessageId);
  const [flashId, setFlashId] = useState<string | null>(null);
  const jumped = useRef({ any: false, loaded: false });
  useLayoutEffect(() => {
    if (!hasTarget || highlightMessageId === null) return;
    if (loading ? jumped.current.any : jumped.current.loaded) return;
    jumped.current = { any: true, loaded: !loading };
    scroller.current
      ?.querySelector(`[data-message-id="${highlightMessageId}"]`)
      ?.scrollIntoView?.({ block: "center" });
    setFlashId(highlightMessageId);
  }, [hasTarget, loading, highlightMessageId]);

  // messages は新しい順。連投の「頭」は見た目の上側（古い側）
  const rows = messages.map((message, i) => {
    const neighbor = messages[i + 1];
    const continuation =
      neighbor !== undefined &&
      neighbor.pubkey === message.pubkey &&
      Math.abs(neighbor.created_at - message.created_at) < CONTINUATION_SEC;
    const parentId = replyParentIdOf(message);
    return (
      <MessageRow
        key={message.id}
        message={message}
        mine={message.pubkey === me}
        continuation={continuation}
        parent={parentId === null ? undefined : byId.get(parentId)}
        reactions={reactions.get(message.id)}
        highlighted={message.id === flashId}
        onReply={() => setReplyTo(message)}
      />
    );
  });

  return (
    <section className={styles.root} aria-label={title} aria-busy={loading} onFocus={onComposerFocus}>
      {header}
      <div className={styles.body}>
        <div ref={scroller} className={styles.scroller} onScroll={onScroll}>
          {messages.length === 0 ? (
            <p className={styles.empty}>{loading ? t("loading") : t("web_chat_empty")}</p>
          ) : (
            rows
          )}
        </div>
        <NewItemsPill count={newCount} scrolledAway={scrolledAway} atBottom onClick={scrollToLatest} />
      </div>
      <ChatComposer
        channelId={channelId}
        channelRelays={channelRelays}
        replyTo={replyTo}
        onCancelReply={() => setReplyTo(null)}
        onSent={() => setReplyTo(null)}
      />
    </section>
  );
}

/**
 * メッセージ画面のルームのヘッダ（チャンネルの画像・名前・説明）。onBack があれば（Compact）「←」、
 * onClose があれば（Expanded）ヘッダ右端に「✕」を出す（ネイティブ ColumnChrome の onClose。#600）。
 * [#856] ログイン中は ⋯ に「参加する／参加をやめる」（ネイティブ HeaderOverflowButton。デッキのカラムは ColumnMenu 側）。
 */
export function RoomHeader({
  channelId,
  title,
  subtitle,
  picture,
  onBack,
  onClose,
}: {
  channelId: string;
  title: string;
  subtitle: string;
  picture: string | null;
  onBack?: () => void;
  onClose?: () => void;
}) {
  const t = useT();
  const joinAction = useChannelJoin(channelId);
  return (
    <header className={styles.header}>
      {onBack && (
        <button type="button" className={styles.back} aria-label={t("common_back")} onClick={onBack}>
          <ArrowBackIcon className={styles.backIcon} />
        </button>
      )}
      <ChannelIcon key={picture} name={title} url={picture} />
      <span className={styles.headerTexts}>
        <h2 className={styles.headerTitle}>{title}</h2>
        {subtitle !== "" && <span className={styles.headerSubtitle}>{subtitle}</span>}
      </span>
      {joinAction && (
        <MenuButton
          label={t("col_menu")}
          triggerClassName={`${styles.back} ${styles.more}`}
          entries={[{ type: "item", label: joinAction.label, onSelect: joinAction.onSelect }]}
        >
          <MoreHorizIcon className={styles.backIcon} />
        </MenuButton>
      )}
      {onClose && (
        <button type="button" className={styles.back} aria-label={t("web_chat_deselect")} onClick={onClose}>
          <CloseIcon className={styles.backIcon} />
        </button>
      )}
    </header>
  );
}

function MessageRow({
  message,
  mine,
  continuation,
  parent,
  reactions,
  highlighted,
  onReply,
}: {
  message: NostrEvent;
  mine: boolean;
  continuation: boolean;
  parent: NostrEvent | undefined;
  reactions: ReactionGroup[] | undefined;
  highlighted: boolean;
  onReply(): void;
}) {
  const t = useT();
  const profile = useProfile(message.pubkey);
  const picture = pictureOf(profile);
  const name = displayName(profile, message.pubkey);
  const now = useNow();
  const timeDisplay = useTimeDisplay();
  const media = extractMedia(message);
  const hasMedia = media.images.length + media.videos.length + media.youtube.length > 0;
  const unsent = useIsUnsent(message.id);
  return (
    <article
      className={styles.message}
      data-message-id={message.id}
      data-continuation={continuation || undefined}
      data-highlight={highlighted || undefined}
    >
      <span className={styles.avatarSlot}>
        {!continuation && (
          <Avatar key={picture} url={picture} size="md" seed={name} pubkey={message.pubkey} />
        )}
      </span>
      <div className={styles.main}>
        {!continuation && (
          <div className={styles.meta}>
            <span className={styles.name}>{name}</span>
            <span className={styles.time}>{timeLabel(message.created_at, now, timeDisplay)}</span>
          </div>
        )}
        {parent && <ReplyQuote parent={parent} />}
        <div className={styles.bubbleRow}>
          <div className={mine ? `${styles.bubble} ${styles.mine}` : styles.bubble}>
            <NoteContent event={message} />
            {hasMedia && <NoteMedia media={media} content={message.content} />}
          </div>
          <MessageActions message={message} mine={mine} onReply={onReply} />
        </div>
        {reactions && reactions.length > 0 && <ReactionChips groups={reactions} />}
        {mine && unsent && (
          <button type="button" className={styles.unsent} onClick={() => retryUnsentNow(message.id)}>
            {t("unsent_tap_retry")}
          </button>
        )}
      </div>
    </article>
  );
}

/**
 * 返信元の 1 行「↩ (アバター) 名前: 本文」（ネイティブ ReplyQuote → ReplyContextLine）。
 * DM（features/dm/ConversationView）とも共有する部品（#589）。
 */
export function ReplyQuote({ parent }: { parent: NostrEvent }) {
  const profile = useProfile(parent.pubkey);
  const picture = pictureOf(profile);
  return (
    <p className={styles.quote}>
      <ReplyIcon className={styles.quoteIcon} />
      <Avatar key={picture} url={picture} size="sm" seed={parent.pubkey} pubkey={parent.pubkey} />
      <span className={styles.quoteText}>
        {`${displayName(profile, parent.pubkey)}: ${oneLine(plainTextOf(parent))}`}
      </span>
    </p>
  );
}

/** 集約したリアクション（絵文字 + 件数。ネイティブ ReactionRow） */
function ReactionChips({ groups }: { groups: readonly ReactionGroup[] }) {
  const t = useT();
  return (
    <ul className={styles.reactions} aria-label={t("note_kind_reaction")}>
      {groups.map((g) => (
        <li
          key={`${g.display}\n${g.imageUrl ?? ""}`}
          className={styles.reaction}
          aria-label={`${g.display} ${g.count}`}
        >
          {g.imageUrl !== null && /^https:\/\//i.test(g.imageUrl) ? (
            <ReactionImage key={g.imageUrl} url={g.imageUrl} text={g.display} />
          ) : (
            <span aria-hidden="true">{g.display}</span>
          )}
          <span className={styles.reactionCount} aria-hidden="true">
            {g.count}
          </span>
        </li>
      ))}
    </ul>
  );
}

/** カスタム絵文字の画像。プロキシが読めなければ元 URL で 1 度だけ取り直し、それも読めなければ :code: の文字 */
function ReactionImage({ url, text }: { url: string; text: string }) {
  const [src, setSrc] = useState<string | null>(() => proxiedEmoji(url, 80));
  if (!src) return <span aria-hidden="true">{text}</span>;

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
      className={styles.reactionImage}
      src={src}
      alt=""
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}

/** ミュート・解除の失敗の文言（NoteActionButtons と同じ） */
function muteFailureMessage(e: unknown): string {
  if (e instanceof MuteListError && e.reason === "no-mute-list") {
    return t("web_mute_no_base");
  }
  if (e instanceof MuteListError && e.reason === "no-cipher") {
    return t("web_mute_no_cipher");
  }
  return t("note_mute_locked");
}

function warn(message: string) {
  return (e: unknown) => console.warn(`[chat] ${message}`, e);
}

/**
 * 吹き出しの横の常設アクション（ネイティブ MessageActions）: リプライ → 既定リアクション → 絵文字 → ⚡ → ⋯
 * （テキストをコピー・このユーザーをミュート・通報、開発者モード中は末尾に「イベントJSONを表示」。CH3）。
 * [#538] ⚡ は発言者の kind:0 に lud16 があるときだけ。
 */
function MessageActions({ message, mine, onReply }: { message: NostrEvent; mine: boolean; onReply(): void }) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const author = useProfile(message.pubkey);
  const lud16 = typeof author?.lud16 === "string" ? author.lud16.trim() : "";
  const content = useDefaultReaction((s) => s.content);
  const isStar = content === "⭐" || content === "★";
  const reacted = useIsReacted(message.id);
  const isMuted = useMuteMatcher().users.has(message.pubkey);
  const developerMode = useDeveloperMode((s) => s.enabled);
  const [dialog, setDialog] = useState<"picker" | "unreact" | "mute" | "report" | "zap" | "json" | null>(
    null,
  );
  // [#537] ウォレット接続（NWC）済みなら Zap ダイアログの受け口へアプリ内送金を渡す
  const walletConnected = useNwc((s) => s.connection !== null);
  const Glyph = isStar ? (reacted ? StarIcon : StarBorderIcon) : reacted ? FavoriteIcon : FavoriteBorderIcon;

  function mute(action: "mute" | "unmute") {
    if (!me) return;
    const run = action === "mute" ? muteUser(me, message.pubkey) : unmuteUser(me, message.pubkey);
    run.then(
      () => showToast(action === "mute" ? t("muted_toast") : t("note_unmuted_toast")),
      (e) => showToast(muteFailureMessage(e)),
    );
  }

  const entries: MenuEntry[] = [
    { type: "item", label: t("note_copy_text"), onSelect: () => void copyText(plainTextOf(message)) },
  ];
  if (!mine) {
    entries.push(
      isMuted
        ? { type: "item", label: t("note_unmute_user"), onSelect: () => mute("unmute") }
        : { type: "item", label: t("note_mute_user"), onSelect: () => setDialog("mute") },
      { type: "item", label: t("note_report"), onSelect: () => setDialog("report"), tone: "danger" },
    );
  }
  if (developerMode) {
    entries.push(
      { type: "separator" },
      { type: "item", label: t("note_view_json"), onSelect: () => setDialog("json") },
    );
  }

  return (
    <div className={styles.actions}>
      <button
        type="button"
        className={styles.action}
        aria-label={t("chat_reply")}
        title={t("chat_reply")}
        onClick={onReply}
      >
        <ReplyIcon className={styles.actionIcon} />
      </button>
      <button
        type="button"
        className={styles.action}
        aria-label={t("note_kind_reaction")}
        aria-pressed={reacted}
        data-shape={isStar ? "star" : "heart"}
        onClick={() => {
          if (reacted) setDialog("unreact");
          else reactWithDefault(message).catch(warn("Failed to react"));
        }}
      >
        <Glyph className={styles.actionIcon} />
      </button>
      <button
        type="button"
        className={styles.action}
        aria-label={t("web_emoji_reaction")}
        onClick={() => setDialog("picker")}
      >
        <AddReactionIcon className={styles.actionIcon} />
      </button>
      {lud16 !== "" && (
        <button
          type="button"
          className={styles.action}
          aria-label="Zap"
          title="Zap"
          onClick={() => setDialog("zap")}
        >
          <BoltIcon className={styles.actionIcon} />
        </button>
      )}
      <MenuButton label={t("web_more_actions")} triggerClassName={styles.action} entries={entries}>
        <MoreHorizIcon className={styles.actionIcon} />
      </MenuButton>
      {dialog === "zap" && (
        <ZapDialog
          recipient={message.pubkey}
          recipientName={displayName(author, message.pubkey)}
          lud16={lud16}
          eventId={message.id}
          targetKind={message.kind}
          payWithWallet={walletConnected ? payInvoiceWithNwc : undefined}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === "picker" && (
        <ReactionPickerDialog
          target={message}
          onPick={(c, url, made) =>
            void publishReaction(message, c, url)
              .then(() => showToast(reactionSentMessage(c, made)), warn("Failed to react"))
              // [#768] 「自分の絵文字リストにも保存」は送信の後に（成否はリアクションとは別のトースト）
              .then(() => saveMadeEmoji(c, url, made))
          }
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === "unreact" && (
        <ConfirmDialog
          title={t("unreact_title")}
          text={t("unreact_text")}
          confirmLabel={t("unreact_confirm")}
          destructive
          onConfirm={() => {
            setDialog(null);
            reactWithDefault(message).catch(warn("Failed to undo the reaction"));
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "mute" && (
        <ConfirmDialog
          title={t("mute_confirm_title")}
          text={t("mute_confirm_text")}
          confirmLabel={t("mute_confirm")}
          destructive
          onConfirm={() => {
            setDialog(null);
            mute("mute");
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "report" && (
        <ReportDialog
          onPick={(type) => {
            setDialog(null);
            reportNote(message, type).catch(warn("Failed to report"));
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "json" && <EventJsonDialog event={message} onDismiss={() => setDialog(null)} />}
    </div>
  );
}
