import { use$ } from "applesauce-react/hooks/use-$";
import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { useT } from "../../i18n";
import { unixNow } from "../../lib/time";
import { readRelays, requestOnce, writeRelays } from "../../nostr/pool";
import { publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { showToast } from "../../ui/toast";
import { useNow } from "../timeline/useNow";
import styles from "./StatusEditorDialog.module.css";
import {
  generalStatusDraft,
  isStatusVisible,
  oneLineStatus,
  STATUS_EXPIRY_CHOICES,
  STATUS_KIND,
  STATUS_MAX_LENGTH,
  type StatusExpiryId,
} from "./statusModel";

/** 期限切れをその場で反映する時計（StatusCard と同じ 10 秒） */
const STATUS_CLOCK_MS = 10_000;
/** いまのステータスを取りに行く待ち時間 */
const FETCH_TIMEOUT_MS = 8_000;

/**
 * [#1020] 自分のステータス（NIP-38 の 1 行。d=general）を更新する画面（ネイティブ StatusEditorDialog #1019）。
 * 開いたとき、いま出しているステータスを入れておく（手元に無ければ自分のリレーへ取りに行く。ステータスのカラムを
 * 置いていない人でも入るように）。「更新」で出し、「消す」（いま出しているときだけ）で空のステータスを出す。
 * 送信はほかの投稿と同じ送信キュー（署名できたら閉じる。受理を確かめられなければキューが送り直す）。
 * music（Now Playing）には触らない。
 */
export function StatusEditorDialog({ pubkey, onClose }: { pubkey: string; onClose(): void }) {
  const t = useT();
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descId = useId();
  const expiryId = useId();

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);

  // いまのステータスを取りに行く（自分の読み込みリレー ∪ 書き込みリレー）
  useEffect(() => {
    const relays = [...new Set([...readRelays(), ...writeRelays()])];
    if (relays.length === 0) return;
    const sub = requestOnce(
      relays,
      [{ kinds: [STATUS_KIND], authors: [pubkey], "#d": ["general"], limit: 1 }],
      FETCH_TIMEOUT_MS,
    ).subscribe({ error: () => {} });
    return () => sub.unsubscribe();
  }, [pubkey]);

  const now = useNow(STATUS_CLOCK_MS);
  const latest = use$(
    () => eventStore.replaceable({ kind: STATUS_KIND, pubkey, identifier: "general" }),
    [pubkey],
  );
  const current = latest && isStatusVisible(latest, "general", now) ? latest : null;

  const [text, setText] = useState("");
  const [touched, setTouched] = useState(false);
  const [expiry, setExpiry] = useState<StatusExpiryId>("none");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // いまのステータスが届いたら入れる（もう書き始めていたら触らない）
  const currentId = current?.id ?? null;
  const currentContent = current?.content ?? "";
  // biome-ignore lint/correctness/useExhaustiveDependencies: 入れ直すのは版（id）が変わったときだけ
  useEffect(() => {
    if (!touched) setText(currentContent);
  }, [currentId]);

  const canUpdate = !busy && oneLineStatus(text) !== "";

  async function send(content: string, done: string) {
    if (busy) return;
    setBusy(true);
    setError(null);
    const seconds = STATUS_EXPIRY_CHOICES.find((c) => c.id === expiry)?.seconds ?? null;
    try {
      await publishEvent(generalStatusDraft(content, seconds, unixNow()));
    } catch {
      setBusy(false);
      setError(t("web_status_edit_failed"));
      return;
    }
    showToast(done);
    onClose();
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (canUpdate) void send(text, t("status_edit_updated"));
  }

  return (
    <dialog
      ref={dialog}
      className={styles.dialog}
      aria-labelledby={titleId}
      aria-describedby={descId}
      aria-busy={busy}
      onCancel={(e) => {
        e.preventDefault();
        e.stopPropagation();
        if (!busy) onClose();
      }}
    >
      <h2 id={titleId} className={styles.title}>
        {t("status_edit_title")}
      </h2>
      <p id={descId} className={styles.desc}>
        {t("status_edit_desc")}
      </p>
      <form className={styles.form} onSubmit={onSubmit}>
        <input
          className={styles.input}
          type="text"
          aria-label={t("status_edit_title")}
          placeholder={t("status_edit_placeholder")}
          value={text}
          disabled={busy}
          // 上限を超える入力は受け付けない。ほかのアプリで出した長いステータスが入っているときも、短くする編集は通す
          onChange={(e) => {
            const next = e.target.value;
            if (next.length > STATUS_MAX_LENGTH && next.length >= text.length) return;
            setText(next);
            setTouched(true);
          }}
        />
        <p id={expiryId} className={styles.label}>
          {t("status_edit_expiry")}
        </p>
        <fieldset className={styles.choices} aria-labelledby={expiryId} disabled={busy}>
          {STATUS_EXPIRY_CHOICES.map((choice) => {
            const active = expiry === choice.id;
            return (
              <button
                key={choice.id}
                type="button"
                className={active ? `${styles.choice} ${styles.active}` : styles.choice}
                aria-pressed={active}
                onClick={() => setExpiry(choice.id)}
              >
                {expiryChoiceLabel(choice.id, t)}
              </button>
            );
          })}
        </fieldset>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.footer}>
          {/* 「消す」は、いまステータスを出しているときだけ */}
          {current !== null && !busy && (
            <button
              type="button"
              className={`${styles.textButton} ${styles.clear}`}
              onClick={() => void send("", t("status_edit_cleared"))}
            >
              {t("status_edit_clear")}
            </button>
          )}
          <span className={styles.spacer} />
          {busy ? (
            <span className={styles.spinner} aria-hidden="true" />
          ) : (
            <>
              <button type="button" className={styles.textButton} onClick={onClose}>
                {t("common_cancel")}
              </button>
              <button type="submit" className={styles.primary} disabled={!canUpdate}>
                {t("status_edit_update")}
              </button>
            </>
          )}
        </div>
      </form>
    </dialog>
  );
}

/** 期限の選択肢の文言（辞書のキーをそのまま書く。import-strings がキーを拾えるように） */
function expiryChoiceLabel(id: StatusExpiryId, t: (key: string) => string): string {
  switch (id) {
    case "none":
      return t("status_expiry_none");
    case "30m":
      return t("status_expiry_30m");
    case "1h":
      return t("status_expiry_1h");
    case "4h":
      return t("status_expiry_4h");
    case "1d":
      return t("status_expiry_1d");
    case "1w":
      return t("status_expiry_1w");
  }
}
