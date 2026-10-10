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
 * me が非 null（他人のプロフィール）なら、ミュート / 解除（確認ダイアログ）とユーザーの通報も出す。
 */
export function ProfileMenu({
  pubkey,
  me,
  muted,
  onCopied,
}: {
  pubkey: string;
  /** 自分の pubkey（自分のプロフィールを見ているときは null。ミュート・通報の項目を出さない） */
  me: string | null;
  muted: boolean;
  onCopied: (message: string) => void;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [dialog, setDialog] = useState<"mute" | "report" | null>(null);
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

  function mute(action: "mute" | "unmute") {
    if (!me) return;
    const run = action === "mute" ? muteUser(me, pubkey) : unmuteUser(me, pubkey);
    run.then(
      () => showToast(action === "mute" ? t("muted_toast") : t("note_unmuted_toast")),
      (e) => showToast(muteFailureMessage(e)),
    );
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
              <button
                type="button"
                role="menuitem"
                className={styles.menuItem}
                onClick={() => {
                  setOpen(false);
                  setDialog("mute");
                }}
              >
                {muted ? t("note_unmute_user") : t("mute_confirm")}
              </button>
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
      {dialog === "mute" &&
        (muted ? (
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
        ) : (
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
        ))}
      {dialog === "report" && (
        <ReportDialog title={t("report_user_title")} onPick={report} onDismiss={() => setDialog(null)} />
      )}
    </div>
  );
}
