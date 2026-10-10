import { npubEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import {
  type DragEvent,
  type MouseEvent,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useT } from "../../i18n";
import { oneLine } from "../../lib/content/labels";
import { markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { PublishError, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { currentSigner, useSession } from "../../signer/session";
import { ConfirmDialog } from "../../ui/ConfirmDialog";
import {
  CloseIcon,
  ImageIcon,
  PlayArrowIcon,
  PlaylistAddIcon,
  ReplyIcon,
  VisibilityOffIcon,
} from "../../ui/icons";
import { showToast } from "../../ui/toast";
import { useVisualViewportHeight } from "../../ui/useVisualViewportHeight";
import { EmojiInsertButton } from "../actions/EmojiInsertButton";
import { openHashtagManager } from "../hashtags/hashtagManagerStore";
import { pinLimitMessage, togglePinnedHashtag } from "../hashtags/pinnedHashtags";
import { type EditAction, Lightbox } from "../media/Lightbox";
import { NoteContent } from "../timeline/NoteContent";
import { Avatar } from "../timeline/NoteItem";
import {
  type Attachment,
  createAttachment,
  editAttachment,
  humanSize,
  isEditable,
  reprocessImages,
  shownUrl,
  uploadAttachments,
} from "./attachments";
import {
  buildNote,
  buildQuote,
  buildReply,
  buildThread,
  type PostContext,
  type PostMedia,
  threadStepSources,
} from "./buildPost";
import styles from "./ComposeDialog.module.css";
import {
  activeEmoji,
  activeMention,
  activeTagPrefix,
  appendHashtag,
  completeHashtag,
  completeMention,
  insertAtCursor,
  insertEmojiShortcode,
  type TextState,
} from "./completion";
import { type ComposeRequest, closeCompose } from "./composeStore";
import { type CustomEmoji, useCustomEmojis } from "./customEmojis";
import { type ImageResolution, maxDimFor, resolutions, useImageCompression } from "./imageCompression";
import { flippedHorizontally, isEdited, NO_EDIT, rotatedLeft, rotatedRight } from "./imageEdit";
import { uploadServers, useMediaServer } from "./mediaServer";
import { ProfileAvatar } from "./ProfileAvatar";
import { storeRelayHints } from "./relayHints";
import { type ProfileHit, searchProfiles } from "./searchProfiles";
import {
  clearDraft,
  clearThreadDraft,
  loadDraft,
  loadThreadDraft,
  loadUsedHashtags,
  PINNED_MAX,
  recentHashtagChips,
  recordHashtags,
  saveDraft,
  saveThreadDraft,
  type ThreadDraft,
  tagSuggestions,
  usePinnedHashtags,
} from "./storage";

/** 連続入力中はメンションを探さない（ネイティブと同じ 120ms） */
const MENTION_DELAY_MS = 120;
/** 絵文字候補の件数（ネイティブと同じ） */
const EMOJI_SUGGEST_MAX = 12;
const EMPTY_THREAD_DRAFT: ThreadDraft = { segs: [], edit: 0 };

/** 候補のボタンを押しても本文のフォーカス（= ソフトキーボード）を外さない */
function keepFocus(e: MouseEvent) {
  e.preventDefault();
}

/** 添付を選ぶ input の accept（スマホではカメラ / ギャラリーが開く） */
const ATTACH_ACCEPT = "image/*,video/*";

/** ドラッグ中のものにファイルが含まれるか */
function hasFiles(e: DragEvent<HTMLElement>): boolean {
  return Array.from(e.dataTransfer.types).includes("Files");
}

/**
 * 投稿シート（ネイティブ ComposeSheet.kt の Web 版: 連投・ピン留め編集・添付の解像度の選択なし）。
 * 画面上端寄せのカードをモーダルで開く。本文・入力補完・添付（画像・動画）・返信先 / 引用元・センシティブ指定・送信。
 */
export function ComposeDialog({ request }: { request: ComposeRequest }) {
  const t = useT();
  const { mode } = request;
  const dialog = useRef<HTMLDialogElement>(null);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const [value, setValue] = useState<TextState>(() => {
    const draft = mode === "new" ? loadDraft() : "";
    const text = draft.trim() === "" ? "" : draft;
    return { text, cursor: text.length };
  });
  const [sensitive, setSensitive] = useState(false);
  const [cwReason, setCwReason] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [mentionHits, setMentionHits] = useState<ProfileHit[]>([]);
  const me = useSession((s) => s.pubkey);
  const profile = useProfile(me ?? undefined);
  const emojis = useCustomEmojis(me);
  const pinned = usePinnedHashtags(me);
  const [used] = useState(loadUsedHashtags);
  const controller = useRef<AbortController | null>(null);
  // コードで本文を変えたら、描画後にカーソルを合わせて本文へフォーカスを戻す
  const moveCursor = useRef(false);
  // 添付（選んだ時点で圧縮を始め、アップロードは送信時。ネイティブ ComposeSheet と同じ）
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  /** 圧縮が終わった添付のバイト数（添付の id → バイト数） */
  const [processedSizes, setProcessedSizes] = useState<ReadonlyMap<string, number>>(() => new Map());
  /** アップロードの完了数（失敗も数える） */
  const [uploadDone, setUploadDone] = useState(0);
  /** 向きを編集できる画像添付の id（GIF・アニメーション WebP・読めない画像は入らない） */
  const [editableIds, setEditableIds] = useState<ReadonlySet<string>>(() => new Set());
  /** ライトボックスで開いている添付の id */
  const [lightboxId, setLightboxId] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const mediaServer = useMediaServer((s) => s.server);
  /** まだ revoke していないプレビューの blob: URL（閉じたときにまとめて revoke する） */
  const previews = useRef(new Set<string>());
  // 添付の解像度（新規投稿のみ。ネイティブ ResolutionSelector の既定と同じ「中」）
  const [resolution, setResolution] = useState<ImageResolution>("mid");
  const compressionPrefs = useImageCompression((s) => s.prefs);
  // 直前の attachments を読むための ref（解像度を変えたときの再圧縮で、依存配列に attachments を入れて
  // ループさせないため）
  const attachmentsRef = useRef(attachments);
  useLayoutEffect(() => {
    attachmentsRef.current = attachments;
  });
  // 解像度（または圧縮設定）を変えたら、添付済みの画像を圧縮し直す（動画・GIF 等は対象外）
  useEffect(() => {
    const maxDim = maxDimFor(resolution, compressionPrefs);
    const list = attachmentsRef.current;
    if (!list.some((a) => a.kind === "image")) return;
    const next = reprocessImages(list, maxDim, compressionPrefs.quality);
    setAttachments(next);
    setProcessedSizes((sizes) => {
      const cleared = new Map(sizes);
      for (const a of next) if (a.kind === "image") cleared.delete(a.id);
      return cleared;
    });
    for (const a of next) {
      if (a.kind !== "image") continue;
      void a.processed.then((p) => setProcessedSizes((sizes) => new Map(sizes).set(a.id, p.blob.size)));
    }
  }, [resolution, compressionPrefs]);

  // 連投（新規投稿のみ）。積んだ段落 + 本文がスレッドの何番目か。下書きは閉じたときに保存する（本文の下書きとは
  // 違い、キー入力のたびには保存しない。ネイティブ ComposeSheet の onDispose と同じ）
  const [thread, setThread] = useState<ThreadDraft>(() =>
    mode === "new" ? (loadThreadDraft() ?? EMPTY_THREAD_DRAFT) : EMPTY_THREAD_DRAFT,
  );
  const threadSegments = thread.segs;
  const editIdx = thread.edit;
  /** 送信できた・破棄した（この 2 つのときだけ、閉じても連投の下書きを保存しない） */
  const sentOk = useRef(false);
  const discardedThread = useRef(false);
  const latestThread = useRef(thread);
  useLayoutEffect(() => {
    latestThread.current = thread;
  });
  useEffect(() => {
    return () => {
      if (mode !== "new" || sentOk.current || discardedThread.current) return;
      saveThreadDraft(latestThread.current.segs, latestThread.current.edit);
    };
  }, [mode]);

  /** 本文を変える（新規投稿は入力のたびに下書きへ保存） */
  function update(next: TextState, fromCode: boolean) {
    if (next === value) return;
    if (fromCode) moveCursor.current = true;
    setValue(next);
    if (mode === "new") saveDraft(next.text);
  }

  /** 閉じる操作の入口（✗・背景・Esc / 戻る）。書きかけ・添付ありなら確認を挟む。送信中は閉じない */
  function attemptClose() {
    if (sending) return;
    if (value.text.trim() !== "" || attachments.length > 0) setConfirmDiscard(true);
    else closeCompose();
  }
  const latestAttemptClose = useRef(attemptClose);
  useLayoutEffect(() => {
    latestAttemptClose.current = attemptClose;
  });

  /** 画像・動画を添付に足して圧縮を始める（それ以外のファイルは無視）。1 件でも足したら true */
  function addFiles(files: Iterable<File>): boolean {
    if (sending) return false;
    const added: Attachment[] = [];
    for (const file of files) {
      const attachment = createAttachment(file);
      if (attachment) added.push(attachment);
    }
    if (added.length === 0) return false;
    for (const attachment of added) {
      previews.current.add(attachment.preview);
      void attachment.processed.then((p) =>
        setProcessedSizes((sizes) => new Map(sizes).set(attachment.id, p.blob.size)),
      );
      void isEditable(attachment).then((ok) => {
        if (ok) setEditableIds((ids) => new Set(ids).add(attachment.id));
      });
    }
    setAttachments((list) => [...list, ...added]);
    return true;
  }

  function removeAttachment(target: Attachment) {
    for (const url of [target.preview, target.edited]) {
      if (!url) continue;
      URL.revokeObjectURL(url);
      previews.current.delete(url);
    }
    setAttachments((list) => list.filter((a) => a.id !== target.id));
  }

  /**
   * ライトボックスの編集（回転・左右反転・元に戻す）。その添付だけ送信用の画像を作り直し（dim・blurhash も取り直す）、
   * できたらプレビュー（サムネイルとライトボックス）を差し替える。作り直しを重ねたら最後に始めた結果だけを使う。
   */
  function editImage(id: string, action: EditAction) {
    if (sending) return;
    const current = attachmentsRef.current.find((a) => a.id === id);
    if (current?.kind !== "image") return;
    const edit =
      action === "reset"
        ? NO_EDIT
        : action === "rotateLeft"
          ? rotatedLeft(current.edit)
          : action === "rotateRight"
            ? rotatedRight(current.edit)
            : flippedHorizontally(current.edit);
    const next = editAttachment(
      current,
      edit,
      maxDimFor(resolution, compressionPrefs),
      compressionPrefs.quality,
    );
    attachmentsRef.current = attachmentsRef.current.map((a) => (a.id === id ? next : a));
    setAttachments((list) => list.map((a) => (a.id === id ? next : a)));
    setProcessedSizes((sizes) => {
      const cleared = new Map(sizes);
      cleared.delete(id);
      return cleared;
    });
    void next.processed.then((p) => {
      const latest = attachmentsRef.current.find((a) => a.id === id);
      if (!latest || latest.processed !== next.processed) return;
      setProcessedSizes((sizes) => new Map(sizes).set(id, p.blob.size));
      const edited = isEdited(edit) ? URL.createObjectURL(p.blob) : undefined;
      if (edited) previews.current.add(edited);
      const stale = latest.edited;
      attachmentsRef.current = attachmentsRef.current.map((a) => (a.id === id ? { ...a, edited } : a));
      setAttachments((list) => list.map((a) => (a.id === id ? { ...a, edited } : a)));
      if (stale) {
        URL.revokeObjectURL(stale);
        previews.current.delete(stale);
      }
    });
  }

  // 閉じたらプレビューの blob: URL を解放する
  useEffect(() => {
    const urls = previews.current;
    return () => {
      for (const url of urls) URL.revokeObjectURL(url);
      urls.clear();
    };
  }, []);

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
    const el = textarea.current;
    if (el) {
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    }
  }, []);

  // カードの外（dialog 自身 = 背景）の押下
  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    const onClick = (e: globalThis.MouseEvent) => {
      if (e.target === d) latestAttemptClose.current();
    };
    d.addEventListener("click", onClick);
    return () => d.removeEventListener("click", onClick);
  }, []);

  // ソフトキーボードが出たら見えている高さにカードを収める
  useVisualViewportHeight(dialog, "--compose-vvh");

  useLayoutEffect(() => {
    const el = textarea.current;
    if (!el) return;
    if (moveCursor.current) {
      moveCursor.current = false;
      el.focus();
      el.setSelectionRange(value.cursor, value.cursor);
    }
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [value]);

  // ---- 入力補完（カーソル直前のトークン。絵文字 > メンション > ハッシュタグの 1 種だけ出す） ----
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
  const tagPrefix = activeTagPrefix(before);
  const suggestions = tagPrefix === null ? [] : tagSuggestions(tagPrefix, pinned, used);
  const recent = recentHashtagChips(used, pinned);

  /** ピン留め・最近のタグ: 入力中の #断片 に前方一致なら補完、でなければカーソル位置に足す */
  function insertTag(tag: string) {
    update(
      tagPrefix !== null && tag.startsWith(tagPrefix)
        ? completeHashtag(value, tag)
        : appendHashtag(value, tag),
      true,
    );
  }

  /** タグチップの長押し / 右クリックのメニュー（#536。取り直してから 1 回だけ発行する） */
  function toggleTagPin(tag: string, pin: boolean) {
    if (!me) return;
    if (pin && pinned.length >= PINNED_MAX) {
      showToast(pinLimitMessage());
      return;
    }
    void togglePinnedHashtag(me, tag, pin)
      .then((result) => {
        if (result === "limit") showToast(pinLimitMessage());
      })
      .catch(() => showToast(t("web_compose_pin_failed")));
  }

  // ---- 連投（新規投稿のみ。ネイティブ ComposeSheet の threadParts / onEdit / onDelete） ----
  // 一覧に出すスレッド全体（積んだぶん + いま書いている本文）。空の本文はまだ書いていないだけなので並びに入れない
  const threadParts: string[] =
    value.text.trim() !== ""
      ? [...threadSegments.slice(0, editIdx), value.text, ...threadSegments.slice(editIdx)]
      : threadSegments;
  const bodyRow = value.text.trim() !== "" ? editIdx : -1;
  /** 一覧の行 index → threadSegments の index（本文が並びに居るかで 1 つずれる） */
  function toSegmentIndex(i: number): number {
    return bodyRow >= 0 && i > bodyRow ? i - 1 : i;
  }

  /** 積んだ段落を本文へ戻し、いま書いていた本文はその段落が居た位置へ積む（並び順は変わらない） */
  function editThreadSegment(i: number) {
    const si = toSegmentIndex(i);
    const picked = threadSegments[si];
    const rest = [...threadSegments.slice(0, si), ...threadSegments.slice(si + 1)];
    if (value.text.trim() !== "") {
      const insertAt = si < editIdx ? editIdx - 1 : editIdx;
      setThread({
        segs: [...rest.slice(0, insertAt), value.text.trimEnd(), ...rest.slice(insertAt)],
        edit: i,
      });
    } else {
      setThread({ segs: rest, edit: i > editIdx ? i - 1 : i });
    }
    update({ text: picked, cursor: picked.length }, true);
  }

  /** ✗ でその段落を取り消す */
  function deleteThreadSegment(i: number) {
    const si = toSegmentIndex(i);
    setThread((t) => ({
      segs: [...t.segs.slice(0, si), ...t.segs.slice(si + 1)],
      edit: si < t.edit ? t.edit - 1 : t.edit,
    }));
  }

  /** 「連投に追加」: いま書いている位置へ本文を積み、入力欄を空にする */
  function addToThread() {
    if (value.text.trim() === "") return;
    setThread((t) => ({
      segs: [...t.segs.slice(0, t.edit), value.text.trimEnd(), ...t.segs.slice(t.edit)],
      edit: t.edit + 1,
    }));
    update({ text: "", cursor: 0 }, true);
  }

  // ---- 送信 ----
  const canSend =
    !sending &&
    (value.text.trim() !== "" || attachments.length > 0 || mode === "quote" || threadSegments.length > 0);
  const sendLabel =
    mode === "new" && threadSegments.length > 0
      ? t("compose_thread")
      : mode === "reply"
        ? t("compose_reply")
        : mode === "quote"
          ? t("compose_quote")
          : t("compose_send");
  const dialogLabel =
    mode === "reply" ? t("compose_reply") : mode === "quote" ? t("compose_quote") : t("fab_post");

  async function send() {
    if (!canSend) return;
    if (me === null) {
      setSendError(t("compose_send_failed"));
      return;
    }
    const content = value.text.trim() === "" ? "" : value.text.trimEnd();
    const cw = sensitive ? cwReason.trim() : null;
    const ctx: PostContext = {
      me,
      emojis: new Map(emojis.map((e) => [e.shortcode, e.url])),
      hints: storeRelayHints(me),
      lookup: (id) => eventStore.getEvent(id),
    };
    const list = attachments;
    const isThread = threadSegments.length > 0;
    // 積んだ段落 + いま書いている本文（editIdx の位置）。連投でなければ空
    const raw = isThread
      ? [...threadSegments.slice(0, editIdx), content, ...threadSegments.slice(editIdx)]
      : [];
    const ac = new AbortController();
    controller.current = ac;
    setSending(true);
    setSendError(null);
    setUploadDone(0);
    let media: PostMedia[] = [];
    let sentCount = 0;
    try {
      // 添付は先にアップロードする（1 件でも失敗したら投稿しない）
      if (list.length > 0) {
        const signer = currentSigner();
        if (!signer) throw new Error("no signer");
        media = await uploadAttachments(list, {
          servers: uploadServers(mediaServer),
          signer,
          signal: ac.signal,
          onProgress: (done) => {
            if (!ac.signal.aborted) setUploadDone(done);
          },
        });
      }
      if (isThread) {
        // [#533] 連投: 先頭から順に発行し、前の段落の id が決まってから次を署名する（自己スレッド化）。
        const steps = buildThread(raw, cw, ctx, media, editIdx);
        let rootId: string | null = null;
        let prevId: string | null = null;
        for (const step of steps) {
          const prior = rootId !== null && prevId !== null ? { rootId, prevId } : null;
          const signed = await publishEvent(step.build(prior), { signal: ac.signal });
          recordHashtags(step.content, signed.created_at);
          rootId = rootId ?? signed.id;
          prevId = signed.id;
          sentCount += 1;
        }
      } else {
        const draft =
          request.mode === "reply"
            ? buildReply(request.target, content, cw, ctx, media)
            : request.mode === "quote"
              ? buildQuote(request.target, content, cw, ctx, media)
              : buildNote(content, cw, ctx, media);
        const signed = await publishEvent(draft, { signal: ac.signal });
        recordHashtags(content, signed.created_at);
      }
      // ネイティブと同じく、返信・引用の送信でも新規投稿の下書きを消す
      sentOk.current = true;
      clearDraft();
      clearThreadDraft();
      closeCompose();
    } catch (e) {
      // キャンセル済み（もう編集に戻っている）
      if (ac.signal.aborted || (e instanceof PublishError && e.reason === "aborted")) return;
      if (isThread && sentCount > 0) {
        // [#533] 送れた段落はもう二度と送らない（送り直すと二重投稿になる）ので下書きから外し、
        // 失敗した段落から先だけを新しい連投の下書きに残す（root/reply は次に送るときに新しく組み直す）。
        const sources = threadStepSources(raw, media, editIdx);
        const pending = sources.slice(sentCount);
        // 添付はもともと editIdx の段落に付けていた。まだ送れていなければ次に引き継ぎ、送れていれば
        // 別の段落へ誤って付かないよう外す。
        const mediaPending = media.length > 0 && editIdx >= sentCount;
        const currentPos = mediaPending ? editIdx - sentCount : 0;
        const newBody = pending[currentPos];
        setThread({ segs: [...pending.slice(0, currentPos), ...pending.slice(currentPos + 1)], edit: 0 });
        update({ text: newBody, cursor: newBody.length }, true);
        if (media.length > 0 && !mediaPending) {
          for (const a of list) {
            URL.revokeObjectURL(a.preview);
            if (a.edited) URL.revokeObjectURL(a.edited);
          }
          previews.current.clear();
          setAttachments([]);
          setProcessedSizes(new Map());
        }
        showToast(t("web_compose_thread_partial", sentCount));
      } else {
        setSendError(t("compose_send_failed"));
      }
      setSending(false);
    } finally {
      if (controller.current === ac) controller.current = null;
    }
  }

  /** 送信中の「キャンセル」: アップロード・署名待ちを打ち切って編集に戻る（本文・添付は残す） */
  function cancelSend() {
    controller.current?.abort();
    controller.current = null;
    setSending(false);
  }

  return (
    <>
      <dialog
        ref={dialog}
        className={styles.dialog}
        aria-label={dialogLabel}
        onCancel={(e) => {
          e.preventDefault();
          attemptClose();
        }}
        // ブラウザが強制で閉じた場合（下書きは入力のたびに保存済み）
        onClose={() => closeCompose()}
        // PC: ファイルのドラッグ & ドロップと貼り付けで添付する
        onDragOver={(e) => {
          if (!hasFiles(e)) return;
          e.preventDefault();
          e.dataTransfer.dropEffect = sending ? "none" : "copy";
        }}
        onDrop={(e) => {
          if (!hasFiles(e)) return;
          e.preventDefault();
          addFiles(Array.from(e.dataTransfer.files));
        }}
        onPaste={(e) => {
          if (addFiles(Array.from(e.clipboardData.files))) e.preventDefault();
        }}
      >
        <div className={styles.card}>
          <div className={styles.head}>
            {me !== null && <ProfileAvatar pubkey={me} size={22} />}
            <span className={styles.name}>
              {me !== null ? displayName(profile, me, "npub") : t("compose_you")}
            </span>
            <button
              type="button"
              className={styles.close}
              aria-label={t("common_close")}
              disabled={sending}
              onClick={attemptClose}
            >
              <CloseIcon className={styles.closeIcon} />
            </button>
          </div>
          <div className={styles.scroll}>
            {threadSegments.length > 0 && (
              <ThreadSegmentList
                parts={threadParts}
                editIndex={bodyRow}
                onEdit={editThreadSegment}
                onDelete={deleteThreadSegment}
              />
            )}
            <textarea
              ref={textarea}
              className={styles.body}
              aria-label={t("web_compose_body_label")}
              placeholder={t("compose_placeholder")}
              value={value.text}
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
                  if (canSend) void send();
                }
              }}
            />
            {emojiHits.length > 0 ? (
              <>
                <p className={styles.hint}>{t("compose_emoji_suggest")}</p>
                <div className={styles.chips}>
                  {emojiHits.map((emoji) => (
                    <EmojiChip
                      key={emoji.shortcode}
                      emoji={emoji}
                      onClick={() => update(insertEmojiShortcode(value, emoji.shortcode), true)}
                    />
                  ))}
                </div>
              </>
            ) : mentionHits.length > 0 ? (
              <>
                <p className={styles.hint}>{t("compose_mention_suggest")}</p>
                <div className={styles.mentions}>
                  {mentionHits.map((hit) => (
                    <button
                      key={hit.pubkey}
                      type="button"
                      className={styles.mention}
                      onMouseDown={keepFocus}
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
              </>
            ) : (
              <>
                {suggestions.length > 0 && (
                  <>
                    <p className={styles.hint}>{t("compose_suggest")}</p>
                    <div className={styles.chips}>
                      {suggestions.map((tag) => (
                        <TagChip
                          key={tag}
                          tag={tag}
                          pinned={pinned.includes(tag)}
                          onClick={() => update(completeHashtag(value, tag), true)}
                          onToggle={(pin) => toggleTagPin(tag, pin)}
                        />
                      ))}
                    </div>
                  </>
                )}
                {/* [#393] 「📌 ピン留め」は常時表示（末尾に「整理…」）→「最近のタグ」の2段（ネイティブと同じ。C3） */}
                <p className={styles.hint}>{t("compose_pinned_tags")}</p>
                <div className={styles.chips}>
                  {pinned.map((tag) => (
                    <TagChip
                      key={tag}
                      tag={tag}
                      pinned
                      onClick={() => insertTag(tag)}
                      onToggle={(pin) => toggleTagPin(tag, pin)}
                    />
                  ))}
                  <button
                    type="button"
                    className={styles.chip}
                    onMouseDown={keepFocus}
                    onClick={() => openHashtagManager()}
                  >
                    {t("compose_manage_tags")}
                  </button>
                </div>
                {recent.length > 0 && (
                  <>
                    <p className={styles.hint}>{t("compose_recent_tags")}</p>
                    <div className={styles.chips}>
                      {recent.map((tag) => (
                        <TagChip
                          key={tag}
                          tag={tag}
                          pinned={false}
                          onClick={() => insertTag(tag)}
                          onToggle={(pin) => toggleTagPin(tag, pin)}
                        />
                      ))}
                    </div>
                  </>
                )}
              </>
            )}
            {attachments.length > 0 && (
              <AttachmentList
                attachments={attachments}
                processedSizes={processedSizes}
                removable={!sending}
                onRemove={removeAttachment}
                onOpen={(a) => setLightboxId(a.id)}
              />
            )}
            {lightboxId !== null && (
              <AttachmentLightbox
                attachments={attachments}
                id={lightboxId}
                editableIds={editableIds}
                onEdit={editImage}
                onClose={() => setLightboxId(null)}
              />
            )}
            {attachments.some((a) => a.kind === "image") && (
              <fieldset className={styles.resolutionRow}>
                <legend className={styles.hint}>{t("compose_resolution")}</legend>
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
          </div>
          {request.mode !== "new" && (
            <div className={styles.context}>
              {request.mode === "reply" ? (
                <ReplyTargetLine target={request.target} />
              ) : (
                <QuoteContextCard target={request.target} />
              )}
            </div>
          )}
          {sendError !== null && (
            <p role="alert" className={styles.error}>
              {sendError}
            </p>
          )}
          {sensitive && !sending && (
            <div className={styles.reason}>
              <input
                type="text"
                className={styles.reasonInput}
                aria-label={t("web_compose_cw_reason_label")}
                placeholder={t("compose_cw_reason_hint")}
                value={cwReason}
                onChange={(e) => setCwReason(e.currentTarget.value)}
              />
            </div>
          )}
          <div className={styles.bar}>
            {sending ? (
              <>
                <span className={styles.spinner} aria-hidden="true" />
                <span className={styles.sendingText}>
                  {attachments.length > 0
                    ? t("compose_uploading_fmt", uploadDone, attachments.length)
                    : t("compose_posting")}
                </span>
                <span className={styles.spacer} />
                <button type="button" className={styles.cancel} onClick={cancelSend}>
                  {t("common_cancel")}
                </button>
              </>
            ) : (
              <>
                <div className={styles.tools}>
                  <button
                    type="button"
                    className={styles.tool}
                    aria-label={t("web_attach_media")}
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
                      // 同じファイルをもう一度選べるように空にする
                      input.value = "";
                    }}
                  />
                  <EmojiInsertButton onInsert={(str) => update(insertAtCursor(value, str), true)} />
                  <button
                    type="button"
                    className={styles.tool}
                    aria-pressed={sensitive}
                    aria-label={sensitive ? t("compose_sensitive_on") : t("compose_sensitive")}
                    onClick={() => setSensitive((v) => !v)}
                  >
                    <VisibilityOffIcon className={styles.toolIcon} />
                  </button>
                  {mode === "new" && (
                    <>
                      <button
                        type="button"
                        className={styles.tool}
                        aria-label={t("compose_add_thread")}
                        disabled={value.text.trim() === ""}
                        onClick={addToThread}
                      >
                        <PlaylistAddIcon className={styles.toolIcon} />
                      </button>
                      {threadSegments.length > 0 && (
                        <span className={styles.threadCount}>
                          {t("compose_thread_count_fmt", editIdx + 1)}
                        </span>
                      )}
                    </>
                  )}
                </div>
                <button type="button" className={styles.send} disabled={!canSend} onClick={() => void send()}>
                  {sendLabel}
                </button>
              </>
            )}
          </div>
        </div>
      </dialog>
      {confirmDiscard && (
        <ConfirmDialog
          title={t("compose_discard_title")}
          text={t("compose_discard_text")}
          confirmLabel={t("compose_discard_confirm")}
          destructive
          onConfirm={() => {
            // 返信・引用の破棄では新規投稿の下書きを消さない（ネイティブの onDispose と同じ）
            if (mode === "new") {
              clearDraft();
              discardedThread.current = true;
              clearThreadDraft();
            }
            closeCompose();
          }}
          onDismiss={() => setConfirmDiscard(false)}
        />
      )}
    </>
  );
}

/**
 * 添付の一覧（ネイティブ ImageCarousel / VideoCarousel。84px のサムネ + 右上の ✗ + 下に容量）。
 * 画像 → 動画の順に並べる（本文へ足す URL と同じ順）。
 */
function AttachmentList({
  attachments,
  processedSizes,
  removable,
  onRemove,
  onOpen,
}: {
  attachments: readonly Attachment[];
  processedSizes: ReadonlyMap<string, number>;
  removable: boolean;
  onRemove(attachment: Attachment): void;
  onOpen(attachment: Attachment): void;
}) {
  const t = useT();
  const ordered = [
    ...attachments.filter((a) => a.kind === "image"),
    ...attachments.filter((a) => a.kind === "video"),
  ];
  return (
    <ul className={styles.attachments} aria-label={t("web_attachments")}>
      {ordered.map((a) => {
        const original = a.file.size;
        const processed = processedSizes.get(a.id);
        const label =
          a.kind === "image" && processed === undefined
            ? t("compose_compressing")
            : processed !== undefined && processed < original
              ? `${humanSize(original)}→${humanSize(processed)}`
              : humanSize(original);
        return (
          <li key={a.id} className={styles.attachment}>
            <div className={styles.thumb}>
              {a.kind === "image" ? (
                <button
                  type="button"
                  className={styles.thumbOpen}
                  aria-label={t("web_compose_attachment_open")}
                  onClick={() => onOpen(a)}
                >
                  <img
                    className={styles.thumbMedia}
                    src={shownUrl(a)}
                    alt={t("compose_attachment")}
                    decoding="async"
                  />
                </button>
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
              {removable && (
                <button
                  type="button"
                  className={styles.remove}
                  aria-label={t("common_delete")}
                  onClick={() => onRemove(a)}
                >
                  <CloseIcon className={styles.removeIcon} />
                </button>
              )}
            </div>
            <span className={styles.size}>{label}</span>
          </li>
        );
      })}
    </ul>
  );
}

/** 添付の画像をライトボックスで開く（複数枚なら前後に移れる。編集メニューは編集できる画像だけ） */
function AttachmentLightbox({
  attachments,
  id,
  editableIds,
  onEdit,
  onClose,
}: {
  attachments: readonly Attachment[];
  id: string;
  editableIds: ReadonlySet<string>;
  onEdit(id: string, action: EditAction): void;
  onClose(): void;
}) {
  const images = attachments.filter((a) => a.kind === "image");
  const index = Math.max(
    0,
    images.findIndex((a) => a.id === id),
  );
  return (
    <Lightbox
      items={images.map((a) => ({ url: shownUrl(a) }))}
      index={index}
      onClose={onClose}
      edit={{
        canEdit: (i) => editableIds.has(images[i]?.id ?? ""),
        isEdited: (i) => isEdited(images[i]?.edit ?? NO_EDIT),
        onAction: (i, action) => {
          const target = images[i];
          if (target) onEdit(target.id, action);
        },
      }}
    />
  );
}

/**
 * 連投で積んだ段落の一覧（ネイティブ ThreadSegmentList: 番号・3 行まで・編集中の行は背景で示す）。
 * いま書いている本文もこの並びに入れて出す（editIndex がその行。負ならどの行も編集中ではない）。
 */
function ThreadSegmentList({
  parts,
  editIndex,
  onEdit,
  onDelete,
}: {
  parts: readonly string[];
  editIndex: number;
  onEdit(i: number): void;
  onDelete(i: number): void;
}) {
  const t = useT();
  return (
    <ol className={styles.threadList} aria-label={t("compose_thread")}>
      {parts.map((part, i) => {
        const editing = i === editIndex;
        const rowClass = editing ? `${styles.threadRow} ${styles.threadRowEditing}` : styles.threadRow;
        return (
          // biome-ignore lint/suspicious/noArrayIndexKey: 段落はただの文字列で並び順そのものが意味を持つ（同じ内容の段落もあり得る）
          <li key={i} className={rowClass}>
            <span className={styles.threadNum}>{i + 1}</span>
            {editing ? (
              <span className={styles.threadText}>{part}</span>
            ) : (
              <button type="button" className={styles.threadTextButton} onClick={() => onEdit(i)}>
                {part}
              </button>
            )}
            {editing ? (
              <span className={styles.threadEditing}>{t("compose_thread_editing")}</span>
            ) : (
              <button
                type="button"
                className={styles.threadRemove}
                aria-label={t("compose_thread_remove")}
                onClick={() => onDelete(i)}
              >
                <CloseIcon className={styles.threadRemoveIcon} />
              </button>
            )}
          </li>
        );
      })}
    </ol>
  );
}

/** 長押し（ネイティブと同じ 500ms）・右クリックでピン留め / 解除のメニューを出す */
const TAG_LONG_PRESS_MS = 500;

/** ハッシュタグのチップ（候補・ピン留め・最近のタグ共通）。長押し / 右クリックで tag_pin / tag_unpin */
function TagChip({
  tag,
  pinned,
  onClick,
  onToggle,
}: {
  tag: string;
  pinned: boolean;
  onClick(): void;
  onToggle(pin: boolean): void;
}) {
  const t = useT();
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const longPressed = useRef(false);
  const pressTimer = useRef<number | null>(null);

  function cancelPress() {
    if (pressTimer.current !== null) {
      window.clearTimeout(pressTimer.current);
      pressTimer.current = null;
    }
  }

  function openMenu(x: number, y: number) {
    longPressed.current = true;
    setMenu({ x, y });
  }

  return (
    <>
      <button
        type="button"
        className={styles.chip}
        onMouseDown={keepFocus}
        onContextMenu={(e) => {
          e.preventDefault();
          openMenu(e.clientX, e.clientY);
        }}
        onTouchStart={(e) => {
          const touch = e.touches[0];
          if (!touch) return;
          const { clientX, clientY } = touch;
          pressTimer.current = window.setTimeout(() => openMenu(clientX, clientY), TAG_LONG_PRESS_MS);
        }}
        onTouchEnd={cancelPress}
        onTouchMove={cancelPress}
        onClick={() => {
          // 長押しで開いたメニューに続くクリック（touchend 由来）は無視する
          if (longPressed.current) {
            longPressed.current = false;
            return;
          }
          onClick();
        }}
      >
        #{tag}
      </button>
      {menu && (
        <TagMenu
          x={menu.x}
          y={menu.y}
          label={pinned ? t("tag_unpin") : t("tag_pin")}
          onSelect={() => onToggle(!pinned)}
          onDismiss={() => setMenu(null)}
        />
      )}
    </>
  );
}

/** タグチップの長押し / 右クリックで出す 1 項目のメニュー。外側の押下・Escape で閉じる */
function TagMenu({
  x,
  y,
  label,
  onSelect,
  onDismiss,
}: {
  x: number;
  y: number;
  label: string;
  onSelect(): void;
  onDismiss(): void;
}) {
  const menu = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onPointerDown = (e: PointerEvent) => {
      if (menu.current?.contains(e.target as Node)) return;
      onDismiss();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onDismiss();
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [onDismiss]);

  return (
    <div ref={menu} role="menu" aria-label={label} className={styles.tagMenu} style={{ left: x, top: y }}>
      <button
        type="button"
        role="menuitem"
        className={styles.tagMenuItem}
        onClick={() => {
          onDismiss();
          onSelect();
        }}
      >
        {label}
      </button>
    </div>
  );
}

/** 絵文字候補のチップ（画像 + :code:）。プロキシが読めなければ元 URL で 1 度だけ取り直す */
function EmojiChip({ emoji, onClick }: { emoji: CustomEmoji; onClick(): void }) {
  const [src, setSrc] = useState<string | null>(() => proxied(emoji.url, 64, 80, true));

  function onError() {
    const origin = originOf(src);
    if (origin) {
      markProxyBlocked(origin);
      setSrc(origin);
    } else {
      setSrc(null);
    }
  }

  return (
    <button
      type="button"
      className={styles.chip}
      aria-label={`:${emoji.shortcode}:`}
      onMouseDown={keepFocus}
      onClick={onClick}
    >
      {src !== null && (
        <img
          className={styles.emoji}
          src={src}
          alt=""
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
          onError={onError}
        />
      )}
      :{emoji.shortcode}:
    </button>
  );
}

/** 返信先の 1 行「◁ (アバター) 名前: 本文…」 */
function ReplyTargetLine({ target }: { target: NostrEvent }) {
  const profile = useProfile(target.pubkey);
  const picture = pictureOf(profile);
  return (
    <p className={styles.replyLine}>
      <ReplyIcon className={styles.replyIcon} />
      <Avatar key={picture} url={picture} size="sm" seed={target.pubkey} pubkey={target.pubkey} />
      <span
        className={styles.replyText}
      >{`${displayName(profile, target.pubkey, "npub")}: ${oneLine(target.content)}`}</span>
    </p>
  );
}

/** 引用元のカード（見出し・作者・本文 2 行） */
function QuoteContextCard({ target }: { target: NostrEvent }) {
  const t = useT();
  const profile = useProfile(target.pubkey);
  return (
    <div className={styles.quote}>
      <p className={styles.quoteLabel}>{t("compose_quote_of")}</p>
      <div className={styles.quoteAuthor}>
        <ProfileAvatar pubkey={target.pubkey} size={28} />
        <span className={styles.quoteName}>{displayName(profile, target.pubkey, "npub")}</span>
      </div>
      <div className={styles.quoteBody}>
        <NoteContent event={target} variant="quote" />
      </div>
    </div>
  );
}
