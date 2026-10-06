import type { NostrEvent } from "nostr-tools/pure";
import {
  type FocusEvent,
  type ReactNode,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { t, useT } from "../../i18n";
import { oneLine } from "../../lib/content/labels";
import { markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { extractMedia } from "../../lib/media";
import { relativeTime } from "../../lib/time";
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
import { useVisualViewportHeight } from "../../ui/useVisualViewportHeight";
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
import { NoteMedia } from "../media/NoteMedia";
import { useMuteMatcher } from "../mute/muteList";
import { MuteListError, muteUser, unmuteUser } from "../mute/muteSync";
import { useDeveloperMode } from "../settings/devMode";
import type { ReactionGroup } from "../thread/engagement";
import { NoteContent } from "../timeline/NoteContent";
import { Avatar } from "../timeline/NoteItem";
import { useNow } from "../timeline/useNow";
import { payInvoiceWithNwc, useNwc } from "../wallet/nwcManager";
import { ZapDialog } from "../zap/ZapDialog";
import { ChannelIcon } from "./ChannelList";
import styles from "./ChannelRoom.module.css";
import { ChatComposer } from "./ChatComposer";
import { replyParentIdOf } from "./chatMessage";
import { useChannelRoom } from "./useChannelRoom";

/** 同じ送り手でこの秒数未満の連投は名前・アバターを省く（ネイティブ continuation） */
const CONTINUATION_SEC = 300;

/**
 * チャンネルのルーム（ネイティブ LiveChannelRoom + ChannelRoomColumn）。
 * screen = メッセージ画面: 最新が下（column-reverse。DOM は新しい順）・下端に常設の入力欄。
 * column = デッキのカラム: 最新が上・入力欄は置かず「✏️ メッセージを書く」でモーダル（ネイティブ deckMode）。
 * 吹き出しは全員左寄せ（自分も左。ネイティブ mineOnRight=false）。ミュートした人・ワードの発言は出さない（revealMuted なら出す）。
 */
export function ChannelRoom({
  channelId,
  title,
  mode,
  header,
  revealMuted = false,
}: {
  channelId: string;
  title: string;
  mode: "screen" | "column";
  header: ReactNode;
  revealMuted?: boolean;
}) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const { loading, messages, reactions, channelRelays } = useChannelRoom(channelId, revealMuted);
  const [replyTo, setReplyTo] = useState<NostrEvent | null>(null);
  const [composing, setComposing] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (me) ensureMyReactionsSubscribed(me);
  }, [me]);

  // [#594] 常設の入力欄（textarea）にフォーカスしたら最新のメッセージ（column-reverse の scrollTop 0）を見せる
  function onComposerFocus(e: FocusEvent<HTMLElement>) {
    if (mode !== "screen" || e.target.tagName !== "TEXTAREA" || !scroller.current) return;
    scroller.current.scrollTop = 0;
  }

  const byId = useMemo(() => new Map(messages.map((m) => [m.id, m])), [messages]);

  function reply(message: NostrEvent) {
    setReplyTo(message);
    if (mode === "column") setComposing(true);
  }

  // messages は新しい順。連投の「頭」は見た目の上側: screen = 古い側、column = 新しい側
  const rows = messages.map((message, i) => {
    const neighbor = mode === "screen" ? messages[i + 1] : messages[i - 1];
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
        onReply={() => reply(message)}
      />
    );
  });

  return (
    <section className={styles.root} aria-label={title} aria-busy={loading} onFocus={onComposerFocus}>
      {header}
      <div ref={scroller} className={mode === "screen" ? styles.chatScroller : styles.feedScroller}>
        {messages.length === 0 ? (
          <p className={styles.empty}>{loading ? t("loading") : t("web_chat_empty")}</p>
        ) : (
          rows
        )}
      </div>
      {mode === "screen" ? (
        <ChatComposer
          channelId={channelId}
          channelRelays={channelRelays}
          replyTo={replyTo}
          onCancelReply={() => setReplyTo(null)}
          onSent={() => setReplyTo(null)}
        />
      ) : (
        <div className={styles.writeBar}>
          <button
            type="button"
            className={styles.write}
            onClick={() => {
              setReplyTo(null);
              setComposing(true);
            }}
          >
            {t("chat_write_message")}
          </button>
        </div>
      )}
      {composing && (
        <ComposeModal
          title={title}
          onClose={() => {
            setComposing(false);
            setReplyTo(null);
          }}
        >
          <ChatComposer
            channelId={channelId}
            channelRelays={channelRelays}
            replyTo={replyTo}
            onCancelReply={() => setReplyTo(null)}
            onSent={() => {
              setComposing(false);
              setReplyTo(null);
            }}
            autoFocus
          />
        </ComposeModal>
      )}
    </section>
  );
}

/**
 * メッセージ画面のルームのヘッダ（チャンネルの画像・名前・説明）。onBack があれば（Compact）「←」、
 * onClose があれば（Expanded）ヘッダ右端に「✕」を出す（ネイティブ ColumnChrome の onClose。#600）。
 */
export function RoomHeader({
  title,
  subtitle,
  picture,
  onBack,
  onClose,
}: {
  title: string;
  subtitle: string;
  picture: string | null;
  onBack?: () => void;
  onClose?: () => void;
}) {
  const t = useT();
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
  onReply,
}: {
  message: NostrEvent;
  mine: boolean;
  continuation: boolean;
  parent: NostrEvent | undefined;
  reactions: ReactionGroup[] | undefined;
  onReply(): void;
}) {
  const t = useT();
  const profile = useProfile(message.pubkey);
  const picture = pictureOf(profile);
  const name = displayName(profile, message.pubkey);
  const now = useNow();
  const media = extractMedia(message);
  const hasMedia = media.images.length + media.videos.length + media.youtube.length > 0;
  const unsent = useIsUnsent(message.id);
  return (
    <article className={styles.message} data-continuation={continuation || undefined}>
      <span className={styles.avatarSlot}>
        {!continuation && (
          <Avatar key={picture} url={picture} size="md" seed={name} pubkey={message.pubkey} />
        )}
      </span>
      <div className={styles.main}>
        {!continuation && (
          <div className={styles.meta}>
            <span className={styles.name}>{name}</span>
            <span className={styles.time}>{relativeTime(message.created_at, now)}</span>
          </div>
        )}
        {parent && <ReplyQuote parent={parent} />}
        <div className={styles.bubbleRow}>
          <div className={mine ? `${styles.bubble} ${styles.mine}` : styles.bubble}>
            <NoteContent event={message} />
            {hasMedia && <NoteMedia media={media} />}
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
  const [src, setSrc] = useState<string | null>(() => proxied(url, 64, 80, true));
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
          else reactWithDefault(message).catch(warn(t("web_log_chat_react_failed")));
        }}
      >
        <Glyph className={styles.actionIcon} />
      </button>
      <button
        type="button"
        className={styles.action}
        aria-label={t("web_chat_react_emoji")}
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
      <MenuButton label={t("web_chat_more_actions")} triggerClassName={styles.action} entries={entries}>
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
              .then(() => showToast(reactionSentMessage(c, made)), warn(t("web_log_chat_react_failed")))
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
            reactWithDefault(message).catch(warn(t("web_log_chat_unreact_failed")));
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
            reportNote(message, type).catch(warn(t("web_log_chat_report_failed")));
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "json" && <EventJsonDialog event={message} onDismiss={() => setDialog(null)} />}
    </div>
  );
}

/**
 * デッキのカラムの投稿モーダル（ネイティブ ChannelRoomColumn の Dialog: チャンネル名 + 入力欄）。
 * 背景・Esc / 戻る・✕ で閉じる。
 */
function ComposeModal({ title, onClose, children }: { title: string; onClose(): void; children: ReactNode }) {
  const t = useT();
  const dialog = useRef<HTMLDialogElement>(null);
  const latestOnClose = useRef(onClose);
  useLayoutEffect(() => {
    latestOnClose.current = onClose;
  });

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);

  // ソフトキーボードが出たら見えている高さにカードを収める（ComposeDialog と同じ --compose-vvh。#594）
  useVisualViewportHeight(dialog, "--compose-vvh");

  // カードの外（dialog 自身 = 背景）の押下
  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    const onClick = (e: MouseEvent) => {
      if (e.target === d) latestOnClose.current();
    };
    d.addEventListener("click", onClick);
    return () => d.removeEventListener("click", onClick);
  }, []);

  return (
    <dialog
      ref={dialog}
      className={styles.modal}
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClose={() => onClose()}
    >
      <div className={styles.modalCard}>
        <div className={styles.modalHead}>
          <span className={styles.modalTitle}>{title}</span>
          <button
            type="button"
            className={styles.modalClose}
            aria-label={t("common_close")}
            onClick={onClose}
          >
            <CloseIcon className={styles.modalCloseIcon} />
          </button>
        </div>
        {children}
      </div>
    </dialog>
  );
}
