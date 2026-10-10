import { npubEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { type ReactNode, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router";
import type { DmMessageRow } from "../../db/schema";
import { t, useT } from "../../i18n";
import { hrefForProfile } from "../../lib/content/labels";
import { extractMedia } from "../../lib/media";
import { shortNpub } from "../../lib/npub";
import { formatAbsoluteTime } from "../../lib/time";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { retryUnsentNow, useIsUnsent } from "../../nostr/publish";
import { currentSigner } from "../../signer/session";
import { ArrowBackIcon, CloseIcon, ImageIcon, PlayArrowIcon, ReplyIcon, SendIcon } from "../../ui/icons";
import { showToast } from "../../ui/toast";
import { EmojiInsertButton } from "../actions/EmojiInsertButton";
import { ReplyQuote } from "../chat/ChannelRoom";
import { ReplyingTo } from "../chat/ChatComposer";
import { replyParentIdOf } from "../chat/chatMessage";
import {
  type Attachment,
  createAttachment,
  humanSize,
  reprocessImages,
  UploadFailedError,
  uploadAttachments,
} from "../compose/attachments";
import {
  activeEmoji,
  activeMention,
  completeMention,
  insertAtCursor,
  insertEmojiShortcode,
  type TextState,
} from "../compose/completion";
import { type CustomEmoji, useCustomEmojis } from "../compose/customEmojis";
import {
  type ImageResolution,
  maxDimFor,
  resolutions,
  useImageCompression,
} from "../compose/imageCompression";
import { uploadServers, useMediaServer } from "../compose/mediaServer";
import { ProfileAvatar } from "../compose/ProfileAvatar";
import { type ProfileHit, searchProfiles } from "../compose/searchProfiles";
import { NoteMedia } from "../media/NoteMedia";
import { NoteContent } from "../timeline/NoteContent";
import { Avatar } from "../timeline/NoteItem";
import styles from "./ConversationView.module.css";
import { markSeen } from "./dmSeen";
import { useConversations, useDm, useMessagesWith } from "./dmStore";
import { type DmSendResult, sendDm } from "./send";

/** 1 度に出す件数（新しい方から。古いものは「さらに表示」で足す） */
const PAGE_SIZE = 200;
/** 同じ送り手でこの秒数未満の連投は名前・アバターを省く（ネイティブ continuation） */
const CONTINUATION_SEC = 300;

/**
 * DmMessageRow → kind:14 相当の NostrEvent（本文の描画・返信元の受け渡し用。EventStore には入れない。#589）。
 */
function dmEvent(message: DmMessageRow): NostrEvent {
  return {
    id: message.id,
    pubkey: message.sender,
    kind: 14,
    created_at: message.createdAt,
    content: message.content,
    tags: message.tags,
    sig: "",
  };
}

/**
 * 相手との会話（ネイティブ DmScreen の会話側）。最新が下。
 * スクロール領域は column-reverse で下端に揃える（DOM は新しい順。ネイティブの reverseLayout と同じで、
 * 読み込み後に最下部へ飛ばす処理は書かない）。onBack があれば（Compact）「←」、onClose があれば（Expanded）
 * ヘッダ右端に「✕」を出す（ネイティブ ColumnChrome の onClose。#600）。
 */
export function ConversationView({
  peer,
  onBack,
  onClose,
}: {
  peer: string;
  onBack?: () => void;
  onClose?: () => void;
}) {
  const t = useT();
  const me = useDm((s) => s.owner);
  const messages = useMessagesWith(peer);
  const conversation = useConversations().find((c) => c.peer === peer);
  const unread = conversation?.unread ?? 0;
  const lastIncomingAt = conversation?.lastIncomingAt ?? 0;
  // 開いている会話は既読にする（開いたとき・開いている間の新着。未読が 0 なら何もしない。ネイティブ #416）
  useEffect(() => {
    if (me !== null && unread > 0) markSeen(me, peer, lastIncomingAt);
  }, [me, peer, unread, lastIncomingAt]);
  const [limit, setLimit] = useState(PAGE_SIZE);
  // 返信中のメッセージ（#589）。会話を切り替えたら持ち越さない
  const [replyTo, setReplyTo] = useState<NostrEvent | null>(null);
  // biome-ignore lint/correctness/useExhaustiveDependencies: peer は「会話が変わったら」のキーとして見るだけ
  useEffect(() => setReplyTo(null), [peer]);
  const profile = useProfile(peer);
  const picture = pictureOf(profile);
  const name = displayName(profile, peer);
  // 副題は nip05（無ければ npub 短縮。ネイティブ DmScreen.kt の handle と同じ。DM3）
  const nip05 =
    typeof profile?.nip05 === "string" && profile.nip05.trim() !== "" ? profile.nip05.trim() : null;

  const byId = useMemo(() => new Map(messages.map((m) => [m.id, m])), [messages]);
  const start = Math.max(0, messages.length - limit);
  // DOM は新しい順（column-reverse で下から積む）
  const rows: ReactNode[] = [];
  for (let i = messages.length - 1; i >= start; i--) {
    const message = messages[i];
    const prev = i > 0 ? messages[i - 1] : undefined;
    const continuation =
      prev !== undefined &&
      prev.sender === message.sender &&
      message.createdAt - prev.createdAt < CONTINUATION_SEC;
    // 返信元（#e の reply マーカー）。手元に無ければ引用は出さない（#589）
    const parentId = replyParentIdOf(dmEvent(message));
    rows.push(
      <Bubble
        key={message.id}
        message={message}
        mine={message.sender === me}
        continuation={continuation}
        parent={parentId ? byId.get(parentId) : undefined}
        onReply={() => setReplyTo(dmEvent(message))}
      />,
    );
  }

  return (
    <section className={styles.root} aria-label={name}>
      <header className={styles.header}>
        {onBack && (
          <button type="button" className={styles.back} aria-label={t("common_back")} onClick={onBack}>
            <ArrowBackIcon className={styles.backIcon} />
          </button>
        )}
        <Link className={styles.peer} to={hrefForProfile(peer)}>
          <Avatar key={picture} url={picture} size="lg" seed={name} pubkey={peer} />
          <span className={styles.peerTexts}>
            <h2 className={styles.peerName}>{name}</h2>
            <span className={styles.peerNpub}>{nip05 ?? shortNpub(peer)}</span>
          </span>
        </Link>
        {onClose && (
          <button type="button" className={styles.back} aria-label={t("web_chat_deselect")} onClick={onClose}>
            <CloseIcon className={styles.backIcon} />
          </button>
        )}
      </header>
      <div className={styles.scroller}>
        {rows}
        {start > 0 && (
          <button type="button" className={styles.more} onClick={() => setLimit((n) => n + PAGE_SIZE)}>
            {t("web_dm_show_more")}
          </button>
        )}
      </div>
      <Composer
        peer={peer}
        replyTo={replyTo}
        onCancelReply={() => setReplyTo(null)}
        onSent={() => setReplyTo(null)}
      />
    </section>
  );
}

/** 送信結果ごとのトースト（ネイティブ ja リソースと同じ文言。sent / sent-no-peer-relays は別扱い） */
const sendFailures = (): Record<Exclude<DmSendResult, "sent" | "sent-no-peer-relays">, string> => ({
  failed: t("dm_send_failed"),
  "no-nip44": t("web_dm_no_nip44"),
  "no-nip04": t("web_dm_no_nip04"),
  "no-relays": t("web_dm_no_relays"),
});
/** 絵文字候補の件数（ComposeDialog と同じ） */
const EMOJI_SUGGEST_MAX = 12;
/** 連続入力中はメンションを探さない（ComposeDialog と同じ 120ms） */
const MENTION_DELAY_MS = 120;

/** 「届かない可能性があります」を出した相手（セッション中 1 回まで） */
const warnedNoPeerRelays = new Set<string>();

/**
 * 会話の入力欄（ネイティブ DmScreen の入力行 + ChannelRoomColumn の Composer と同じ補完・添付）。
 * 並びは 絵文字 → 入力欄 → 添付（画像・動画共通の 1 ボタン）→ 送信（丸いアイコンボタン）で
 * ChatComposer と統一（DM4）。Enter は改行、Ctrl / Cmd + Enter で送信（IME 変換中は送らない）。
 * カーソル直前の ":…" でカスタム絵文字、"@…" でメンションを補完し、絵文字ボタンでカーソル位置に挿入する
 * （ComposeDialog / completion.ts と共通）。
 * 画像は選んだ時点で圧縮を始め、添付中は解像度チップ（低/中/高）で選び直せる（挙動4.4）。
 * 送信時にアップロードして URL を本文の末尾に改行でつなぐ（ネイティブ Composer の onSend と同じ）。
 * 1 件でも失敗したら送らず chat_upload_failed。
 * 送信中は入力と送信を止める。送れたら空にし、送れなければ入力・添付を残してトースト
 */
function Composer({
  peer,
  replyTo,
  onCancelReply,
  onSent,
}: {
  peer: string;
  replyTo: NostrEvent | null;
  onCancelReply(): void;
  onSent(): void;
}) {
  const t = useT();
  const nip17 = useDm((s) => s.nip17);
  const nip04 = useDm((s) => s.nip04);
  const me = useDm((s) => s.owner);
  const [value, setValue] = useState<TextState>({ text: "", cursor: 0 });
  const [sending, setSending] = useState(false);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [mentionHits, setMentionHits] = useState<ProfileHit[]>([]);
  const emojis = useCustomEmojis(me);
  const mediaServer = useMediaServer((s) => s.server);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  // コードで本文を変えたら、描画後にカーソルを合わせて本文へフォーカスを戻す
  const moveCursor = useRef(false);
  // まだ revoke していないプレビューの blob: URL（会話を離れたらまとめて revoke する）
  const previews = useRef(new Set<string>());
  // 添付画像の解像度（ネイティブ ChannelRoomColumn の Composer と同じ既定「中」。DM4 / 挙動4.4）
  const [resolution, setResolution] = useState<ImageResolution>("mid");
  const compressionPrefs = useImageCompression((s) => s.prefs);
  const attachmentsRef = useRef(attachments);
  useLayoutEffect(() => {
    attachmentsRef.current = attachments;
  });
  useEffect(() => {
    const maxDim = maxDimFor(resolution, compressionPrefs);
    const list = attachmentsRef.current;
    if (!list.some((a) => a.kind === "image")) return;
    setAttachments(reprocessImages(list, maxDim, compressionPrefs.quality));
  }, [resolution, compressionPrefs]);

  // 返信を選んだら入力欄へ（ネイティブ・ChatComposer と同じ #589）
  useEffect(() => {
    if (replyTo) textarea.current?.focus();
  }, [replyTo]);

  useEffect(() => {
    const urls = previews.current;
    return () => {
      for (const url of urls) URL.revokeObjectURL(url);
      urls.clear();
    };
  }, []);

  useLayoutEffect(() => {
    const el = textarea.current;
    if (!el || !moveCursor.current) return;
    moveCursor.current = false;
    el.focus();
    el.setSelectionRange(value.cursor, value.cursor);
  }, [value]);

  function update(next: TextState, fromCode: boolean) {
    if (fromCode) moveCursor.current = true;
    setValue(next);
  }

  // ---- 入力補完（カーソル直前のトークン。絵文字 > メンションの 1 種だけ出す） ----
  const before = value.text.slice(0, value.cursor);
  const emojiFrag = activeEmoji(before);
  const emojiHits = useMemo(() => {
    if (emojiFrag === null) return [];
    const frag = emojiFrag.toLowerCase();
    return emojis.filter((e) => e.shortcode.toLowerCase().startsWith(frag)).slice(0, EMOJI_SUGGEST_MAX);
  }, [emojis, emojiFrag]);
  const mentionFrag = activeMention(before);
  useEffect(() => {
    if (mentionFrag === null) {
      setMentionHits([]);
      return;
    }
    const timer = setTimeout(() => setMentionHits(searchProfiles(mentionFrag)), MENTION_DELAY_MS);
    return () => clearTimeout(timer);
  }, [mentionFrag]);

  // ---- 添付（画像・動画。選んだ時点で圧縮を始め、アップロードは送信時。ComposeDialog と同じ既定の圧縮） ----
  function addFiles(files: Iterable<File>) {
    if (sending) return;
    const added: Attachment[] = [];
    for (const file of files) {
      const attachment = createAttachment(file);
      if (attachment) added.push(attachment);
    }
    if (added.length === 0) return;
    for (const attachment of added) previews.current.add(attachment.preview);
    setAttachments((list) => [...list, ...added]);
  }

  function removeAttachment(target: Attachment) {
    URL.revokeObjectURL(target.preview);
    previews.current.delete(target.preview);
    setAttachments((list) => list.filter((a) => a.id !== target.id));
  }

  const canSend = !sending && (value.text.trim() !== "" || attachments.length > 0);

  async function send() {
    if (!canSend) return;
    setSending(true);
    const list = attachments;
    let text = value.text.trim();
    if (list.length > 0) {
      const signer = currentSigner();
      if (!signer) {
        setSending(false);
        showToast(t("chat_upload_failed"));
        return;
      }
      try {
        const media = await uploadAttachments(list, { servers: uploadServers(mediaServer), signer });
        // 本文の末尾に URL を改行でつなぐ（空の本文なら URL だけ。ネイティブ Composer の onSend と同じ）
        text = [text, ...media.map((m) => m.url)].filter((s) => s !== "").join("\n");
      } catch (e) {
        setSending(false);
        showToast(e instanceof UploadFailedError ? t("chat_upload_failed") : sendFailures().failed);
        return;
      }
    }
    const result = await sendDm(peer, text, replyTo);
    setSending(false);
    if (result === "sent" || result === "sent-no-peer-relays") {
      for (const a of list) URL.revokeObjectURL(a.preview);
      previews.current.clear();
      setAttachments([]);
      update({ text: "", cursor: 0 }, true);
      onSent();
      if (result === "sent-no-peer-relays" && !warnedNoPeerRelays.has(peer)) {
        warnedNoPeerRelays.add(peer);
        showToast(t("dm_no_relays_warn"));
      }
      return;
    }
    showToast(sendFailures()[result]);
  }

  if (nip17 === "no-nip44" && nip04 === "no-nip04") {
    return <p className={styles.cannotSend}>{t("web_dm_cannot_send")}</p>;
  }
  return (
    <div className={styles.composerColumn}>
      {replyTo && (
        <div className={styles.replying}>
          <ReplyingTo target={replyTo} onCancel={onCancelReply} />
        </div>
      )}
      {emojiHits.length > 0 ? (
        <div className={styles.chips}>
          {emojiHits.map((emoji) => (
            <EmojiChip
              key={emoji.shortcode}
              emoji={emoji}
              onClick={() => update(insertEmojiShortcode(value, emoji.shortcode), true)}
            />
          ))}
        </div>
      ) : (
        mentionHits.length > 0 && (
          <div className={styles.mentions}>
            {mentionHits.map((hit) => (
              <button
                key={hit.pubkey}
                type="button"
                className={styles.mention}
                onClick={() => update(completeMention(value, npubEncode(hit.pubkey)), true)}
              >
                <ProfileAvatar pubkey={hit.pubkey} size={28} />
                <span className={styles.mentionText}>
                  <span className={styles.mentionName}>{hit.name || hit.handle}</span>
                  {hit.handle !== "" && <span className={styles.mentionHandle}>{hit.handle}</span>}
                </span>
              </button>
            ))}
          </div>
        )
      )}
      {attachments.length > 0 && (
        <AttachmentList attachments={attachments} removable={!sending} onRemove={removeAttachment} />
      )}
      {attachments.some((a) => a.kind === "image") && (
        <fieldset className={styles.resolutionRow}>
          <legend className={styles.resolutionLegend}>{t("compose_resolution")}</legend>
          <div className={styles.resolutionGroup}>
            {resolutions().map(([value, label]) => (
              <button
                key={value}
                type="button"
                className={styles.resolutionButton}
                aria-pressed={resolution === value}
                onClick={() => setResolution(value)}
              >
                {label}
              </button>
            ))}
          </div>
        </fieldset>
      )}
      {/* 絵文字 → 入力欄 → 添付 → 送信（ネイティブ ChannelRoomColumn の Composer・ChatComposer と同じ並び。DM4） */}
      <div className={styles.composer}>
        <EmojiInsertButton onInsert={(str) => update(insertAtCursor(value, str), true)} />
        <textarea
          ref={textarea}
          className={styles.input}
          aria-label={t("dm_title")}
          rows={1}
          value={value.text}
          disabled={sending}
          onChange={(e) => {
            const el = e.currentTarget;
            update({ text: el.value, cursor: el.selectionStart ?? el.value.length }, false);
          }}
          onSelect={(e) => {
            const cursor = e.currentTarget.selectionStart;
            setValue((v) => (v.cursor === cursor ? v : { ...v, cursor }));
          }}
          onKeyDown={(e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === "Enter" && !e.nativeEvent.isComposing) {
              e.preventDefault();
              void send();
            }
          }}
        />
        <button
          type="button"
          className={styles.attachButton}
          aria-label={t("web_chat_attach_media")}
          disabled={sending}
          onClick={() => fileInput.current?.click()}
        >
          <ImageIcon className={styles.attachIcon} />
        </button>
        <input
          ref={fileInput}
          type="file"
          accept="image/*,video/*"
          multiple
          hidden
          onChange={(e) => {
            const input = e.currentTarget;
            addFiles(Array.from(input.files ?? []));
            input.value = "";
          }}
        />
        <button
          type="button"
          className={styles.send}
          aria-label={t("send")}
          aria-busy={sending || undefined}
          disabled={!canSend}
          onClick={() => void send()}
        >
          {sending ? (
            <span className={styles.spinner} aria-hidden="true" />
          ) : (
            <SendIcon className={styles.sendIcon} />
          )}
        </button>
      </div>
    </div>
  );
}

/** 絵文字候補のチップ（画像 + :code:。ComposeDialog の EmojiChip と同じ） */
function EmojiChip({ emoji, onClick }: { emoji: CustomEmoji; onClick(): void }) {
  return (
    <button type="button" className={styles.chip} aria-label={`:${emoji.shortcode}:`} onClick={onClick}>
      <img className={styles.chipEmoji} src={emoji.url} alt="" loading="lazy" decoding="async" />:
      {emoji.shortcode}:
    </button>
  );
}

/**
 * 添付の一覧（ComposeDialog の AttachmentList と同じ体裁。画像 → 動画の順。解像度選択は無し）。
 */
function AttachmentList({
  attachments,
  removable,
  onRemove,
}: {
  attachments: readonly Attachment[];
  removable: boolean;
  onRemove(attachment: Attachment): void;
}) {
  const t = useT();
  const ordered = [
    ...attachments.filter((a) => a.kind === "image"),
    ...attachments.filter((a) => a.kind === "video"),
  ];
  return (
    <ul className={styles.attachments} aria-label={t("web_chat_attachments")}>
      {ordered.map((a) => (
        <li key={a.id} className={styles.attachment}>
          <div className={styles.thumb}>
            {a.kind === "image" ? (
              <img
                className={styles.thumbMedia}
                src={a.preview}
                alt={t("compose_attachment")}
                decoding="async"
              />
            ) : (
              <>
                <video
                  className={styles.thumbMedia}
                  src={a.preview}
                  aria-label={t("web_chat_attachment_video")}
                  muted
                  playsInline
                  preload="metadata"
                />
                <PlayArrowIcon className={styles.thumbPlay} />
              </>
            )}
            {removable && (
              <button
                type="button"
                className={styles.remove}
                aria-label={t("chat_attach_remove")}
                onClick={() => onRemove(a)}
              >
                <CloseIcon className={styles.removeIcon} />
              </button>
            )}
          </div>
          <span className={styles.size}>{humanSize(a.file.size)}</span>
        </li>
      ))}
    </ul>
  );
}

/**
 * 吹き出し。自分 = 右寄せ、相手 = 左寄せ + アバター（連投は省く）。
 * [parent] は返信元（無ければ引用は出さない）、[onReply] は返信ボタン（ネイティブ MessageActions。#589）。
 */
function Bubble({
  message,
  mine,
  continuation,
  parent,
  onReply,
}: {
  message: DmMessageRow;
  mine: boolean;
  continuation: boolean;
  parent: DmMessageRow | undefined;
  onReply(): void;
}) {
  const t = useT();
  // 本文の描画用に kind:14 のイベントの形にする（EventStore には入れない）
  const event = useMemo<NostrEvent>(() => dmEvent(message), [message]);
  const media = extractMedia(event);
  const hasMedia = media.images.length + media.videos.length + media.youtube.length > 0;
  const showSender = !mine && !continuation;
  const unsent = useIsUnsent(message.id);
  return (
    <div className={mine ? styles.mine : styles.theirs} data-continuation={continuation || undefined}>
      {!mine &&
        (showSender ? <SenderAvatar pubkey={message.sender} /> : <span className={styles.avatarGap} />)}
      <div className={styles.column}>
        {showSender && <SenderName pubkey={message.sender} />}
        {parent && <ReplyQuote parent={dmEvent(parent)} />}
        <div className={styles.bubbleRow}>
          {mine && <ReplyButton onClick={onReply} />}
          <div className={styles.bubble}>
            <NoteContent event={event} />
            {hasMedia && <NoteMedia media={media} />}
          </div>
          {!mine && <ReplyButton onClick={onReply} />}
        </div>
        <span className={styles.time}>{formatAbsoluteTime(message.createdAt)}</span>
        {mine && unsent && (
          // 暗号文しか残っていないので「下書きに戻す」は出さない（ネイティブ ChannelRoomColumn の未送信）
          <button type="button" className={styles.unsent} onClick={() => retryUnsentNow(message.id)}>
            {t("unsent_tap_retry")}
          </button>
        )}
      </div>
    </div>
  );
}

/** 吹き出し横のリプライボタン（ネイティブ MessageActions の Reply アイコン。DM はリアクション・Zap 無し） */
function ReplyButton({ onClick }: { onClick(): void }) {
  const t = useT();
  return (
    <button
      type="button"
      className={styles.action}
      aria-label={t("chat_reply")}
      title={t("chat_reply")}
      onClick={onClick}
    >
      <ReplyIcon className={styles.actionIcon} />
    </button>
  );
}

function SenderAvatar({ pubkey }: { pubkey: string }) {
  const profile = useProfile(pubkey);
  const picture = pictureOf(profile);
  return (
    <span className={styles.avatar}>
      <Avatar key={picture} url={picture} size="md" seed={displayName(profile, pubkey)} pubkey={pubkey} />
    </span>
  );
}

function SenderName({ pubkey }: { pubkey: string }) {
  return <span className={styles.sender}>{displayName(useProfile(pubkey), pubkey)}</span>;
}
