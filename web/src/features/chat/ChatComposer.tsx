import { npubEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { type MouseEvent, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useT } from "../../i18n";
import { oneLine } from "../../lib/content/labels";
import { displayName, useProfile } from "../../nostr/loaders";
import { currentSigner, useSession } from "../../signer/session";
import { CloseIcon, ImageIcon, PlayArrowIcon, ReplyIcon, SendIcon } from "../../ui/icons";
import { showToast } from "../../ui/toast";
import { EmojiInsertButton } from "../actions/EmojiInsertButton";
import { plainTextOf } from "../actions/noteLinks";
import {
  type Attachment,
  createAttachment,
  reprocessImages,
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
import { useCustomEmojis } from "../compose/customEmojis";
import {
  type ImageResolution,
  maxDimFor,
  resolutions,
  useImageCompression,
} from "../compose/imageCompression";
import { uploadServers, useMediaServer } from "../compose/mediaServer";
import { ProfileAvatar } from "../compose/ProfileAvatar";
import { type ProfileHit, searchProfiles } from "../compose/searchProfiles";
import styles from "./ChatComposer.module.css";
import { withChatMedia } from "./chatMessage";
import { sendChannelMessage } from "./send";

/** 連続入力中はメンションを探さない（ネイティブと同じ 120ms） */
const MENTION_DELAY_MS = 120;
/** 候補の件数（ネイティブ ChannelRoomColumn の Composer: メンション 4・絵文字 8） */
const MENTION_MAX = 4;
const EMOJI_MAX = 8;
/** 添付を選ぶ input の accept（スマホではカメラ / ギャラリーが開く） */
const ATTACH_ACCEPT = "image/*,video/*";

/** 候補のボタンを押しても本文のフォーカス（= ソフトキーボード）を外さない */
function keepFocus(e: MouseEvent) {
  e.preventDefault();
}

/**
 * チャットの入力欄（ネイティブ ChannelRoomColumn の Composer）。返信中の表示と取り消し・絵文字の挿入・
 * `:` の絵文字補完・`@` のメンション補完・画像 / 動画の添付・送信。Enter は改行、Ctrl / Cmd + Enter で送信。
 * 画像添付があるときは解像度チップ（低/中/高。ComposeDialog と同じ。CH4 / 挙動4.4）。
 * 添付は送信時にアップロードし、URL を本文の後ろに足す（1 件でも失敗したら送らず、添付を残す）。
 */
export function ChatComposer({
  channelId,
  channelRelays,
  replyTo,
  onCancelReply,
  onSent,
  autoFocus = false,
}: {
  channelId: string;
  channelRelays: readonly string[];
  replyTo: NostrEvent | null;
  onCancelReply(): void;
  onSent?: () => void;
  autoFocus?: boolean;
}) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const emojis = useCustomEmojis(me);
  const mediaServer = useMediaServer((s) => s.server);
  const [value, setValue] = useState<TextState>({ text: "", cursor: 0 });
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [sending, setSending] = useState(false);
  const [mentionHits, setMentionHits] = useState<ProfileHit[]>([]);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  // コードで本文を変えたら、描画後にカーソルを合わせて本文へフォーカスを戻す
  const moveCursor = useRef(false);
  /** まだ revoke していないプレビューの blob: URL */
  const previews = useRef(new Set<string>());
  // 添付画像の解像度（ネイティブ ChannelRoomColumn の Composer と同じ既定「中」。CH4 / 挙動4.4）
  const [resolution, setResolution] = useState<ImageResolution>("mid");
  const compressionPrefs = useImageCompression((s) => s.prefs);
  const attachmentsRef = useRef(attachments);
  useLayoutEffect(() => {
    attachmentsRef.current = attachments;
  });
  // 解像度（または圧縮設定）を変えたら、添付済みの画像を圧縮し直す（ComposeDialog と同じ）
  useEffect(() => {
    const maxDim = maxDimFor(resolution, compressionPrefs);
    const list = attachmentsRef.current;
    if (!list.some((a) => a.kind === "image")) return;
    setAttachments(reprocessImages(list, maxDim, compressionPrefs.quality));
  }, [resolution, compressionPrefs]);

  useEffect(() => {
    if (autoFocus) textarea.current?.focus();
  }, [autoFocus]);

  // 返信を選んだら入力欄へ
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

  function update(next: TextState) {
    moveCursor.current = true;
    setValue(next);
  }

  // ---- 入力補完（カーソル直前のトークン。絵文字 > メンションの 1 種だけ出す） ----
  const before = value.text.slice(0, value.cursor);
  const emojiFrag = activeEmoji(before);
  const emojiHits = useMemo(() => {
    if (emojiFrag === null) return [];
    const frag = emojiFrag.toLowerCase();
    return emojis.filter((e) => e.shortcode.toLowerCase().startsWith(frag)).slice(0, EMOJI_MAX);
  }, [emojis, emojiFrag]);
  const mentionFrag = activeMention(before);
  useEffect(() => {
    if (mentionFrag === null) {
      setMentionHits([]);
      return;
    }
    const timer = setTimeout(
      () => setMentionHits(searchProfiles(mentionFrag, MENTION_MAX)),
      MENTION_DELAY_MS,
    );
    return () => clearTimeout(timer);
  }, [mentionFrag]);

  function addFiles(files: Iterable<File>): boolean {
    if (sending) return false;
    const added: Attachment[] = [];
    for (const file of files) {
      const attachment = createAttachment(file);
      if (attachment) added.push(attachment);
    }
    if (added.length === 0) return false;
    for (const a of added) previews.current.add(a.preview);
    setAttachments((list) => [...list, ...added]);
    return true;
  }

  function removeAttachment(target: Attachment) {
    URL.revokeObjectURL(target.preview);
    previews.current.delete(target.preview);
    setAttachments((list) => list.filter((a) => a.id !== target.id));
  }

  const canSend = !sending && (value.text.trim() !== "" || attachments.length > 0);

  async function send() {
    if (!canSend) return;
    const list = [
      ...attachments.filter((a) => a.kind === "image"),
      ...attachments.filter((a) => a.kind === "video"),
    ];
    setSending(true);
    try {
      let urls: string[] = [];
      if (list.length > 0) {
        const signer = currentSigner();
        if (!signer) throw new Error("no signer");
        try {
          const media = await uploadAttachments(list, { servers: uploadServers(mediaServer), signer });
          urls = media.map((m) => m.url);
        } catch (e) {
          console.warn("[chat] Failed to upload the attachment", e);
          showToast(t("chat_upload_failed"));
          return;
        }
      }
      await sendChannelMessage({
        channelId,
        channelRelays,
        content: withChatMedia(value.text, urls),
        replyTo,
        emojis: new Map(emojis.map((e) => [e.shortcode, e.url])),
      });
      setValue({ text: "", cursor: 0 });
      for (const a of list) {
        URL.revokeObjectURL(a.preview);
        previews.current.delete(a.preview);
      }
      setAttachments([]);
      onSent?.();
    } catch (e) {
      console.warn("[chat] Failed to send", e);
      showToast(t("dm_send_failed"));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className={styles.composer}>
      {replyTo && <ReplyingTo target={replyTo} onCancel={onCancelReply} />}
      {emojiHits.length > 0 ? (
        <div className={styles.chips}>
          {emojiHits.map((emoji) => (
            <button
              key={emoji.shortcode}
              type="button"
              className={styles.chip}
              onMouseDown={keepFocus}
              onClick={() => update(insertEmojiShortcode(value, emoji.shortcode))}
            >
              :{emoji.shortcode}:
            </button>
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
                onMouseDown={keepFocus}
                onClick={() => update(completeMention(value, npubEncode(hit.pubkey)))}
              >
                <ProfileAvatar pubkey={hit.pubkey} size={28} />
                <span className={styles.mentionName}>{hit.name || hit.handle}</span>
              </button>
            ))}
          </div>
        )
      )}
      {attachments.length > 0 && (
        <ul className={styles.attachments} aria-label={t("web_attachments")}>
          {attachments.map((a) => (
            <li key={a.id} className={styles.thumb}>
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
                    aria-label={t("web_attachment_video")}
                    muted
                    playsInline
                    preload="metadata"
                  />
                  <PlayArrowIcon className={styles.thumbPlay} />
                </>
              )}
              {!sending && (
                <button
                  type="button"
                  className={styles.remove}
                  aria-label={t("chat_attach_remove")}
                  onClick={() => removeAttachment(a)}
                >
                  <CloseIcon className={styles.removeIcon} />
                </button>
              )}
            </li>
          ))}
        </ul>
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
      <div className={styles.row}>
        <EmojiInsertButton onInsert={(str) => update(insertAtCursor(value, str))} />
        <textarea
          ref={textarea}
          className={styles.input}
          aria-label={t("dm_title")}
          placeholder={t("chat_input_placeholder")}
          rows={1}
          value={value.text}
          disabled={sending}
          onChange={(e) => {
            const el = e.currentTarget;
            setValue({ text: el.value, cursor: el.selectionStart ?? el.value.length });
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
          onPaste={(e) => {
            if (addFiles(Array.from(e.clipboardData.files))) e.preventDefault();
          }}
        />
        <button
          type="button"
          className={styles.tool}
          aria-label={t("web_attach_media")}
          disabled={sending}
          onClick={() => fileInput.current?.click()}
        >
          <ImageIcon className={styles.toolIcon} />
        </button>
        <input
          ref={fileInput}
          type="file"
          accept={ATTACH_ACCEPT}
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

/**
 * 返信中の 1 行（ネイティブ chat_reply_to_fmt「%1$s に返信: %2$s」）と取り消し。
 * DM（features/dm/ConversationView）とも共有する部品（#589）。
 */
export function ReplyingTo({ target, onCancel }: { target: NostrEvent; onCancel(): void }) {
  const t = useT();
  const profile = useProfile(target.pubkey);
  return (
    <div className={styles.replying}>
      <ReplyIcon className={styles.replyingIcon} />
      <span className={styles.replyingText}>
        {t("chat_reply_to_fmt", displayName(profile, target.pubkey), oneLine(plainTextOf(target)))}
      </span>
      <button
        type="button"
        className={styles.cancelReply}
        aria-label={t("chat_cancel_reply")}
        onClick={onCancel}
      >
        <CloseIcon className={styles.cancelIcon} />
      </button>
    </div>
  );
}
