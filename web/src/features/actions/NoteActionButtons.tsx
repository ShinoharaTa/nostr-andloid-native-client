import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useMemo, useState } from "react";
import { t, useT } from "../../i18n";
import { clientNameOf } from "../../lib/content/tags";
import { displayName, useProfile } from "../../nostr/loaders";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { ConfirmDialog } from "../../ui/ConfirmDialog";
import { EventJsonDialog } from "../../ui/EventJsonDialog";
import {
  AddReactionIcon,
  BoltIcon,
  FavoriteBorderIcon,
  FavoriteIcon,
  MoreHorizIcon,
  RepeatIcon,
  StarBorderIcon,
  StarIcon,
} from "../../ui/icons";
import { MenuButton } from "../../ui/MenuButton";
import { showToast } from "../../ui/toast";
import { openCompose } from "../compose/composeStore";
import { ACTION_BUTTON_CLASS, ActionButton } from "../compose/NoteFooter";
import { OwnListError, toggleBookmark, togglePinned, useIsBookmarked, useIsPinned } from "../lists/ownLists";
import { useMuteMatcher } from "../mute/muteList";
import { MuteListError, muteUser, unmuteUser } from "../mute/muteSync";
import { followsFromContacts } from "../profile/contacts";
import { FollowError, toggleFollow } from "../profile/follow";
import { useDeveloperMode } from "../settings/devMode";
import { translateAvailable } from "../translate/translate";
import { hideTranslation, requestTranslation, useTranslation } from "../translate/translateStore";
import { payInvoiceWithNwc, useNwc } from "../wallet/nwcManager";
import { useZapSats } from "../zap/useZapReceipts";
import { ZapDialog } from "../zap/ZapDialog";
import { formatSats } from "../zap/zapTotals";
import { moreMenuEntries } from "./moreMenu";
import styles from "./NoteActionButtons.module.css";
import { copyText, hasBodyText, noteLinksOf, plainTextOf } from "./noteLinks";
import { reactionSentMessage, saveMadeEmoji } from "./pickedReaction";
import { ReactionPickerDialog } from "./ReactionPickerDialog";
import { ReportDialog } from "./ReportDialog";
import { useDefaultReaction } from "./reactionPrefs";
import {
  ensureMyReactionsSubscribed,
  publishReaction,
  publishRepost,
  reactWithDefault,
  reportNote,
  requestDelete,
  useIsReacted,
  useIsReposted,
} from "./reactions";

/** ♡ を押してから自分の kind:7 が来なければ押下を戻すまで（ネイティブと同じ 6 秒） */
export const REACTION_PENDING_MS = 6_000;

/** 発行の失敗は画面に出さない（ネイティブと同じ） */
function warn(message: string) {
  return (e: unknown) => console.warn(`[actions] ${message}`, e);
}

/**
 * 投稿のアクション行の「返信」の後ろ（NoteItem から NoteFooter の children として呼ぶ唯一の入口）:
 * リポスト → 既定リアクション（♡ / ☆）→ 絵文字 → ⚡。数は Zap の合計だけ出す（他は押下状態だけ）。
 * [#683] ⋯（NoteMoreMenu）はここには含めない。NoteFooter の primaryActions（返信〜Zap を行の幅に
 * 均等配置する箱）の外に置き、行の右端に固定するため NoteFooter の more props へ別に渡す。
 */
export function NoteActionButtons({ event }: { event: NostrEvent }) {
  const me = useSession((s) => s.pubkey);
  useEffect(() => {
    if (me) ensureMyReactionsSubscribed(me);
  }, [me]);
  return (
    <>
      <RepostButton event={event} />
      <DefaultReactionButton event={event} />
      <EmojiReactionButton event={event} />
      <ZapAction event={event} />
    </>
  );
}

/** リポスト。押すと「リポスト」「引用リポスト」。自分がリポスト済みなら緑 */
function RepostButton({ event }: { event: NostrEvent }) {
  const t = useT();
  const reposted = useIsReposted(event.id);
  return (
    <MenuButton
      label={t("note_repost")}
      triggerClassName={reposted ? `${ACTION_BUTTON_CLASS} ${styles.reposted}` : ACTION_BUTTON_CLASS}
      entries={[
        {
          type: "item",
          label: t("note_repost"),
          onSelect: () => void publishRepost(event).catch(warn("Failed to repost")),
        },
        {
          type: "item",
          label: t("note_quote_repost"),
          onSelect: () => openCompose({ mode: "quote", target: event }),
        },
      ]}
    >
      <RepeatIcon />
    </MenuButton>
  );
}

/**
 * 既定リアクション（ネイティブ DefaultReactionButton）。⭐ / ★ なら ☆、それ以外は ♡。
 * 押すと楽観的に押下 + 署名待ちのスピナー、自分の kind:7 が来たら確定、来なければ 6 秒で戻す。
 * 付与済みを押すと確認してから取り消す（kind:5）。
 */
function DefaultReactionButton({ event }: { event: NostrEvent }) {
  const t = useT();
  const content = useDefaultReaction((s) => s.content);
  const isStar = content === "⭐" || content === "★";
  const active = useIsReacted(event.id);
  const [pending, setPending] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    if (active) setPending(false);
  }, [active]);

  useEffect(() => {
    if (!pending) return;
    const timer = setTimeout(() => setPending(false), REACTION_PENDING_MS);
    return () => clearTimeout(timer);
  }, [pending]);

  const on = active || pending;
  const busy = pending && !active;
  const Glyph = isStar ? (on ? StarIcon : StarBorderIcon) : on ? FavoriteIcon : FavoriteBorderIcon;

  function onClick() {
    if (active) {
      setConfirming(true);
      return;
    }
    // 署名待ちの間の連打で二重に送らない
    if (pending) return;
    setPending(true);
    reactWithDefault(event).catch((e) => {
      warn("Failed to react")(e);
      setPending(false);
    });
  }

  return (
    <>
      <ActionButton label={t("section_reaction")} pressed={on} busy={busy} onClick={onClick}>
        <span
          className={on ? `${styles.glyph} ${isStar ? styles.star : styles.heart}` : styles.glyph}
          data-shape={isStar ? "star" : "heart"}
        >
          {busy ? <span className={styles.spinner} aria-hidden="true" /> : <Glyph />}
        </span>
      </ActionButton>
      {confirming && (
        <ConfirmDialog
          title={t("unreact_title")}
          text={t("unreact_text")}
          confirmLabel={t("unreact_confirm")}
          destructive
          onConfirm={() => {
            setConfirming(false);
            reactWithDefault(event).catch(warn("Failed to undo the reaction"));
          }}
          onDismiss={() => setConfirming(false)}
        />
      )}
    </>
  );
}

/** 絵文字でリアクション（ピッカーを開き、選んだものを送る） */
function EmojiReactionButton({ event }: { event: NostrEvent }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <ActionButton label={t("web_emoji_reaction")} onClick={() => setOpen(true)}>
        <AddReactionIcon />
      </ActionButton>
      {open && (
        <ReactionPickerDialog
          target={event}
          onPick={(c, url, made) =>
            void publishReaction(event, c, url)
              .then(() => showToast(reactionSentMessage(c, made)), warn("Failed to react"))
              // [#768] 「自分の絵文字リストにも保存」は送信の後に（成否はリアクションとは別のトースト）
              .then(() => saveMadeEmoji(c, url, made))
          }
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

/**
 * ⚡ Zap（ネイティブ ZapAction）。作者の kind:0 に lud16 があるか、受領の合計が 0 より大きいときだけ出す。
 * 合計 > 0 なら右に金額（formatSats）を --zap で、0 なら --text-3。lud16 があれば押すと Zap ダイアログ（投稿への Zap）、
 * 無ければ表示だけで押せない。
 */
function ZapAction({ event }: { event: NostrEvent }) {
  const author = useProfile(event.pubkey);
  const lud16 = typeof author?.lud16 === "string" ? author.lud16.trim() : "";
  const sats = useZapSats(event.id);
  const [open, setOpen] = useState(false);
  // [#537] ウォレット接続（NWC）済みなら Zap ダイアログの受け口へアプリ内送金を渡す
  const walletConnected = useNwc((s) => s.connection !== null);
  if (lud16 === "" && sats === 0) return null;
  const label = sats > 0 ? `Zap ${formatSats(sats)} sats` : "Zap";
  const className = sats > 0 ? `${styles.zap} ${styles.zapped}` : styles.zap;
  const content = (
    <>
      <span className={styles.zapIcon}>
        <BoltIcon />
      </span>
      {sats > 0 && <span className={styles.zapAmount}>{formatSats(sats)}</span>}
    </>
  );
  if (lud16 === "") {
    return (
      <span className={className} role="img" aria-label={label} title={label}>
        {content}
      </span>
    );
  }
  return (
    <>
      <button
        type="button"
        className={`${className} ${styles.zapButton}`}
        aria-label={label}
        title={label}
        onClick={() => setOpen(true)}
      >
        {content}
      </button>
      {open && (
        <ZapDialog
          recipient={event.pubkey}
          recipientName={displayName(author, event.pubkey)}
          lud16={lud16}
          eventId={event.id}
          targetKind={event.kind}
          payWithWallet={walletConnected ? payInvoiceWithNwc : undefined}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

/** ミュート・解除の失敗の文言 */
function muteFailureMessage(e: unknown): string {
  if (e instanceof MuteListError && e.reason === "no-mute-list") {
    return t("web_mute_no_base");
  }
  if (e instanceof MuteListError && e.reason === "no-cipher") {
    return t("web_mute_no_cipher");
  }
  // ネイティブ note_mute_locked
  return t("note_mute_locked");
}

/** ブックマーク・固定の失敗の文言（#531。#478 と同じデータ保護の理由） */
function ownListFailureMessage(e: unknown): string {
  if (e instanceof OwnListError && e.reason === "unreachable") {
    return t("web_own_list_no_base");
  }
  return t("mute_change_failed");
}

/**
 * ⋯ メニュー（並びは moreMenuEntries）と、そこから開く確認・通報・イベント JSON のダイアログ。
 * [#683] NoteFooter の more props へ渡す（行の右端に固定。primaryActions の均等配置には含めない）。
 */
export function NoteMoreMenu({ event }: { event: NostrEvent }) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const author = useProfile(event.pubkey);
  const contacts = use$(() => (me ? eventStore.replaceable({ kind: 3, pubkey: me }) : undefined), [me]);
  const isMine = event.pubkey === me;
  // 自分の kind:3 が未取得の間は null（フォロー項目を出さない。空のリストで上書きしないため）
  const isFollowing = contacts ? followsFromContacts(contacts).includes(event.pubkey) : null;
  const links = useMemo(() => noteLinksOf(event), [event]);
  const isMuted = useMuteMatcher().users.has(event.pubkey);
  const isBookmarked = useIsBookmarked(event.id);
  const isPinned = useIsPinned(event.id);
  const developerMode = useDeveloperMode((s) => s.enabled);
  const canTranslate = translateAvailable() && hasBodyText(event);
  const translation = useTranslation(event.id);
  const [dialog, setDialog] = useState<"unfollow" | "mute" | "delete" | "report" | "json" | null>(null);

  function follow(action: "follow" | "unfollow") {
    if (!me) return;
    toggleFollow(me, event.pubkey, action).catch((e) => {
      showToast(
        e instanceof FollowError && e.reason === "no-contacts"
          ? t("web_follow_no_list")
          : t("web_follow_update_failed"),
      );
    });
  }

  function mute(action: "mute" | "unmute") {
    if (!me) return;
    const run = action === "mute" ? muteUser(me, event.pubkey) : unmuteUser(me, event.pubkey);
    run.then(
      () => showToast(action === "mute" ? t("muted_toast") : t("note_unmuted_toast")),
      (e) => showToast(muteFailureMessage(e)),
    );
  }

  function bookmark(action: "bookmark" | "unbookmark") {
    if (!me) return;
    toggleBookmark(me, event.id, action).then(
      () => showToast(action === "bookmark" ? t("web_bookmark_added") : t("web_bookmark_removed")),
      (e) => showToast(ownListFailureMessage(e)),
    );
  }

  function pin(action: "pin" | "unpin") {
    if (!me) return;
    togglePinned(me, event.id, action).then(
      () => showToast(action === "pin" ? t("web_pin_added") : t("web_pin_removed")),
      (e) => showToast(ownListFailureMessage(e)),
    );
  }

  const entries = moreMenuEntries({
    clientName: clientNameOf(event),
    isMine,
    isFollowing,
    isBookmarked,
    isPinned,
    isMuted,
    note1: links.note1,
    nevent: links.nevent,
    developerMode,
    translationVisible: canTranslate ? (translation?.visible ?? false) : null,
    on: {
      follow: () => follow("follow"),
      unfollow: () => setDialog("unfollow"),
      bookmark: () => bookmark("bookmark"),
      unbookmark: () => bookmark("unbookmark"),
      pin: () => pin("pin"),
      unpin: () => pin("unpin"),
      requestDelete: () => setDialog("delete"),
      mute: () => setDialog("mute"),
      unmute: () => mute("unmute"),
      report: () => setDialog("report"),
      copyText: () => void copyText(plainTextOf(event)),
      copyLink: () => void copyText(links.njump),
      copyId: () => void copyText(event.id),
      copyNote1: () => void copyText(links.note1),
      copyNevent: () => void copyText(links.nevent),
      viewJson: () => setDialog("json"),
      translate: () => {
        void requestTranslation(event.id, plainTextOf(event)).then((ok) => {
          if (!ok) showToast(t("note_translate_failed"));
        });
      },
      hideTranslation: () => hideTranslation(event.id),
    },
  });

  return (
    <>
      <MenuButton label={t("web_more_actions")} triggerClassName={ACTION_BUTTON_CLASS} entries={entries}>
        <MoreHorizIcon />
      </MenuButton>
      {dialog === "unfollow" && (
        <ConfirmDialog
          title={t("unfollow_title")}
          text={t("web_note_unfollow_text", displayName(author, event.pubkey))}
          confirmLabel={t("unfollow_confirm")}
          destructive
          onConfirm={() => {
            setDialog(null);
            follow("unfollow");
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
      {dialog === "delete" && (
        <ConfirmDialog
          title={t("note_delete_title")}
          text={t("note_delete_text")}
          confirmLabel={t("note_delete_confirm")}
          destructive
          onConfirm={() => {
            setDialog(null);
            void requestDelete(event).then((ok) =>
              showToast(ok ? t("note_delete_sent") : t("note_delete_failed")),
            );
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "report" && (
        <ReportDialog
          onPick={(type) => {
            setDialog(null);
            reportNote(event, type).catch(warn("Failed to report"));
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "json" && <EventJsonDialog event={event} onDismiss={() => setDialog(null)} />}
    </>
  );
}
