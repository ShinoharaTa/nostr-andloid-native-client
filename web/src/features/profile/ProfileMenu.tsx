import { nprofileEncode } from "nostr-tools/nip19";
import { useEffect, useRef, useState } from "react";
import { useCloseMenuOnBack } from "../../app/history";
import { t, useT } from "../../i18n";
import { relayHintsOf } from "../../nostr/outbox";
import { ConfirmDialog } from "../../ui/ConfirmDialog";
import { Icon } from "../../ui/icons";
import { showToast } from "../../ui/toast";
import { ReportDialog } from "../actions/ReportDialog";
import { reportUser } from "../actions/reactions";
import { blockUser, unblockUser } from "../mute/block";
import type { UserMuteState } from "../mute/muteList";
import { MuteListError, muteUser, unmuteUser } from "../mute/muteSync";
import styles from "./ProfileHeaderCard.module.css";

/** ミュート・解除の失敗の文言（ネイティブ note_mute_locked。NoteActionButtons と同じ） */
function muteFailureMessage(e: unknown): string {
  if (e instanceof MuteListError && e.reason === "no-mute-list") {
    return t("web_mute_no_base");
  }
  if (e instanceof MuteListError && e.reason === "no-cipher") {
    return t("web_mute_no_cipher");
  }
  return t("note_mute_locked");
}

/**
 * プロフィールの ⋯ メニュー。nprofile（相手の kind:10002 の先頭 3 件をリレーヒントに）と njump のリンクをコピーする。
 * 開閉は ColumnMenu と同じ（外側のクリック・Escape・戻るで閉じる。#540）。
 * me が非 null（他人のプロフィール）なら、ミュート・ブロック / 解除（確認ダイアログ）とユーザーの通報も出す。
 * [#963] muteState: none = 「ミュート」「ブロック」、muted = 「ミュートを解除」「ブロック」、blocked = 「ブロックを解除」。
 */
export function ProfileMenu({
  pubkey,
  me,
  muteState,
  onCopied,
}: {
  pubkey: string;
  /** 自分の pubkey（自分のプロフィールを見ているときは null。ミュート・通報の項目を出さない） */
  me: string | null;
  muteState: UserMuteState;
  onCopied: (message: string) => void;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [dialog, setDialog] = useState<"mute" | "unmute" | "block" | "unblock" | "report" | null>(null);
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);

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

  const nprofile = () => nprofileEncode({ pubkey, relays: relayHintsOf(pubkey) });

  async function copy(text: string, message: string) {
    setOpen(false);
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      onCopied(t("web_copy_failed"));
      return;
    }
    onCopied(message);
  }

  function mute(action: "mute" | "unmute" | "block" | "unblock") {
    if (!me) return;
    switch (action) {
      case "block":
        // [#963] ブロック（すぐ隠し、通報し、ミュートリストに公開で保存する。保存できなくても失敗にはしない）
        void blockUser(me, pubkey).then((result) =>
          showToast(result === "saved" ? t("blocked_toast") : t("block_pending_toast")),
        );
        return;
      case "mute":
        muteUser(me, pubkey).then(
          () => showToast(t("muted_toast")),
          (e) => showToast(muteFailureMessage(e)),
        );
        return;
      case "unmute":
        unmuteUser(me, pubkey).then(
          () => showToast(t("note_unmuted_toast")),
          (e) => showToast(muteFailureMessage(e)),
        );
        return;
      case "unblock":
        unblockUser(me, pubkey).then(
          () => showToast(t("note_unblocked_toast")),
          (e) => showToast(muteFailureMessage(e)),
        );
        return;
    }
  }

  function openDialog(next: "mute" | "unmute" | "block" | "unblock") {
    setOpen(false);
    setDialog(next);
  }

  function report(type: string) {
    setDialog(null);
    reportUser(pubkey, type).then(
      () => showToast(t("reported_toast")),
      (e) => console.warn("[profile] Failed to report", e),
    );
  }

  return (
    <div ref={root} className={styles.menuRoot}>
      <button
        ref={button}
        type="button"
        className={styles.circle}
        aria-label={t("menu")}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="moreHoriz" size="md" />
      </button>
      {open && (
        <div role="menu" aria-label={t("menu")} className={styles.menu}>
          <button
            type="button"
            role="menuitem"
            className={styles.menuItem}
            onClick={() => copy(nprofile(), t("nprofile_copied"))}
          >
            {t("copy_nprofile")}
          </button>
          <button
            type="button"
            role="menuitem"
            className={styles.menuItem}
            onClick={() => copy(`https://njump.me/${nprofile()}`, t("link_copied"))}
          >
            {t("note_copy_link")}
          </button>
          {me !== null && (
            <>
              {muteState === "blocked" ? (
                <button
                  type="button"
                  role="menuitem"
                  className={styles.menuItem}
                  onClick={() => openDialog("unblock")}
                >
                  {t("note_unblock_user")}
                </button>
              ) : (
                <>
                  <button
                    type="button"
                    role="menuitem"
                    className={styles.menuItem}
                    onClick={() => openDialog(muteState === "muted" ? "unmute" : "mute")}
                  >
                    {muteState === "muted" ? t("note_unmute_user") : t("mute_confirm")}
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    className={styles.menuItem}
                    onClick={() => openDialog("block")}
                  >
                    {t("block_confirm")}
                  </button>
                </>
              )}
              <button
                type="button"
                role="menuitem"
                className={`${styles.menuItem} ${styles.danger}`}
                onClick={() => {
                  setOpen(false);
                  setDialog("report");
                }}
              >
                {t("note_report")}
              </button>
            </>
          )}
        </div>
      )}
      {dialog === "unmute" && (
        <ConfirmDialog
          title={t("unmute_confirm_title")}
          text={t("web_profile_unmute_text")}
          confirmLabel={t("lift_confirm")}
          onConfirm={() => {
            setDialog(null);
            mute("unmute");
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "mute" && (
        <ConfirmDialog
          title={t("mute_confirm_title")}
          text={t("mute_confirm_text2")}
          confirmLabel={t("mute_confirm")}
          destructive
          onConfirm={() => {
            setDialog(null);
            mute("mute");
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "unblock" && (
        <ConfirmDialog
          title={t("unblock_confirm_title")}
          text={t("unblock_confirm_text")}
          confirmLabel={t("lift_confirm")}
          onConfirm={() => {
            setDialog(null);
            mute("unblock");
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "block" && (
        <ConfirmDialog
          title={t("block_confirm_title")}
          text={t("block_confirm_text")}
          confirmLabel={t("block_confirm")}
          destructive
          onConfirm={() => {
            setDialog(null);
            mute("block");
          }}
          onDismiss={() => setDialog(null)}
        />
      )}
      {dialog === "report" && (
        <ReportDialog title={t("report_user_title")} onPick={report} onDismiss={() => setDialog(null)} />
      )}
    </div>
  );
}
