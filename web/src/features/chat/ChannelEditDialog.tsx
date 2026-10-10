import type { NostrEvent } from "nostr-tools/pure";
import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { t, useT } from "../../i18n";
import { currentSigner } from "../../signer/session";
import { CloseIcon } from "../../ui/icons";
import { showToast } from "../../ui/toast";
import { uploadServers, useMediaServer } from "../compose/mediaServer";
import { uploadMedia } from "../compose/nip96";
import { processImage } from "../compose/processMedia";
import styles from "./ChannelEditDialog.module.css";
import { ChannelIcon } from "./ChannelList";
import {
  ChannelEditError,
  type ChannelFields,
  publishChannelEdit,
  publishNewChannel,
  refetchOwnChannelMeta,
  useOwnChannelMeta,
} from "./channelEdit";
import { type Channel, upsertLocalChannel } from "./channels";

type Draft = { basedOnId: string | null; fields: ChannelFields };

function fieldsOf(base: { name: string; about: string; picture: string | null } | null): ChannelFields {
  return { name: base?.name ?? "", about: base?.about ?? "", picture: base?.picture ?? "" };
}

function metaFieldsOf(
  event: NostrEvent | null,
): { name: string; about: string; picture: string | null } | null {
  if (!event) return null;
  try {
    const json: unknown = JSON.parse(event.content);
    if (typeof json !== "object" || json === null || Array.isArray(json)) return null;
    const record = json as Record<string, unknown>;
    return {
      name: typeof record.name === "string" ? record.name : "",
      about: typeof record.about === "string" ? record.about : "",
      picture: typeof record.picture === "string" ? record.picture : null,
    };
  } catch {
    return null;
  }
}

/** 保存の失敗の文言（編集。#478 と同じ理由。ProfileEditSection と同じ言い回し） */
function failureMessage(e: unknown): string {
  if (e instanceof ChannelEditError && e.reason === "unreachable") {
    return t("web_channel_no_base");
  }
  if (e instanceof ChannelEditError && e.reason === "stale") {
    return t("web_channel_stale");
  }
  return t("web_channel_save_failed");
}

/**
 * スレッド（NIP-28 チャンネル）の作成（kind:40）・編集（kind:41）ダイアログ（ネイティブ ChannelEditSheet）。
 * channel が null なら作成、渡せば編集。編集は開いたら #478 の取り直しが終わるまで入力欄を無効にし
 * （ProfileEditSection と同じ）、保存の直前にもう一度取り直して食い違えば保存しない（publishChannelEdit）。
 * 画像は URL の直接入力のほか、NIP-96 アップロード（投稿と同じ既定の圧縮）でも選べる。
 * 保存できたらローカルの一覧へすぐ反映して onDone（作成ならそのままルームを開く・編集なら閉じるだけ）。
 */
export function ChannelEditDialog({
  me,
  channel,
  onDone,
  onDismiss,
}: {
  me: string;
  /** 編集対象（null なら作成） */
  channel: Channel | null;
  onDone(channel: Channel): void;
  onDismiss(): void;
}) {
  const t = useT();
  const isEdit = channel !== null;
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const nameId = useId();
  const aboutId = useId();
  const pictureId = useId();
  const fileInput = useRef<HTMLInputElement>(null);
  const mediaServer = useMediaServer((s) => s.server);

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);

  // 編集: 開いた直後に自分の最新版を取り直す（#478）。読むのは useOwnChannelMeta（ストアの今の中身）
  const [loaded, setLoaded] = useState(!isEdit);
  useEffect(() => {
    if (!channel) return;
    let alive = true;
    refetchOwnChannelMeta(me, channel.id, channel.relays)
      .catch(() => null)
      .finally(() => {
        if (alive) setLoaded(true);
      });
    return () => {
      alive = false;
    };
  }, [me, channel]);

  const latestMeta = useOwnChannelMeta(isEdit ? me : null, channel?.id ?? null);
  const liveFields = fieldsOf(isEdit ? (metaFieldsOf(latestMeta) ?? channel) : null);

  const [draft, setDraft] = useState<Draft | null>(null);
  const [uploading, setUploading] = useState(false);
  const [saving, setSaving] = useState(false);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    const ac = new AbortController();
    controller.current = ac;
    return () => ac.abort();
  }, []);

  const fields = draft?.fields ?? liveFields;

  /** 編集を始める（始めた時点の版を覚える）。patch があればその項目を変える */
  function edit(patch: Partial<ChannelFields>) {
    setDraft((prev) => {
      const base = prev ?? { basedOnId: latestMeta?.id ?? null, fields: liveFields };
      return { basedOnId: base.basedOnId, fields: { ...base.fields, ...patch } };
    });
  }

  async function pickIcon(file: File) {
    const signal = controller.current?.signal;
    const signer = currentSigner();
    if (!signer || !file.type.startsWith("image/")) {
      showToast(t("channel_icon_upload_failed"));
      return;
    }
    // 選んだ時点の版で編集を始める（アップロード中に届いた版で上書きしない）
    edit({});
    setUploading(true);
    try {
      const processed = await processImage(file);
      const result = await uploadMedia(processed, uploadServers(mediaServer), signer, signal);
      if (signal?.aborted) return;
      if (result) edit({ picture: result.url });
      else showToast(t("channel_icon_upload_failed"));
    } catch {
      if (!signal?.aborted) showToast(t("channel_icon_upload_failed"));
    } finally {
      if (!signal?.aborted) setUploading(false);
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const trimmed: ChannelFields = {
      name: fields.name.trim(),
      about: fields.about.trim(),
      picture: fields.picture.trim(),
    };
    if (trimmed.name === "" || saving || uploading) return;
    setSaving(true);
    try {
      if (isEdit && channel) {
        await publishChannelEdit({
          me,
          channelId: channel.id,
          channelRelays: channel.relays,
          fields: trimmed,
          basedOnId: draft?.basedOnId ?? latestMeta?.id ?? null,
        });
        const updated: Channel = {
          ...channel,
          name: trimmed.name,
          about: trimmed.about,
          picture: trimmed.picture === "" ? null : trimmed.picture,
        };
        upsertLocalChannel(updated);
        onDone(updated);
      } else {
        const signed = await publishNewChannel(trimmed);
        const created: Channel = {
          id: signed.id,
          name: trimmed.name,
          about: trimmed.about,
          picture: trimmed.picture === "" ? null : trimmed.picture,
          relays: [],
          createdAt: signed.created_at,
          lastAt: signed.created_at,
        };
        upsertLocalChannel(created);
        onDone(created);
      }
    } catch (err) {
      showToast(isEdit ? failureMessage(err) : t("web_channel_create_failed"));
    } finally {
      setSaving(false);
    }
  }

  const disabled = (isEdit && !loaded) || saving;
  const canSubmit = !disabled && !uploading && fields.name.trim() !== "";

  return (
    <dialog
      ref={dialog}
      className={styles.dialog}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        if (!saving) onDismiss();
      }}
    >
      <div className={styles.head}>
        <h2 id={titleId} className={styles.title}>
          {isEdit ? t("channel_edit_title") : t("channel_create_title")}
        </h2>
        <button type="button" className={styles.close} aria-label={t("common_close")} onClick={onDismiss}>
          <CloseIcon className={styles.closeIcon} />
        </button>
      </div>
      {!isEdit && <p className={styles.note}>{t("channel_create_note")}</p>}
      {isEdit && !loaded && (
        <p className={styles.note} role="status">
          {t("loading")}
        </p>
      )}
      <form className={styles.form} onSubmit={submit}>
        <div className={styles.field}>
          <label htmlFor={nameId} className={styles.label}>
            {t("channel_field_name")}
          </label>
          <input
            id={nameId}
            className={styles.input}
            type="text"
            value={fields.name}
            disabled={disabled}
            onChange={(e) => edit({ name: e.target.value })}
          />
        </div>
        <div className={styles.field}>
          <label htmlFor={aboutId} className={styles.label}>
            {t("channel_field_about")}
          </label>
          <textarea
            id={aboutId}
            className={`${styles.input} ${styles.textarea}`}
            rows={3}
            value={fields.about}
            disabled={disabled}
            onChange={(e) => edit({ about: e.target.value })}
          />
        </div>
        <div className={styles.field}>
          <label htmlFor={pictureId} className={styles.label}>
            {t("channel_field_picture")}
          </label>
          <div className={styles.iconRow}>
            <ChannelIcon key={fields.picture} name={fields.name} url={fields.picture || null} />
            <input
              id={pictureId}
              className={styles.input}
              type="text"
              inputMode="url"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              placeholder="https://…"
              value={fields.picture}
              disabled={disabled || uploading}
              onChange={(e) => edit({ picture: e.target.value })}
            />
            <button
              type="button"
              className={styles.ghost}
              disabled={disabled || uploading}
              onClick={() => fileInput.current?.click()}
            >
              {uploading ? t("web_uploading") : t("channel_icon_pick")}
            </button>
            {fields.picture !== "" && !uploading && (
              <button
                type="button"
                className={styles.clear}
                aria-label={t("channel_icon_clear")}
                disabled={disabled}
                onClick={() => edit({ picture: "" })}
              >
                <CloseIcon className={styles.clearIcon} />
              </button>
            )}
            <input
              ref={fileInput}
              type="file"
              accept="image/*"
              hidden
              aria-label={t("web_channel_icon_file")}
              onChange={(e) => {
                const input = e.currentTarget;
                const file = input.files?.[0];
                input.value = "";
                if (file) void pickIcon(file);
              }}
            />
          </div>
        </div>
        <div className={styles.footer}>
          <button type="button" className={styles.textButton} onClick={onDismiss}>
            {t("common_cancel")}
          </button>
          <button type="submit" className={styles.primary} disabled={!canSubmit}>
            {saving ? t("common_saving") : isEdit ? t("channel_edit_submit") : t("channel_create_submit")}
          </button>
        </div>
      </form>
    </dialog>
  );
}
